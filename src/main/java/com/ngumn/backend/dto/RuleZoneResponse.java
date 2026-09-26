package com.ngumn.backend.dto;

import com.ngumn.backend.entity.RoadSpeedLimit;
import com.ngumn.backend.entity.TrafficRuleZone;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * One rule zone as the app sees it. Speed-limit zones (RoadSpeedLimit)
 * and one-way / no-entry zones (TrafficRuleZone) come back in one list,
 * told apart by "kind": SPEED_LIMIT, ONE_WAY or NO_ENTRY. Ids are unique
 * per kind, so clients key zones by kind + id.
 */
@Data
@AllArgsConstructor
public class RuleZoneResponse {
    private Long id;
    private String kind;
    private String name;
    private Double latitude;
    private Double longitude;
    private Double endLatitude;
    private Double endLongitude;
    private Double radiusMeters;
    private Integer speedLimitKmh;
    private List<String> vehicleTypes;

    public static RuleZoneResponse fromSpeedLimit(RoadSpeedLimit s) {
        return new RuleZoneResponse(
                s.getId(), "SPEED_LIMIT", s.getRoadName(),
                s.getLatitude(), s.getLongitude(), null, null,
                s.getRadiusMeters(), s.getSpeedLimitKmh(), new ArrayList<>()
        );
    }

    public static RuleZoneResponse fromZone(TrafficRuleZone z) {
        List<String> types = new ArrayList<>();
        if (z.getVehicleTypes() != null) {
            for (String part : z.getVehicleTypes().split(",")) {
                if (!part.isBlank()) types.add(part.trim());
            }
        }
        return new RuleZoneResponse(
                z.getId(), z.getZoneType().name(), z.getName(),
                z.getLatitude(), z.getLongitude(), z.getEndLatitude(), z.getEndLongitude(),
                z.getRadiusMeters(), null, types
        );
    }
}
