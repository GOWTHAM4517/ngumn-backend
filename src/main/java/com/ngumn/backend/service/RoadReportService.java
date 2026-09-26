package com.ngumn.backend.service;

import com.ngumn.backend.dto.RoadReportRequest;
import com.ngumn.backend.dto.RoadReportResponse;
import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Hazard reports. Reports are verified by the community (people nearby
 * answer "is this true?" - see CommunityService); the admin status change
 * below is kept as a moderation override.
 */
@Service
public class RoadReportService {

    private static final int VERIFIED_REPORT_POINTS = CommunityService.REPORTER_POINTS;

    private final RoadReportRepository roadReportRepository;
    private final RewardService rewardService;
    private final CommunityService communityService;
    private final NgumnWebSocketHandler webSocketHandler;

    public RoadReportService(RoadReportRepository roadReportRepository, RewardService rewardService,
                              CommunityService communityService, NgumnWebSocketHandler webSocketHandler) {
        this.roadReportRepository = roadReportRepository;
        this.rewardService = rewardService;
        this.communityService = communityService;
        this.webSocketHandler = webSocketHandler;
    }

    public RoadReportResponse submit(User reporter, RoadReportRequest request) {
        RoadReport report = RoadReport.builder()
                .reporter(reporter)
                .type(request.getType())
                .description(request.getDescription())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .imageUrl(request.getImageUrl())
                .status(ReportStatus.PENDING)
                .confirmations(0)
                .denials(0)
                .build();
        report = roadReportRepository.save(report);
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(reporter, report);
    }

    public List<RoadReportResponse> recent(User viewer) {
        return communityService.describeReports(viewer, roadReportRepository.findTop100ByOrderByTimestampDesc());
    }

    public List<RoadReportResponse> myReports(User viewer) {
        return communityService.describeReports(viewer,
                roadReportRepository.findByReporterIdOrderByTimestampDesc(viewer.getId()));
    }

    /** Admin moderation override (the community normally decides - see CommunityService). */
    public RoadReportResponse updateStatus(User admin, Long reportId, ReportStatus newStatus) {
        RoadReport report = roadReportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        ReportStatus previous = report.getStatus();
        report.setStatus(newStatus);
        if (newStatus != ReportStatus.PENDING && previous != newStatus) {
            report.setDecidedAt(LocalDateTime.now());
        }
        report = roadReportRepository.save(report);

        if (newStatus == ReportStatus.VERIFIED && previous != ReportStatus.VERIFIED) {
            rewardService.grant(report.getReporter(), VERIFIED_REPORT_POINTS,
                    "Road report verified (#" + report.getId() + ")", report);
        }

        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(admin, report);
    }
}
