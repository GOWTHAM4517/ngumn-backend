package com.ngumn.backend.service;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.ReportLifetime;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Community trust - road users check each other's reports with likes and
 * dislikes, no admin needed.
 *
 * - 👍 Like = "it's true". LIKES_TO_TRUST likes (and more likes than
 *   dislikes) make a report trusted (VERIFIED): the reporter earns
 *   REPORTER_POINTS and everyone who liked it VOTER_POINTS. For a
 *   rule-breaker report about an NGUMN vehicle, its driver is alerted and
 *   the violation goes on their driving record.
 * - 👎 Dislike = "it's wrong". More than two dislikes (DISLIKES_TO_REMOVE,
 *   and more dislikes than likes) take it down (REJECTED): the reporter
 *   loses REMOVED_REPORT_PENALTY points and everyone who disliked it earns
 *   VOTER_POINTS.
 * - Either way the reporter is told (an alert: "Report trusted: ..." /
 *   "Report removed: ..." - a phone notification when the app is closed).
 *
 * A decision is final. A report that is simply over - "not an issue
 * anymore" from two people, removed by its reporter, or expired - is
 * cleared instead, and nobody gains or loses points for that (see
 * ReportFeedbackService, where likes, dislikes and clearing happen).
 *
 * Trust: a 0-100 score from how a person's reports turned out and how often
 * their likes and dislikes (and, from older app versions, their Yes / No
 * answers) matched the outcome. It changes how many likes their reports
 * need - 2 for highly trusted people, 4 for people whose reports keep
 * getting taken down.
 *
 * A report of a speeding, wrong-way or rash driver doesn't wait: drivers
 * near it are warned straight away (NearbyDangerService), with the warning
 * marked "not verified yet".
 */
