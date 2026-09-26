package com.ngumn.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.event.AlertRaisedEvent;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.service.VehicleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * IoT / ESP32 bridge over MQTT.
 *
 * Topics (see README for the full list):
 *   IN  ngumn/vehicle/{vehicleCode}/location  - {"latitude":..,"longitude":..,"speedKmh":..,"directionDegrees":..,"emergencyStatus":false}
 *   OUT ngumn/vehicle/{vehicleCode}/alert     - published whenever a risk alert is raised for that vehicle
 *
 * Disabled by default (ngumn.mqtt.enabled=false) so the backend starts
 * cleanly even with no broker available. Enable it and point
 * ngumn.mqtt.broker-url at a local Mosquitto instance (or any broker)
 * to demo the ESP32 flow - a software ESP32 simulator can publish to
 * the same topics if physical hardware isn't available.
 */
@Component
public class MqttService implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttService.class);
    private static final String LOCATION_TOPIC_FILTER = "ngumn/vehicle/+/location";

    @Value("${ngumn.mqtt.enabled:false}")
    private boolean enabled;

    @Value("${ngumn.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${ngumn.mqtt.client-id:ngumn-backend}")
    private String clientId;

    @Value("${ngumn.mqtt.username:}")
    private String username;

    @Value("${ngumn.mqtt.password:}")
    private String password;

    private final VehicleRepository vehicleRepository;
    private final VehicleService vehicleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MqttClient client;

    public MqttService(VehicleRepository vehicleRepository, VehicleService vehicleService) {
        this.vehicleRepository = vehicleRepository;
        this.vehicleService = vehicleService;
    }

    @PostConstruct
    public void connect() {
        if (!enabled) {
            log.info("MQTT disabled (ngumn.mqtt.enabled=false) - IoT/ESP32 bridge inactive.");
            return;
        }
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            if (!username.isBlank()) {
                options.setUserName(username);
                options.setPassword(password.toCharArray());
            }
            client.setCallback(this);
            client.connect(options);
            client.subscribe(LOCATION_TOPIC_FILTER, 1);
            log.info("Connected to MQTT broker {} and subscribed to {}", brokerUrl, LOCATION_TOPIC_FILTER);
        } catch (MqttException e) {
            log.warn("Could not connect to MQTT broker at {} - IoT bridge will stay offline. ({})", brokerUrl, e.getMessage());
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException ignored) {
        }
    }

    public void publishAlert(String vehicleCode, String jsonPayload) {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            client.publish("ngumn/vehicle/" + vehicleCode + "/alert", new MqttMessage(jsonPayload.getBytes()));
        } catch (MqttException e) {
            log.warn("Failed to publish MQTT alert for {}: {}", vehicleCode, e.getMessage());
        }
    }

    /** Listens for alerts raised anywhere in the app and republishes them
     *  over MQTT - decoupled via a Spring event so this class has no
     *  compile-time dependency back on AlertService (that would create a
     *  circular bean dependency through VehicleService/RiskEngineService). */
    @EventListener
    public void onAlertRaised(AlertRaisedEvent event) {
        if (!enabled || client == null || !client.isConnected()) {
            return;
        }
        try {
            publishAlert(event.getVehicleCode(), objectMapper.writeValueAsString(event.getAlert()));
        } catch (Exception e) {
            log.warn("Failed to publish alert event over MQTT: {}", e.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            // topic shape: ngumn/vehicle/{vehicleCode}/location
            String[] parts = topic.split("/");
            if (parts.length < 4) return;
            String vehicleCode = parts[2];

            JsonNode payload = objectMapper.readTree(message.getPayload());
            Vehicle vehicle = vehicleRepository.findByVehicleCode(vehicleCode).orElse(null);
            if (vehicle == null) {
                log.warn("MQTT location for unknown vehicleCode={}, ignoring", vehicleCode);
                return;
            }

            LocationUpdateRequest update = new LocationUpdateRequest();
            update.setLatitude(payload.path("latitude").asDouble());
            update.setLongitude(payload.path("longitude").asDouble());
            update.setSpeedKmh(payload.path("speedKmh").asDouble(0));
            update.setDirectionDegrees(payload.path("directionDegrees").asDouble(0));

            vehicleService.updateLocation(vehicle, update);
        } catch (Exception e) {
            log.warn("Failed to process MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}
