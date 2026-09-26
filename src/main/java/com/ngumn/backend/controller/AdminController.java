package com.ngumn.backend.controller;

import com.ngumn.backend.repository.*;
import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Backs the admin dashboard's summary tiles: live vehicle count, alert
 * count, pending reports, active emergencies, traffic violations in the
 * last 24 hours, rule-breaker reports awaiting community votes, and
 * connected clients.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final VehicleRepository vehicleRepository;
    private final AlertRepository alertRepository;
    private final RoadReportRepository roadReportRepository;
    private final EmergencyEventRepository emergencyEventRepository;
    private final UserRepository userRepository;
    private final TrafficViolationRepository trafficViolationRepository;
    private final ViolationComplaintRepository violationComplaintRepository;
    private final NgumnWebSocketHandler webSocketHandler;
    private final AuthService authService;

    public AdminController(VehicleRepository vehicleRepository, AlertRepository alertRepository,
                            RoadReportRepository roadReportRepository, EmergencyEventRepository emergencyEventRepository,
                            UserRepository userRepository, TrafficViolationRepository trafficViolationRepository,
                            ViolationComplaintRepository violationComplaintRepository,
                            NgumnWebSocketHandler webSocketHandler, AuthService authService) {
        this.vehicleRepository = vehicleRepository;
        this.alertRepository = alertRepository;
        this.roadReportRepository = roadReportRepository;
        this.emergencyEventRepository = emergencyEventRepository;
        this.userRepository = userRepository;
        this.trafficViolationRepository = trafficViolationRepository;
        this.violationComplaintRepository = violationComplaintRepository;
        this.webSocketHandler = webSocketHandler;
        this.authService = authService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Only admins can view the admin summary");
        }
        return ResponseEntity.ok(Map.of(
                "totalUsers", userRepository.count(),
                "totalVehicles", vehicleRepository.count(),
                "totalAlerts", alertRepository.count(),
                "pendingReports", roadReportRepository.findByStatus(ReportStatus.PENDING).size(),
                "activeEmergencies", emergencyEventRepository.findByStatus(com.ngumn.backend.entity.EmergencyStatus.ACTIVE).size(),
                "violations24h", trafficViolationRepository.countByCreatedAtAfter(LocalDateTime.now().minusHours(24)),
                "pendingComplaints", violationComplaintRepository.countByStatus(ReportStatus.PENDING),
                "connectedLiveClients", webSocketHandler.connectedClients()
        ));
    }
}
