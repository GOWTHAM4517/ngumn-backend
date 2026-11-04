package com.ngumn.backend;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.event.HospitalChosenEvent;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.AuthorityForwarder;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.service.*;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.StandardEnvironment;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safe Walk, live location sharing, crash / SOS, units on duty, backup,
 * the ambulance's hospital, black spots and analytics - the real service
 * classes against in-memory repositories, so no database is needed:
 *
 *   ./mvnw -Dtest='SafetyFlowsTest,ErrorResponsesTest' test
 */
class SafetyFlowsTest {

    InMemoryRepositories db;
    UserRepository users;
    IncidentRepository incidents;
    SosEventRepository sosEvents;
    IncidentService incidentService;
    SosService sos;
    ContactService contacts;
    HotspotService hotspots;
    AnalyticsService analytics;

    User gowtham, mom, admin, police1, police2, ambulance, fire;

    @BeforeEach
    void setUp() {
        db = new InMemoryRepositories();
        users = db.of(UserRepository.class);
        incidents = db.of(IncidentRepository.class);
        sosEvents = db.of(SosEventRepository.class);
        VehicleRepository vehicles = db.of(VehicleRepository.class);
        SosDeliveryRepository deliveries = db.of(SosDeliveryRepository.class);
        EmergencyContactRepository contactRepo = db.of(EmergencyContactRepository.class);

        NgumnWebSocketHandler ws = new NgumnWebSocketHandler();
        SosService[] sosRef = new SosService[1];
        // The hospital update reaches SosService as an event, as in Spring.
        ApplicationEventPublisher events = e -> {
            if (e instanceof HospitalChosenEvent h && sosRef[0] != null) sosRef[0].onHospitalChosen(h);
        };
        AlertService alerts = new AlertService(db.of(AlertRepository.class), ws, events);
        EmergencyService corridor = new EmergencyService(db.of(EmergencyEventRepository.class), vehicles, alerts, null, ws);
        NotifyGateway gateway = new NotifyGateway(); // no Twilio settings: SMS / calls are recorded as not set up
        AuthorityForwarder forwarder = new AuthorityForwarder(new StandardEnvironment(), gateway);
        incidentService = new IncidentService(incidents, users, alerts, ws, vehicles, corridor, forwarder, events);
        sos = new SosService(sosEvents, deliveries, contactRepo, users, vehicles, incidents, incidentService, alerts, gateway);
        sosRef[0] = sos;
        contacts = new ContactService(contactRepo, users);
        hotspots = new HotspotService(incidents, db.of(RoadReportRepository.class));
        analytics = new AnalyticsService(incidents, sosEvents, deliveries, users, incidentService, hotspots);

        gowtham = person("Gowtham Kondeti", "+919848012345", null, null);
        mom = person("Lakshmi", "+919000000001", null, null); // uses Raksio with the number saved as a contact
        admin = users.save(User.builder().name("Admin").email("admin@x").role(Role.ADMIN).build());
        police1 = person("Ramesh", null, ResponderTeam.POLICE, "Traffic Police - Benz Circle");
        police2 = person("Suresh", null, ResponderTeam.POLICE, "Police - Patamata");
        ambulance = person("Anil", null, ResponderTeam.AMBULANCE, "108 Unit 2");
        fire = person("Kiran", null, ResponderTeam.FIRE, "Fire Station - Governorpet");
        for (User u : List.of(police1, ambulance, fire)) at(vehicles, u, 16.512, 80.652);
        at(vehicles, police2, 16.53, 80.62);

        List<ContactInput> list = new ArrayList<>();
        for (String[] c : new String[][]{{"Lakshmi", "9000000001", "Mother"}, {"Srinivas", "9000000002", "Father"}, {"Ravi", "9000000003", "Friend"}}) {
            ContactInput ci = new ContactInput();
            ci.setName(c[0]);
            ci.setPhone(c[1]);
            ci.setRelation(c[2]);
            list.add(ci);
        }
        contacts.save(gowtham, list);
        contacts.setDuty(police1, true);
        contacts.setDuty(ambulance, true);
        contacts.setDuty(fire, true);
    }

    User person(String name, String phone, ResponderTeam team, String unit) {
        return users.save(User.builder().name(name).email(name.toLowerCase().replace(' ', '.') + "@x").role(Role.DRIVER)
                .phone(phone).responderTeam(team).unitName(unit).build());
    }

