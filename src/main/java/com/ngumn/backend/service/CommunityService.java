package com.ngumn.backend.service;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Community verification - road users check each other's reports, no
 * admin needed.
 *
 * When someone reports a hazard or a rule-breaker, people nearby are asked
 * "Is this true?" (in the app: Alerts > Verify, or a prompt when they are
 * stopped). Each person answers once.
 *
 * - CONFIRMATIONS_NEEDED "true" answers (and more true than false) mark it
 *   VERIFIED: the reporter earns REPORTER_POINTS. For a rule-breaker
 *   report about an NGUMN vehicle, its driver is alerted and the violation
 *   goes on their driving record.
 * - DENIALS_TO_REJECT "false" answers (and more false than true) mark it
 *   REJECTED.
 * - Everyone whose answer matched the final outcome earns VOTER_POINTS.
 *
 * Trust: a 0-100 score from how a person's reports turned out and how
 * accurate their answers were. It is shown next to their reports, and it
 * changes how many confirmations their reports need - 2 for highly
 * trusted people, 4 for people with a record of false reports.
 *
 * A report of a speeding, wrong-way or rash driver doesn't wait for the
 * vote: drivers near it are warned straight away (NearbyDangerService),
 * with the warning marked "not verified yet".
 */
@Service
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

    public static final int CONFIRMATIONS_NEEDED = 3;
    public static final int CONFIRMATIONS_NEEDED_TRUSTED = 2;
    public static final int CONFIRMATIONS_NEEDED_LOW_TRUST = 4;
    public static final int DENIALS_TO_REJECT = 3;
    public static final int REPORTER_POINTS = 10;
    public static final int VOTER_POINTS = 2;

    /** Answers sent from further away than this are refused. */
    private static final double MAX_VOTE_DISTANCE_METERS = 15_000;
    /** Spam guard: at most this many rule-breaker reports per person in 10 minutes. */
    private static final int MAX_COMPLAINTS_PER_10_MIN = 5;

    private final RoadReportRepository roadReportRepository;
    private final ViolationComplaintRepository complaintRepository;
    private final CommunityVoteRepository voteRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final RewardService rewardService;
    private final TrafficRuleService trafficRuleService;
    private final NearbyDangerService nearbyDangerService;
    private final NgumnWebSocketHandler webSocketHandler;

    public CommunityService(RoadReportRepository roadReportRepository,
                            ViolationComplaintRepository complaintRepository,
                            CommunityVoteRepository voteRepository,
                            UserRepository userRepository,
                            VehicleRepository vehicleRepository,
                            RewardService rewardService,
                            TrafficRuleService trafficRuleService,
                            NearbyDangerService nearbyDangerService,
                            NgumnWebSocketHandler webSocketHandler) {
        this.roadReportRepository = roadReportRepository;
        this.complaintRepository = complaintRepository;
        this.voteRepository = voteRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.rewardService = rewardService;
        this.trafficRuleService = trafficRuleService;
        this.nearbyDangerService = nearbyDangerService;
        this.webSocketHandler = webSocketHandler;
    }

    // ------------------------------------------------------------------
    // Hazard reports
    // ------------------------------------------------------------------

    /** Reports as the viewer sees them: vote counts, confirmations needed, reporter trust, their own vote. */
    public List<RoadReportResponse> describeReports(User viewer, List<RoadReport> reports) {
        Map<Long, Boolean> myVotes = votesBy(viewer, VoteTarget.REPORT);
        Map<Long, TrustResponse> trustCache = new HashMap<>();
        List<RoadReportResponse> out = new ArrayList<>();
        for (RoadReport r : reports) {
            TrustResponse trust = r.getReporter() != null
                    ? trustCache.computeIfAbsent(r.getReporter().getId(), this::trustFor)
                    : null;
            out.add(RoadReportResponse.from(r,
                    trust != null ? trust.getTrustScore() : null,
                    trust != null ? trust.getConfirmationsNeeded() : CONFIRMATIONS_NEEDED,
                    myVotes.get(r.getId())));
        }
        return out;
    }

    public RoadReportResponse describeReport(User viewer, RoadReport report) {
        return describeReports(viewer, List.of(report)).get(0);
    }

    /** Synchronized so two answers arriving together can't both close the vote (and pay out twice). */
    public synchronized RoadReportResponse voteOnReport(User voter, Long reportId, VoteRequest request) {
        RoadReport report = roadReportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        checkVote(voter, VoteTarget.REPORT, report.getId(), report.getReporter(), report.getStatus(),
                report.getLatitude(), report.getLongitude(), request, null);

        saveVote(voter, VoteTarget.REPORT, report.getId(), request.getAgree());
        report.setConfirmations(countVotes(VoteTarget.REPORT, report.getId(), true));
        report.setDenials(countVotes(VoteTarget.REPORT, report.getId(), false));
        report = roadReportRepository.save(report);

        Long reporterId = report.getReporter() != null ? report.getReporter().getId() : null;
        ReportStatus outcome = outcome(report.getConfirmations(), report.getDenials(), confirmationsNeeded(reporterId));
        if (outcome != null) {
            report.setStatus(outcome);
            report.setDecidedAt(LocalDateTime.now());
            report = roadReportRepository.save(report);
            boolean verified = outcome == ReportStatus.VERIFIED;
            settleVotes(VoteTarget.REPORT, report.getId(), verified, report);
            if (verified && reporterId != null) {
                grant(reporterId, REPORTER_POINTS,
                        "Report confirmed by " + report.getConfirmations() + " people (#" + report.getId() + ")", report);
            }
        }
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return describeReport(voter, report);
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
            // Speeding / wrong-way / rash driver: warn the drivers around it now.
            nearbyDangerService.warnAboutReport(complaint);
        } catch (RuntimeException e) {
            // Best effort - the report itself is already saved.
            log.warn("Couldn't warn drivers near complaint {}: {}", complaint.getId(), e.getMessage());
        }
        return describeComplaint(reporter, complaint);
    }

    public List<ComplaintResponse> recentComplaints(User viewer) {
        return describeComplaints(viewer, complaintRepository.findTop100ByOrderByCreatedAtDesc());
    }

    public List<ComplaintResponse> myComplaints(User viewer) {
        return describeComplaints(viewer, complaintRepository.findByReporterIdOrderByCreatedAtDesc(viewer.getId()));
    }

    public List<ComplaintResponse> describeComplaints(User viewer, List<ViolationComplaint> complaints) {
        Map<Long, Boolean> myVotes = votesBy(viewer, VoteTarget.COMPLAINT);
        Map<Long, TrustResponse> trustCache = new HashMap<>();
        List<ComplaintResponse> out = new ArrayList<>();
        for (ViolationComplaint c : complaints) {
            TrustResponse trust = c.getReporter() != null
                    ? trustCache.computeIfAbsent(c.getReporter().getId(), this::trustFor)
                    : null;
            out.add(ComplaintResponse.from(c,
                    trust != null ? trust.getTrustScore() : null,
                    trust != null ? trust.getConfirmationsNeeded() : CONFIRMATIONS_NEEDED,
                    myVotes.get(c.getId()),
                    viewer != null ? viewer.getId() : null));
        }
        return out;
    }

    public ComplaintResponse describeComplaint(User viewer, ViolationComplaint complaint) {
        return describeComplaints(viewer, List.of(complaint)).get(0);
    }

    public synchronized ComplaintResponse voteOnComplaint(User voter, Long complaintId, VoteRequest request) {
        ViolationComplaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        Vehicle accused = complaint.getAccusedVehicle();
        Long accusedOwnerId = accused != null && accused.getOwner() != null ? accused.getOwner().getId() : null;
        checkVote(voter, VoteTarget.COMPLAINT, complaint.getId(), complaint.getReporter(), complaint.getStatus(),
                complaint.getLatitude(), complaint.getLongitude(), request, accusedOwnerId);

        saveVote(voter, VoteTarget.COMPLAINT, complaint.getId(), request.getAgree());
        complaint.setConfirmations(countVotes(VoteTarget.COMPLAINT, complaint.getId(), true));
        complaint.setDenials(countVotes(VoteTarget.COMPLAINT, complaint.getId(), false));
        complaint = complaintRepository.save(complaint);

        Long reporterId = complaint.getReporter() != null ? complaint.getReporter().getId() : null;
        ReportStatus outcome = outcome(complaint.getConfirmations(), complaint.getDenials(), confirmationsNeeded(reporterId));
        if (outcome != null) {
            complaint.setStatus(outcome);
            complaint.setDecidedAt(LocalDateTime.now());
            complaint = complaintRepository.save(complaint);
            boolean verified = outcome == ReportStatus.VERIFIED;
            settleVotes(VoteTarget.COMPLAINT, complaint.getId(), verified, null);
            if (verified) {
                if (reporterId != null) {
                    grant(reporterId, REPORTER_POINTS, "Rule-breaker report confirmed by "
                            + complaint.getConfirmations() + " people (#" + complaint.getId() + ")", null);
                }
                if (accused != null && accused.getOwner() != null) {
                    boolean simulatedVehicle = Boolean.TRUE.equals(accused.getIsSimulated());
                    trafficRuleService.record(TrafficViolation.builder()
                            .vehicle(accused)
                            .driver(accused.getOwner())
                            .type(complaint.getType())
                            .source(ViolationSource.COMMUNITY)
                            .latitude(complaint.getLatitude())
                            .longitude(complaint.getLongitude())
                            .complaintId(complaint.getId())
                            .message(complaint.getConfirmations() + " road users confirmed your vehicle was "
                                    + TrafficRuleService.phrase(complaint.getType())
                                    + ". Please follow the rules - it keeps everyone safe.")
                            .simulated(simulatedVehicle)
                            .build(), !simulatedVehicle);
                }
            }
        }
        webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        return describeComplaint(voter, complaint);
    }

    // ------------------------------------------------------------------
    // Trust
    // ------------------------------------------------------------------

    public TrustResponse trustFor(Long userId) {
        long verified = roadReportRepository.countByReporterIdAndStatus(userId, ReportStatus.VERIFIED)
                + complaintRepository.countByReporterIdAndStatus(userId, ReportStatus.VERIFIED);
        long rejected = roadReportRepository.countByReporterIdAndStatus(userId, ReportStatus.REJECTED)
                + complaintRepository.countByReporterIdAndStatus(userId, ReportStatus.REJECTED);
        long votes = voteRepository.countByVoterId(userId);
        long matched = voteRepository.countByVoterIdAndOutcomeMatchedTrue(userId);
        long missed = voteRepository.countByVoterIdAndOutcomeMatchedFalse(userId);

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

        int needed = CONFIRMATIONS_NEEDED;
        if (score >= 80 && verified >= 3) needed = CONFIRMATIONS_NEEDED_TRUSTED;
        else if (score < 40 && rejected >= 2) needed = CONFIRMATIONS_NEEDED_LOW_TRUST;

        return new TrustResponse(userId, score, level, verified, rejected, votes, matched, needed);
    }

    private int confirmationsNeeded(Long reporterId) {
        return reporterId == null ? CONFIRMATIONS_NEEDED : trustFor(reporterId).getConfirmationsNeeded();
    }

    // ------------------------------------------------------------------
    // Demo Mode
    // ------------------------------------------------------------------

    /**
     * Demo Mode only: one simulated person answers one open report or
     * complaint (85% "true"), so community verification can be shown with
     * a single phone. Called by DemoSimulatorService while the demo runs.
     */
    public void demoVote(List<User> demoVoters, Random random) {
        if (demoVoters.isEmpty()) return;
        LocalDateTime since = LocalDateTime.now().minusHours(6);
        List<RoadReport> reports = roadReportRepository.findByStatusAndTimestampAfter(ReportStatus.PENDING, since);
        List<ViolationComplaint> complaints = complaintRepository.findByStatusAndCreatedAtAfter(ReportStatus.PENDING, since);
        int total = reports.size() + complaints.size();
        if (total == 0) return;

        int pick = random.nextInt(total);
        User voter = demoVoters.get(random.nextInt(demoVoters.size()));
        VoteRequest vote = new VoteRequest();
        vote.setAgree(random.nextDouble() < 0.85);
        try {
            if (pick < reports.size()) {
                voteOnReport(voter, reports.get(pick).getId(), vote);
            } else {
                voteOnComplaint(voter, complaints.get(pick - reports.size()).getId(), vote);
            }
        } catch (ApiException ignored) {
            // This demo voter already answered it (or can't) - another one will next tick.
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void checkVote(User voter, VoteTarget target, Long targetId, User reporter, ReportStatus status,
                           Double itemLat, Double itemLng, VoteRequest request, Long accusedOwnerId) {
        if (status != ReportStatus.PENDING) {
            throw ApiException.badRequest(status == ReportStatus.VERIFIED
                    ? "Already verified by the community - thanks for checking!"
                    : "The community already marked this as not true.");
        }
        if (reporter != null && reporter.getId().equals(voter.getId())) {
            throw ApiException.badRequest("You can't vote on your own report");
        }
        if (accusedOwnerId != null && accusedOwnerId.equals(voter.getId())) {
            throw ApiException.badRequest("This report is about your vehicle, so you can't vote on it");
        }
        if (voteRepository.existsByTargetTypeAndTargetIdAndVoterId(target, targetId, voter.getId())) {
            throw ApiException.badRequest("You've already answered this one");
        }
        if (request.getLatitude() != null && request.getLongitude() != null && itemLat != null && itemLng != null) {
            double d = GeoUtil.distanceMeters(request.getLatitude(), request.getLongitude(), itemLat, itemLng);
            if (d > MAX_VOTE_DISTANCE_METERS) {
                throw ApiException.badRequest(String.format(Locale.ROOT,
                        "You're about %.0f km away - only people nearby can confirm this.", d / 1000));
            }
        }
    }

    private void saveVote(User voter, VoteTarget target, Long targetId, Boolean agree) {
        voteRepository.save(CommunityVote.builder()
                .targetType(target)
                .targetId(targetId)
                .voter(voter)
                .agree(Boolean.TRUE.equals(agree))
                .build());
    }

    private int countVotes(VoteTarget target, Long targetId, boolean agree) {
        return (int) voteRepository.countByTargetTypeAndTargetIdAndAgree(target, targetId, agree);
    }

    private static ReportStatus outcome(Integer confirmations, Integer denials, int needed) {
        int yes = confirmations != null ? confirmations : 0;
        int no = denials != null ? denials : 0;
        if (yes >= needed && yes > no) return ReportStatus.VERIFIED;
        if (no >= DENIALS_TO_REJECT && no > yes) return ReportStatus.REJECTED;
        return null;
    }

    /** Records whether each answer matched the outcome, and rewards the ones that did. */
    private void settleVotes(VoteTarget target, Long targetId, boolean verified, RoadReport report) {
        String what = target == VoteTarget.REPORT ? "a hazard report" : "a rule-breaker report";
        for (CommunityVote vote : voteRepository.findByTargetTypeAndTargetId(target, targetId)) {
            boolean matched = Boolean.TRUE.equals(vote.getAgree()) == verified;
            vote.setOutcomeMatched(matched);
            voteRepository.save(vote);
            if (matched && vote.getVoter() != null) {
                grant(vote.getVoter().getId(), VOTER_POINTS, "Helped verify " + what + " (#" + targetId + ")", report);
            }
        }
    }

    /** Re-reads the user first so points are added to their latest total. */
    private void grant(Long userId, int points, String reason, RoadReport report) {
        userRepository.findById(userId).ifPresent(u -> rewardService.grant(u, points, reason, report));
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
