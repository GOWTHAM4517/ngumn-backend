package com.ngumn.backend.event;

import com.ngumn.backend.dto.AlertResponse;

/**
 * Published whenever a new alert is raised. Decouples AlertService from
 * MqttService (which itself depends on VehicleService -> RiskEngineService
 * -> AlertService) to avoid a circular bean dependency - MqttService just
 * listens for this event instead of being called directly.
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