@Service
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

    /** Likes that make a report trusted - fewer for trusted reporters, more for people whose reports get taken down. */
    public static final int LIKES_TO_TRUST = 3;
    public static final int LIKES_TO_TRUST_TRUSTED = 2;
    public static final int LIKES_TO_TRUST_LOW_TRUST = 4;
    /** "More than two dislikes" take a report down (as long as more people disliked it than liked it). */
    public static final int DISLIKES_TO_REMOVE = 3;
    public static final int REPORTER_POINTS = 10;
    /** Points a reporter loses when their report is disliked off the road. */
    public static final int REMOVED_REPORT_PENALTY = 10;
    /** Points for each like / dislike that matched how the report turned out. */
    public static final int VOTER_POINTS = 2;

    /** Spam guard: at most this many rule-breaker reports per person in 10 minutes. */
    private static final int MAX_COMPLAINTS_PER_10_MIN = 5;

    private final RoadReportRepository roadReportRepository;
    private final ViolationComplaintRepository complaintRepository;
    private final CommunityVoteRepository voteRepository;
    private final ReportFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final RewardService rewardService;
    private final TrafficRuleService trafficRuleService;
    private final NearbyDangerService nearbyDangerService;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;

    public CommunityService(RoadReportRepository roadReportRepository,
                            ViolationComplaintRepository complaintRepository,
                            CommunityVoteRepository voteRepository,
                            ReportFeedbackRepository feedbackRepository,
                            UserRepository userRepository,
                            VehicleRepository vehicleRepository,
                            RewardService rewardService,
                            TrafficRuleService trafficRuleService,
                            NearbyDangerService nearbyDangerService,
                            AlertService alertService,
                            NgumnWebSocketHandler webSocketHandler) {
        this.roadReportRepository = roadReportRepository;
        this.complaintRepository = complaintRepository;
        this.voteRepository = voteRepository;
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.rewardService = rewardService;
        this.trafficRuleService = trafficRuleService;
        this.nearbyDangerService = nearbyDangerService;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
    }

    // ------------------------------------------------------------------
    // Hazard reports
    // ------------------------------------------------------------------

    /**
     * Reports as the viewer sees them: like / dislike counts, the reporter's
     * trust and how many likes their reports need, whether each is still on
     * the road, and the viewer's own like / dislike / "not there anymore".
     */
    public List<RoadReportResponse> describeReports(User viewer, List<RoadReport> reports) {
        Map<Long, Boolean> olderVotes = votesBy(viewer, VoteTarget.REPORT);
        Set<Long> myLikes = feedbackBy(viewer, VoteTarget.REPORT, FeedbackKind.HELPFUL);
        Set<Long> myDislikes = feedbackBy(viewer, VoteTarget.REPORT, FeedbackKind.DISLIKE);
        Set<Long> myGone = feedbackBy(viewer, VoteTarget.REPORT, FeedbackKind.GONE);
        Map<Long, TrustResponse> trustCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        List<RoadReportResponse> out = new ArrayList<>();
        for (RoadReport r : reports) {
            TrustResponse trust = r.getReporter() != null
                    ? trustCache.computeIfAbsent(r.getReporter().getId(), this::trustFor)
                    : null;
            Long id = r.getId();
            out.add(RoadReportResponse.from(r,
                    trust != null ? trust.getTrustScore() : null,
                    trust != null ? trust.getConfirmationsNeeded() : LIKES_TO_TRUST,
                    myVote(id, myLikes, myDislikes, myGone, olderVotes),
                    myLikes.contains(id),
                    myDislikes.contains(id),
                    myGone.contains(id),
                    now));
        }
        return out;
    }

    public RoadReportResponse describeReport(User viewer, RoadReport report) {
        return describeReports(viewer, List.of(report)).get(0);
    }

    /**
     * Decides a hazard report from its likes and dislikes (once - see the
     * class comment), and pays out points and tells the reporter. Call it
     * after the counts change. Returns the report as saved.
     */
    public RoadReport settleReport(RoadReport report) {
        // decidedAt: already decided once (an admin may have reopened it) - never pay out twice.
        if (report.getStatus() != ReportStatus.PENDING || report.getDecidedAt() != null
                || !report.isActiveAt(LocalDateTime.now())) return report;
        int likes = orZero(report.getHelpfulCount());
        int dislikes = orZero(report.getDislikeCount());
        Long reporterId = report.getReporter() != null ? report.getReporter().getId() : null;
        ReportStatus outcome = outcome(likes, dislikes, likesNeeded(reporterId));
        if (outcome == null) return report;

        report.setStatus(outcome);
        report.setDecidedAt(LocalDateTime.now());
        report = roadReportRepository.save(report);
        boolean trusted = outcome == ReportStatus.VERIFIED;
        String what = reportWhat(report);
        settleReactions(VoteTarget.REPORT, report.getId(), trusted, withArticle(what), report);
        if (reporterId != null) {
            if (trusted) {
                grant(reporterId, REPORTER_POINTS, "Your " + what + " was trusted - " + people(likes) + " liked it", report);
                tellReporter(report.getReporter(), "Report trusted: " + people(likes) + " liked your " + what
                        + ". You earned " + REPORTER_POINTS + " points - thanks for looking out for others.",
                        report.getLatitude(), report.getLongitude(), report.getId(), null);
            } else {
                grant(reporterId, -REMOVED_REPORT_PENALTY, "Your " + what + " was taken down - " + people(dislikes) + " disliked it", report);
                tellReporter(report.getReporter(), "Report removed: " + people(dislikes) + " disliked your " + what
                        + ", so it was taken down. You lost " + REMOVED_REPORT_PENALTY + " points.",
                        report.getLatitude(), report.getLongitude(), report.getId(), null);
            }
        }
        return report;
    }

    // ------------------------------------------------------------------
    // Rule-breaker complaints
    // ------------------------------------------------------------------

    public ComplaintResponse fileComplaint(User reporter, ComplaintRequest request) {
        TrafficRuleService.checkCoordinates(request.getLatitude(), request.getLongitude());
        long recent = complaintRepository.countByReporterIdAndCreatedAtAfter(reporter.getId(),
                LocalDateTime.now().minusMinutes(10));
        if (recent >= MAX_COMPLAINTS_PER_10_MIN) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've sent several reports in the last few minutes - please wait a little before sending more.");
        }

        Vehicle accused = null;
        if (request.getAccusedVehicleId() != null) {
            accused = vehicleRepository.findById(request.getAccusedVehicleId())
                    .orElseThrow(() -> ApiException.notFound("That vehicle is no longer on the network"));
            if (accused.getOwner() != null && accused.getOwner().getId().equals(reporter.getId())) {
                throw ApiException.badRequest("You can't report your own vehicle");
            }
        }

        ViolationComplaint complaint = complaintRepository.save(ViolationComplaint.builder()
                .reporter(reporter)
                .type(request.getType())
                .accusedVehicle(accused)
                .plateNumber(normalizePlate(request.getPlateNumber()))
                .description(TrafficRuleService.trimToNull(request.getDescription()))
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .status(ReportStatus.PENDING)
                .confirmations(0)
                .denials(0)
                .build());
        webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        try {
            // Tell the people around it now (a warning if it's dangerous driving coming their way).
            nearbyDangerService.warnAboutReport(complaint);
        } catch (RuntimeException e) {
            // Best effort - the report itself is already saved.
            log.warn("Couldn't warn drivers near complaint {}: {}", complaint.getId(), e.getMessage());
        }
        return describeComplaint(reporter, complaint);
    }

    public List<ComplaintResponse> recentComplaints(User viewer) {
        return recentComplaints(viewer, null, null, null);
    }

    /**
     * The latest rule-breaker reports. With lat / lng (what the app asks
     * for): only the ones still shown - from the last two hours, not taken
     * down or cleared - within radiusMeters (default 10 km, at most 20 km)
     * of that point, so people only see what's happening around them.
     * Without (the admin dashboard): the latest 100, whatever happened to
     * them.
     */
    public List<ComplaintResponse> recentComplaints(User viewer, Double lat, Double lng, Double radiusMeters) {
        List<ViolationComplaint> latest = complaintRepository.findTop100ByOrderByCreatedAtDesc();
        if (lat != null && lng != null) {
            LocalDateTime now = LocalDateTime.now();
            double radius = VehicleService.clampRadius(radiusMeters, 10_000);
            latest = latest.stream()
                    .filter(c -> c.isActiveAt(now))
                    .filter(c -> c.getLatitude() != null && c.getLongitude() != null
                            && GeoUtil.distanceMeters(lat, lng, c.getLatitude(), c.getLongitude()) <= radius)
                    .toList();
        }
        return describeComplaints(viewer, latest);
    }

    public List<ComplaintResponse> myComplaints(User viewer) {
        return describeComplaints(viewer, complaintRepository.findByReporterIdOrderByCreatedAtDesc(viewer.getId()));
    }

    public List<ComplaintResponse> describeComplaints(User viewer, List<ViolationComplaint> complaints) {
        Map<Long, Boolean> olderVotes = votesBy(viewer, VoteTarget.COMPLAINT);
        Set<Long> myLikes = feedbackBy(viewer, VoteTarget.COMPLAINT, FeedbackKind.HELPFUL);
        Set<Long> myDislikes = feedbackBy(viewer, VoteTarget.COMPLAINT, FeedbackKind.DISLIKE);
        Set<Long> myGone = feedbackBy(viewer, VoteTarget.COMPLAINT, FeedbackKind.GONE);
        Map<Long, TrustResponse> trustCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        List<ComplaintResponse> out = new ArrayList<>();
        for (ViolationComplaint c : complaints) {
            TrustResponse trust = c.getReporter() != null
                    ? trustCache.computeIfAbsent(c.getReporter().getId(), this::trustFor)
                    : null;
            Long id = c.getId();
            out.add(ComplaintResponse.from(c,
                    trust != null ? trust.getTrustScore() : null,
                    trust != null ? trust.getConfirmationsNeeded() : LIKES_TO_TRUST,
                    myVote(id, myLikes, myDislikes, myGone, olderVotes),
                    viewer != null ? viewer.getId() : null,
                    myLikes.contains(id),
                    myDislikes.contains(id),
                    myGone.contains(id),
                    now));
        }
        return out;
    }

    public ComplaintResponse describeComplaint(User viewer, ViolationComplaint complaint) {
        return describeComplaints(viewer, List.of(complaint)).get(0);
    }

    /** One rule-breaker report by id - e.g. opened from a notification. */
    public ComplaintResponse complaint(User viewer, Long id) {
        return describeComplaint(viewer, complaintRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Report not found")));
    }

    /** One hazard report by id (also after it has cleared) - e.g. opened from a notification. */
    public RoadReportResponse report(User viewer, Long id) {
        return describeReport(viewer, roadReportRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Report not found")));
    }

    /**
     * Decides a rule-breaker report from its likes and dislikes (once - see
     * the class comment). A trusted one about an NGUMN vehicle goes on its
     * driver's record, and they're alerted. Returns the report as saved.
     */
    public ViolationComplaint settleComplaint(ViolationComplaint complaint) {
        if (complaint.getStatus() != ReportStatus.PENDING || complaint.getDecidedAt() != null
                || !complaint.isActiveAt(LocalDateTime.now())) return complaint;
        int likes = orZero(complaint.getHelpfulCount());
        int dislikes = orZero(complaint.getDislikeCount());
        Long reporterId = complaint.getReporter() != null ? complaint.getReporter().getId() : null;
        ReportStatus outcome = outcome(likes, dislikes, likesNeeded(reporterId));
        if (outcome == null) return complaint;

        complaint.setStatus(outcome);
        complaint.setDecidedAt(LocalDateTime.now());
        complaint = complaintRepository.save(complaint);
        boolean trusted = outcome == ReportStatus.VERIFIED;
        settleReactions(VoteTarget.COMPLAINT, complaint.getId(), trusted, "a rule-breaker report", null);
        if (reporterId != null) {
            if (trusted) {
                grant(reporterId, REPORTER_POINTS, "Your rule-breaker report was trusted - " + people(likes) + " liked it", null);
                tellReporter(complaint.getReporter(), "Report trusted: " + people(likes) + " liked your rule-breaker report. You earned "
                        + REPORTER_POINTS + " points - thanks for looking out for others.",
                        complaint.getLatitude(), complaint.getLongitude(), null, complaint.getId());
            } else {
                grant(reporterId, -REMOVED_REPORT_PENALTY, "Your rule-breaker report was taken down - " + people(dislikes) + " disliked it", null);
                tellReporter(complaint.getReporter(), "Report removed: " + people(dislikes) + " disliked your rule-breaker report, so it was taken down. You lost "
                        + REMOVED_REPORT_PENALTY + " points.",
                        complaint.getLatitude(), complaint.getLongitude(), null, complaint.getId());
            }
        }
        Vehicle accused = complaint.getAccusedVehicle();
        if (trusted && accused != null && accused.getOwner() != null) {
            boolean simulatedVehicle = Boolean.TRUE.equals(accused.getIsSimulated());
            trafficRuleService.record(TrafficViolation.builder()
                    .vehicle(accused)
                    .driver(accused.getOwner())
                    .type(complaint.getType())
                    .source(ViolationSource.COMMUNITY)
                    .latitude(complaint.getLatitude())
                    .longitude(complaint.getLongitude())
                    .complaintId(complaint.getId())
                    .message(people(likes) + " on the road confirmed your vehicle was "
                            + TrafficRuleService.phrase(complaint.getType())
                            + ". Please follow the rules - it keeps everyone safe.")
                    .simulated(simulatedVehicle)
                    .build(), !simulatedVehicle);
        }
        return complaint;
    }

    // ------------------------------------------------------------------
    // Trust
    // ------------------------------------------------------------------

    public TrustResponse trustFor(Long userId) {
        long verified = roadReportRepository.countByReporterIdAndStatus(userId, ReportStatus.VERIFIED)
                + complaintRepository.countByReporterIdAndStatus(userId, ReportStatus.VERIFIED);
        long rejected = roadReportRepository.countByReporterIdAndStatus(userId, ReportStatus.REJECTED)
                + complaintRepository.countByReporterIdAndStatus(userId, ReportStatus.REJECTED);
        // Likes / dislikes on reports that have been decided, plus older apps' Yes / No answers.
        long likesMatched = feedbackRepository.countByUserIdAndOutcomeMatchedTrue(userId);
        long likesMissed = feedbackRepository.countByUserIdAndOutcomeMatchedFalse(userId);
        long votes = voteRepository.countByVoterId(userId) + likesMatched + likesMissed;
        long matched = voteRepository.countByVoterIdAndOutcomeMatchedTrue(userId) + likesMatched;
        long missed = voteRepository.countByVoterIdAndOutcomeMatchedFalse(userId) + likesMissed;

        // Reports count fully, answers a quarter each; +1/+2 smoothing puts
        // a newcomer at 50.
        double good = verified + 0.25 * matched;
        double bad = rejected + 0.25 * missed;
        int score = (int) Math.round(100.0 * (good + 1) / (good + bad + 2));

        String level;
        if (verified + rejected + (matched + missed) / 4.0 < 2) level = "New";
        else if (score >= 80) level = "Highly trusted";
        else if (score >= 60) level = "Trusted";
        else if (score >= 40) level = "Mixed record";
        else level = "Low trust";

        int needed = LIKES_TO_TRUST;
        if (score >= 80 && verified >= 3) needed = LIKES_TO_TRUST_TRUSTED;
        else if (score < 40 && rejected >= 2) needed = LIKES_TO_TRUST_LOW_TRUST;

        return new TrustResponse(userId, score, level, verified, rejected, votes, matched, needed);
    }

    /** How many likes a report by this person needs to be trusted. */
    public int likesNeeded(Long reporterId) {
        return reporterId == null ? LIKES_TO_TRUST : trustFor(reporterId).getConfirmationsNeeded();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Trusted, taken down, or (null) not decided yet. */
    public static ReportStatus outcome(int likes, int dislikes, int likesNeeded) {
        if (likes >= likesNeeded && likes > dislikes) return ReportStatus.VERIFIED;
        if (dislikes >= DISLIKES_TO_REMOVE && dislikes > likes) return ReportStatus.REJECTED;
        return null;
    }

    /**
     * Records whether each like / dislike matched the outcome (for trust)
     * and rewards the ones that did. Older apps' Yes / No answers on it are
     * marked too.
     */
    private void settleReactions(VoteTarget target, Long targetId, boolean trusted, String what, RoadReport report) {
        for (ReportFeedback f : feedbackRepository.findByTargetTypeAndTargetId(target, targetId)) {
            if (f.getKind() != FeedbackKind.HELPFUL && f.getKind() != FeedbackKind.DISLIKE) continue;
            boolean matched = (f.getKind() == FeedbackKind.HELPFUL) == trusted;
            f.setOutcomeMatched(matched);
            feedbackRepository.save(f);
            if (matched && f.getUser() != null) {
                grant(f.getUser().getId(), VOTER_POINTS,
                        trusted ? "You helped confirm " + what : "You helped take down " + what + " that was wrong", report);
            }
        }
        for (CommunityVote vote : voteRepository.findByTargetTypeAndTargetId(target, targetId)) {
            vote.setOutcomeMatched(Boolean.TRUE.equals(vote.getAgree()) == trusted);
            voteRepository.save(vote);
        }
    }

    /** "Report trusted: ..." / "Report removed: ..." to the reporter. Best effort - the decision stands either way. */
    private void tellReporter(User reporter, String message, Double lat, Double lng, Long reportId, Long complaintId) {
        if (reporter == null) return;
        try {
            alertService.raise(null, reporter, AlertType.ROAD_HAZARD, RiskLevel.LOW, TrafficRuleService.truncate(message, 290),
                    lat, lng, reportId, complaintId);
        } catch (RuntimeException e) {
            log.warn("Couldn't tell reporter {} about their report: {}", reporter.getId(), e.getMessage());
        }
    }

    /** For older apps' "You said yes / no": a like is yes; a dislike or "not there anymore" is no. */
    private static Boolean myVote(Long id, Set<Long> likes, Set<Long> dislikes, Set<Long> gone, Map<Long, Boolean> olderVotes) {
        if (likes.contains(id)) return Boolean.TRUE;
        if (dislikes.contains(id) || gone.contains(id)) return Boolean.FALSE;
        return olderVotes.get(id);
    }

    private static int orZero(Integer n) {
        return n != null ? n : 0;
    }

    /** "a pothole report", "an accident report". */
    static String withArticle(String what) {
        return (what.matches("(?i)^[aeiou].*") ? "an " : "a ") + what;
    }

    /** "pothole report", "traffic jam report"... for messages people read. */
    public static String reportWhat(RoadReport report) {
        ReportType type = report != null && report.getType() != null ? report.getType() : ReportType.OTHER;
        return switch (type) {
            case ACCIDENT -> "accident report";
            case POTHOLE -> "pothole report";
            case TRAFFIC_JAM -> "traffic jam report";
            case ROAD_HAZARD -> "road hazard report";
            case EMERGENCY -> "emergency report";
            default -> "report";
        };
    }

    private static String people(Integer n) {
        int count = n != null ? n : 0;
        return count == 1 ? "1 person" : count + " people";
    }

    /** Re-reads the user first so points are added to their latest total. */
    private void grant(Long userId, int points, String reason, RoadReport report) {
        userRepository.findById(userId).ifPresent(u -> rewardService.grant(u, points, reason, report));
    }

    private Set<Long> feedbackBy(User viewer, VoteTarget target, FeedbackKind kind) {
        Set<Long> ids = new HashSet<>();
        if (viewer == null) return ids;
        for (ReportFeedback f : feedbackRepository.findByUserIdAndTargetTypeAndKind(viewer.getId(), target, kind)) {
            ids.add(f.getTargetId());
        }
        return ids;
    }

    private Map<Long, Boolean> votesBy(User viewer, VoteTarget target) {
        Map<Long, Boolean> map = new HashMap<>();
        if (viewer == null) return map;
        for (CommunityVote v : voteRepository.findByVoterIdAndTargetType(viewer.getId(), target)) {
            map.put(v.getTargetId(), v.getAgree());
        }
        return map;
    }

    /** "ap 16 ab-1234" -> "AP 16 AB 1234"; empty -> null. */
    private static String normalizePlate(String raw) {
        if (raw == null) return null;
        String plate = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (plate.isEmpty()) return null;
        if (plate.replace(" ", "").length() < 4) {
            throw ApiException.badRequest("Enter the full number plate, e.g. AP 16 AB 1234");
        }
        return plate.length() > 20 ? plate.substring(0, 20) : plate;
    }
}
