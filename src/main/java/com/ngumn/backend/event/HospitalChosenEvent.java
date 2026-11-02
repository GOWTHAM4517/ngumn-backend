package com.ngumn.backend.event;

/**
 * An ambulance told Raksio which hospital it is taking someone to (see
 * IncidentService.hospital). Published as an event so SosService can tell
 * that person's emergency contacts without IncidentService depending on
 * SosService (which already depends on IncidentService).
 */
public record HospitalChosenEvent(Long sosEventId, String hospitalName, String unit, Double latitude, Double longitude) {
}