    static void at(VehicleRepository vehicles, User u, double lat, double lng) {
        vehicles.save(Vehicle.builder().vehicleCode("U" + u.getId()).owner(u).vehicleType(VehicleType.CAR).travelMode(TravelMode.EMERGENCY)
                .currentLatitude(lat).currentLongitude(lng).lastLocationUpdate(LocalDateTime.now().minusMinutes(1)).build());
    }

    List<Alert> alertsFor(User u) {
        return db.rows(Alert.class).stream().filter(a -> a.getUser() == u).toList();
    }

    List<SosDelivery> deliveriesOf(Object sosId) {
        return db.rows(SosDelivery.class).stream().filter(d -> sosId.equals(d.getSosEventId())).toList();
    }

    /** Contacts are told on a background thread - wait for it. */
    static void await(BooleanSupplier done) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue(done.getAsBoolean(), "timed out waiting");
    }

    static SosRequest sosRequest(String kind) {
        SosRequest r = new SosRequest();
        r.setKind(kind);
        r.setLatitude(16.5062);
        r.setLongitude(80.648);
        return r;
    }

    static String tokenOf(Map<String, Object> view) {
        return ((String) view.get("trackUrl")).replaceAll(".*t=", "");
    }

    // ------------------------------------------------------------------ duty

    @Test
    void goingOnDutyIsSavedAndOnlyForUnits() {
        assertTrue(police1.getOnDuty());
        assertNotNull(police1.getDutyChangedAt());
        assertEquals(true, contacts.profile(police1).get("onDuty"));
        assertThrows(ApiException.class, () -> contacts.setDuty(gowtham, true));
    }

    // ------------------------------------------------------------------ Safe Walk

    @Test
    void missedSafeWalkTellsOnlyTheContacts() throws Exception {
        Map<String, Object> w = sos.trigger(gowtham, sosRequest("CHECKIN"), "https://srv");
        await(() -> deliveriesOf(w.get("id")).size() >= 7);
        assertTrue(((List<?>) w.get("requests")).isEmpty(), "no help requests");
        assertTrue(db.rows(Incident.class).isEmpty(), "no police or ambulance");

        List<Alert> told = alertsFor(mom);
        assertEquals(1, told.size());
        assertTrue(told.get(0).getMessage().startsWith("SOS: Gowtham didn't check in from their Safe Walk"), told.get(0).getMessage());
        assertEquals(RiskLevel.MEDIUM, told.get(0).getRiskLevel());
        List<SosDelivery> d = deliveriesOf(w.get("id"));
        assertEquals(3, d.stream().filter(x -> x.getChannel().equals("SMS")).count());
        assertEquals(3, d.stream().filter(x -> x.getChannel().equals("CALL")).count());

        Map<String, Object> page = sos.track(tokenOf(w));
        assertEquals("CHECKIN", page.get("kind"));
        assertEquals("ACTIVE", page.get("status"));
        assertTrue(((List<?>) page.get("responders")).isEmpty());

        sos.cancel(gowtham, (Long) w.get("id"), "https://srv");
        await(() -> alertsFor(mom).size() == 2);
        assertEquals("Safe Walk: Gowtham has checked in and is safe.", alertsFor(mom).get(1).getMessage());
        assertEquals("CANCELLED", sos.track(tokenOf(w)).get("status"));
    }

    // ------------------------------------------------------------------ sharing live location

    @Test
    void sharingLocationIsNotAnEmergency() throws Exception {
        Map<String, Object> s = sos.trigger(gowtham, sosRequest("SHARE"), "https://srv");
        await(() -> alertsFor(mom).size() == 1 && deliveriesOf(s.get("id")).size() >= 4);
        Alert shared = alertsFor(mom).get(0);
        assertTrue(shared.getMessage().startsWith("Location shared: Gowtham is sharing their live location"), shared.getMessage());
        assertEquals(RiskLevel.LOW, shared.getRiskLevel());
        assertTrue(deliveriesOf(s.get("id")).stream().noneMatch(d -> d.getChannel().equals("CALL")), "no automatic calls");
        assertTrue(db.rows(Incident.class).isEmpty(), "no help requests");

        sos.cancel(gowtham, (Long) s.get("id"), "https://srv");
        Thread.sleep(300);
        assertEquals(1, alertsFor(mom).size(), "stopping a share tells nobody");
    }

    @Test
    void aShareEndsAfterAnHourButACrashDoesNot() {
        SosEvent share = sosEvents.save(SosEvent.builder().user(gowtham).kind("SHARE").reason("UNSAFE").latitude(16.5).longitude(80.6).status("ACTIVE").trackToken("s1").build());
        share.setCreatedAt(LocalDateTime.now().minusMinutes(70));
        SosEvent crash = sosEvents.save(SosEvent.builder().user(police2).kind("CRASH").reason("ACCIDENT").latitude(16.5).longitude(80.6).status("ACTIVE").trackToken("c1").build());
        crash.setCreatedAt(LocalDateTime.now().minusMinutes(70));
        sos.closeStale();
        assertEquals("CLOSED", share.getStatus());
        assertEquals("ACTIVE", crash.getStatus());
        assertFalse(sos.track("s1").containsKey("latitude"), "a closed share shows no position");
    }

    // ------------------------------------------------------------------ crash, units, backup, hospital

    @SuppressWarnings("unchecked")
    @Test
    void crashReachesOnDutyUnitsFirstThenBackupAndHospital() throws Exception {
        SosRequest crash = sosRequest("CRASH");
        crash.setSpeedKmh(42);
        Map<String, Object> c = sos.trigger(gowtham, crash, "https://srv");
        List<IncidentResponse> reqs = (List<IncidentResponse>) c.get("requests");
        assertEquals(Set.of("POLICE", "AMBULANCE"), Set.of(reqs.get(0).getTeam(), reqs.get(1).getTeam()));

        assertTrue(alertsFor(police1).stream().anyMatch(a -> a.getMessage().startsWith("New help request") && a.getMessage().matches(".* \\d+(\\.\\d)? (m|km) from you.*")),
                "the on-duty unit hears it, with how far");
        assertTrue(alertsFor(police2).stream().noneMatch(a -> a.getMessage().startsWith("New help request")), "an off-duty unit doesn't while someone is on duty");
        Map<String, Object> again = sos.trigger(gowtham, crash, "https://srv");
        assertEquals(c.get("id"), again.get("id"), "a second alert within minutes is the same one");

        IncidentResponse policeReq = reqs.stream().filter(r -> r.getTeam().equals("POLICE")).findFirst().orElseThrow();
        IncidentResponse ambReq = reqs.stream().filter(r -> r.getTeam().equals("AMBULANCE")).findFirst().orElseThrow();
        incidentService.accept(police1, policeReq.getId(), 16.512, 80.652);
        incidentService.accept(ambulance, ambReq.getId(), 16.512, 80.652);

        // Backup: the police ask the fire service to come too.
        BackupRequest b = new BackupRequest();
        b.setTeam(ResponderTeam.FIRE);
        b.setNote("Car smoking");
        IncidentResponse fireReq = incidentService.backup(police1, policeReq.getId(), b);
        assertEquals("FIRE", fireReq.getTeam());
        assertEquals("BACKUP", fireReq.getSource());
        assertEquals(policeReq.getCaseKey(), fireReq.getCaseKey());
        assertEquals(policeReq.getId(), fireReq.getBackupOfId());
        assertTrue(fireReq.getPriority() >= 60, "backup is urgent");
        assertTrue(alertsFor(fire).stream().anyMatch(a -> a.getMessage().startsWith("New help request")));
        assertEquals(fireReq.getId(), incidentService.backup(police1, policeReq.getId(), b).getId(), "asking again doesn't make another");
        assertThrows(ApiException.class, () -> incidentService.backup(ambulance, policeReq.getId(), b), "only the request's own team");

        // Hospital: only the unit that accepted; everyone on the case and the family are told.
        HospitalRequest h = new HospitalRequest();
        h.setName("Government General Hospital");
        h.setLatitude(16.513);
        h.setLongitude(80.628);
        assertThrows(ApiException.class, () -> incidentService.hospital(police2, ambReq.getId(), h));
        IncidentResponse withHospital = incidentService.hospital(ambulance, ambReq.getId(), h);
        assertEquals("Government General Hospital", withHospital.getHospitalName());
        assertTrue(alertsFor(gowtham).stream().anyMatch(a -> a.getMessage().contains("is taking you to Government General Hospital")));
        assertTrue(alertsFor(police1).stream().anyMatch(a -> a.getMessage().contains("taking the injured person to Government General Hospital")));
        await(() -> alertsFor(mom).stream().anyMatch(a -> a.getMessage().startsWith("SOS update: Gowtham is being taken to Government General Hospital by 108 Unit 2")));
        Map<?, ?> hospital = (Map<?, ?>) sos.track(tokenOf(c)).get("hospital");
        assertEquals("Government General Hospital", hospital.get("name"));
    }

    @Test
    void withNobodyOnDutyTheWholeTeamHears() {
        contacts.setDuty(police1, false);
        IncidentRequest r = new IncidentRequest();
        r.setType(IncidentType.BREAKDOWN);
        r.setLatitude(16.52);
        r.setLongitude(80.63);
        r.setBlocking("YES");
        r.setVehicleKind("CAR");
        incidentService.create(mom, r);
        assertTrue(alertsFor(police1).stream().anyMatch(a -> a.getMessage().startsWith("New help request")));
        assertTrue(alertsFor(police2).stream().anyMatch(a -> a.getMessage().startsWith("New help request")));
    }

    // ------------------------------------------------------------------ black spots, analytics

    @Test
    void blackSpotNeedsTwoSeparateAccidentsAtOnePlace() {
        User priya = person("Priya", null, null, null);
        IncidentRequest one = accident(16.5065, 80.6482);
        Long first = incidentService.create(mom, one).get(0).getId();
        assertEquals(first, incidentService.create(mom, one).get(0).getId(), "the same person asking twice is one request");
        assertTrue(hotspots.near(16.5062, 80.648, 5000).isEmpty(), "one accident isn't a black spot");

        // (A new service: the black-spot list is worked out once and kept for 5 minutes.)
        incidentService.create(priya, accident(16.5068, 80.6482));
        incidentService.create(priya, accident(16.60, 80.70)); // far away - on its own
        HotspotService fresh = new HotspotService(incidents, db.of(RoadReportRepository.class));
        List<Map<String, Object>> spots = fresh.near(16.5062, 80.648, 5000);
        assertEquals(1, spots.size());
        assertEquals(2, ((Number) spots.get(0).get("accidents")).intValue());
        assertEquals(1, fresh.all(10).size(), "the far accident isn't a black spot");
    }

    static IncidentRequest accident(double lat, double lng) {
        IncidentRequest r = new IncidentRequest();
        r.setType(IncidentType.ACCIDENT);
        r.setLatitude(lat);
        r.setLongitude(lng);
        r.setInjured(0);
        return r;
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyticsForAdminsOnly() throws Exception {
        Map<String, Object> share = sos.trigger(gowtham, sosRequest("SHARE"), "https://srv");
        Map<String, Object> walk = sos.trigger(gowtham, sosRequest("CHECKIN"), "https://srv");
        sos.cancel(gowtham, (Long) walk.get("id"), "https://srv");
        Map<String, Object> c = sos.trigger(gowtham, sosRequest("CRASH"), "https://srv");
        IncidentResponse ambReq = ((List<IncidentResponse>) c.get("requests")).stream().filter(r -> r.getTeam().equals("AMBULANCE")).findFirst().orElseThrow();
        incidentService.accept(ambulance, ambReq.getId(), 16.512, 80.652);
        await(() -> deliveriesOf(share.get("id")).size() >= 4);

        Map<String, Object> a = analytics.analytics(admin, 30);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) a.get("teams");
        assertEquals(5, teams.size());
        Map<String, Object> amb = teams.stream().filter(t -> t.get("team").equals("AMBULANCE")).findFirst().orElseThrow();
        assertNotNull(amb.get("avgAcceptMin"));
        assertEquals(1, ((Number) amb.get("onDutyUnits")).intValue());
        Map<String, Object> s = (Map<String, Object>) a.get("sos");
        assertEquals(1, ((Number) s.get("locationShares")).intValue(), "shares counted on their own");
        assertEquals(1, ((Number) s.get("missedCheckIns")).intValue());
        assertEquals(0, ((Number) s.get("falseAlarms")).intValue(), "checking in isn't a false alarm");
        assertEquals(24, ((List<?>) a.get("byHour")).size());
        assertThrows(ApiException.class, () -> analytics.analytics(gowtham, 30));

        Map<String, Object> unit = analytics.units(admin).stream().filter(u -> u.get("team").equals("AMBULANCE")).findFirst().orElseThrow();
        assertEquals(true, unit.get("onDuty"));
        assertNotNull(unit.get("latitude"));
        assertEquals(1, ((Number) unit.get("handling")).intValue());
    }
}
