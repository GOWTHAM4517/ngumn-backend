package com.ngumn.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Every minute: raises help requests nobody has accepted and complaints that
 * are missing their deadline (IncidentService.escalateDue), and closes crash /
 * SOS alerts left open for a day (SosService.closeStale).
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final IncidentService incidentService;
    private final SosService sosService;

    public EscalationService(IncidentService incidentService, SosService sosService) {
        this.incidentService = incidentService;
        this.sosService = sosService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        try {
            int n = incidentService.escalateDue();
            if (n > 0) log.info("Escalated {} request(s)", n);
        } catch (RuntimeException e) {
            log.warn("Escalation check failed: {}", e.toString());
        }
        try {
            sosService.closeStale();
        } catch (RuntimeException e) {
            log.warn("Closing old SOS alerts failed: {}", e.toString());
        }
    }
}
