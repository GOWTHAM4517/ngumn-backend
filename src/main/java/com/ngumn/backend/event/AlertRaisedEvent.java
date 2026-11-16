package com.ngumn.backend.event;

import com.ngumn.backend.dto.AlertResponse;

/**
 * Published whenever a new vehicle alert is raised, for any listener that
 * wants every alert without depending on AlertService directly.
 */
public class AlertRaisedEvent {

    private final String vehicleCode;
    private final AlertResponse alert;

    public AlertRaisedEvent(String vehicleCode, AlertResponse alert) {
        this.vehicleCode = vehicleCode;
        this.alert = alert;
    }

    public String getVehicleCode() {
        return vehicleCode;
    }

    public AlertResponse getAlert() {
        return alert;
    }
}
