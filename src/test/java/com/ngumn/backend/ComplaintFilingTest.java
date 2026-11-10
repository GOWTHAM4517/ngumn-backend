package com.ngumn.backend;

import com.ngumn.backend.dto.AuthorityChannelRequest;
import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.AuthorityForwarder;
import com.ngumn.backend.notify.MailGateway;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.service.*;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complaints filed with the real authorities (ComplaintFilingService): the
 * official channels for each kind and place, the email to the department with
 * its reply link, the department answering (taken up / fixed / not ours) and
 * the person being told, calls / WhatsApp / portals opened from the app with
 * the official number they got, and the reminder past the deadline:
 *
 *   ./mvnw -Dtest='ComplaintFilingTest' test
 */
class ComplaintFilingTest {

    static final double LAT = 16.5062, LNG = 80.6480; // Benz Circle, Vijayawada

    /** Records every email instead of sending it. */
    static class FakeMail extends MailGateway {
        boolean on = true;
        final List<Mail> sent = new ArrayList<>();

        @Override
        public boolean configured() {
            return on;
        }

        @Override
        public NotifyGateway.Result send(Mail m) {
            if (!on) return NotifyGateway.Result.notSetUp("off");
            sent.add(m);
            return new NotifyGateway.Result(NotifyGateway.Status.SENT, "msg-" + sent.size());
        }
    }

    InMemoryRepositories db;
    UserRepository users;
    IncidentService incidents;
    ComplaintFilingService filing;
    FakeMail mail;
    User citizen, other, admin;

    @BeforeEach
    void setUp() {
        db = new InMemoryRepositories();
        users = db.of(UserRepository.class);
        VehicleRepository vehicles = db.of(VehicleRepository.class);
        NgumnWebSocketHandler ws = new NgumnWebSocketHandler();
        AlertService alerts = new AlertService(db.of(AlertRepository.class), ws, e -> { });
        EmergencyService corridor = new EmergencyService(db.of(EmergencyEventRepository.class), vehicles, alerts, null, ws);
        AuthorityForwarder forwarder = new AuthorityForwarder(new StandardEnvironment(), new NotifyGateway());
        incidents = new IncidentService(db.of(IncidentRepository.class), users, alerts, ws, vehicles, corridor, forwarder, e -> { });
        mail = new FakeMail();
        filing = new ComplaintFilingService(db.of(AuthorityChannelRepository.class), db.of(ComplaintFilingRepository.class),
                db.of(IncidentRepository.class), incidents, mail, ws, Runnable::run);
        filing.seedDefaults();
        citizen = users.save(User.builder().name("Priya Reddy").email("priya@x.test").phone("+919848022222").role(Role.DRIVER).build());
        other = users.save(User.builder().name("Kiran").email("kiran@x.test").role(Role.DRIVER).build());
        admin = users.save(User.builder().name("Admin").email("admin@x").role(Role.ADMIN).build());
    }

    AuthorityChannelRequest vmc() {
        AuthorityChannelRequest r = new AuthorityChannelRequest();
        r.setName("Vijayawada Municipal Corporation");
        r.setTeam("MUNICIPAL");
        r.setLevel("CITY");
        r.setEmail("grievances@vmc.example.gov.in");
        r.setPhone("0866 242 0000");
        r.setCenterLatitude(LAT);
        r.setCenterLongitude(LNG);
        r.setRadiusKm(25.0);
        r.setNote("Roads, sanitation, drains, water supply");
        return r;
    }

    IncidentResponse complain(User who, IncidentType type, boolean share, double lat) {
        IncidentRequest r = new IncidentRequest();
        r.setType(type);
        r.setLatitude(lat);
        r.setLongitude(LNG);
        r.setDescription("Big pothole near the bus stop, left lane");
        r.setShareContact(share);
        return incidents.create(who, r).get(0);
    }

    List<String> names(IncidentType type, double lat) {
        return filing.channelsFor(type, lat, LNG).stream().map(AuthorityChannel::getName).toList();
    }

    Incident incident(IncidentResponse r) {
        return db.rows(Incident.class).stream().filter(i -> i.getId().equals(r.getId())).findFirst().orElseThrow();
    }

    List<Alert> alertsFor(User u) {
        return db.rows(Alert.class).stream().filter(a -> a.getUser() != null && a.getUser().getId().equals(u.getId())).toList();
    }

    String tokenIn(MailGateway.Mail m) {
        int i = m.text().indexOf("complaint-reply.html?t=");
        assertTrue(i > 0, m.text());
        return m.text().substring(i + "complaint-reply.html?t=".length()).split("\\s")[0];
    }

    @Test
    void eachKindGetsItsOwnOfficialChannelsCityFirst() {
        assertEquals(8, db.rows(AuthorityChannel.class).size(), "the state and national ones are there from the start");
        filing.seedDefaults();
        assertEquals(8, db.rows(AuthorityChannel.class).size(), "only once");
        assertEquals(List.of("AP grievances - PGRS (Meekosam)", "AP government on WhatsApp (Mana Mitra)", "CPGRAMS - central government grievances",
                "National highways - NHAI 1033"), names(IncidentType.POTHOLE, LAT));
        assertEquals("Electricity complaints - 1912", names(IncidentType.POWER_CUT, LAT).get(0));
        assertFalse(names(IncidentType.GARBAGE, LAT).contains("National highways - NHAI 1033"));
        assertEquals("Police - 112", names(IncidentType.ACCIDENT, LAT).get(0), "help needed now: the emergency number first");
        assertTrue(names(IncidentType.ILLEGAL_PARKING, LAT).contains("Police - 112"));

        filing.adminSave(null, vmc());
        assertEquals("Vijayawada Municipal Corporation", names(IncidentType.POTHOLE, LAT).get(0), "the city's own first");
        assertFalse(names(IncidentType.POTHOLE, 16.2430).contains("Vijayawada Municipal Corporation"), "Tenali (29 km) isn't the VMC's");
        assertFalse(names(IncidentType.POWER_CUT, LAT).contains("Vijayawada Municipal Corporation"), "power cuts aren't the municipality's");

        Map<String, Object> v = filing.channelsView(IncidentType.POTHOLE, LAT, LNG).get(0);
        assertEquals(true, v.get("emailedAutomatically"));
        assertEquals("grievances@vmc.example.gov.in", v.get("email"));
    }

    @Test
    void aComplaintIsEmailedToTheDepartmentWithAReplyLinkAndOnlyWhatThePersonAgreedToShare() {
        filing.adminSave(null, vmc());
        IncidentResponse c = complain(citizen, IncidentType.POTHOLE, true, LAT);
        List<ComplaintFiling> made = filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example");
        assertEquals(1, made.size(), "only the channel with an email address");
        ComplaintFiling f = db.rows(ComplaintFiling.class).get(0);
        assertEquals("SENT", f.getStatus());
        assertEquals("to grievances@vmc.example.gov.in", f.getDetail());
        assertEquals("EMAIL", f.getMethod());
        MailGateway.Mail m = mail.sent.get(0);
        assertEquals("grievances@vmc.example.gov.in", m.to());
        assertEquals("Complaint: Pothole or damaged road [Raksio #" + c.getId() + "]", m.subject());
        assertTrue(m.text().contains("Details: \"Big pothole near the bus stop, left lane\""));
        assertTrue(m.text().contains("https://maps.google.com/?q=16.506200,80.648000"));
        assertTrue(m.text().contains("Priya Reddy, +919848022222 (shared with their permission"));
        assertTrue(m.text().contains("https://raksio.example/complaint-reply.html?t=" + f.getToken()));
        assertTrue(m.html().contains("Answer this complaint"));
        assertEquals("priya@x.test", m.replyTo(), "the department can reply straight to them");
        assertEquals(36, f.getToken().length());

        // Sent again (a retry) - not emailed twice. A request for help now isn't emailed at all.
        assertTrue(filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example").isEmpty());
        IncidentResponse help = complain(citizen, IncidentType.BREAKDOWN, true, LAT);
        assertTrue(filing.fileNew(citizen, List.of(help.getId()), "https://raksio.example").isEmpty());
        assertTrue(filing.fileNew(other, List.of(c.getId()), "https://raksio.example").isEmpty(), "only the person's own");

        // Not sharing: no name, no phone, no reply-to.
        IncidentResponse quiet = complain(other, IncidentType.GARBAGE, false, LAT);
        filing.fileNew(other, List.of(quiet.getId()), "https://raksio.example");
        MailGateway.Mail q = mail.sent.get(1);
        assertFalse(q.text().contains("Kiran"));
        assertTrue(q.text().contains("chose not to share their contact details"));
        assertNull(q.replyTo());
        assertNull(filing.replyView(tokenIn(q)).get("citizen"));
        assertEquals("Priya Reddy", filing.replyView(f.getToken()).get("citizen"));
    }

    @Test
    void withoutAnEmailServiceItSaysSoInsteadOfSent() {
        mail.on = false;
        filing.adminSave(null, vmc());
        IncidentResponse c = complain(citizen, IncidentType.POTHOLE, true, LAT);
        filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example");
        ComplaintFiling f = db.rows(ComplaintFiling.class).get(0);
        assertEquals("NOT_SET_UP", f.getStatus());
        assertTrue(mail.sent.isEmpty());
        Map<String, Object> v = filing.view(citizen, c.getId());
        assertEquals(false, v.get("emailReady"));
    }

    @Test
    void theDepartmentAnswersThroughTheLinkAndThePersonIsToldAtOnce() {
        filing.adminSave(null, vmc());
        IncidentResponse c = complain(citizen, IncidentType.POTHOLE, true, LAT);
        filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example");
        String token = tokenIn(mail.sent.get(0));

        Map<String, Object> page = filing.replyView(token);
        assertEquals("Vijayawada Municipal Corporation", page.get("department"));
        assertEquals("Pothole or damaged road", page.get("what"));
        assertNull(page.get("answer"));
        assertThrows(ApiException.class, () -> filing.replyView("nope"));
        assertThrows(ApiException.class, () -> filing.reply(token, "MAYBE", null, null, null));

        filing.reply(token, "ACCEPT", "Ravi, AE Ward 12", "Team visits tomorrow morning", "VMC/2026/4411");
        Incident i = incident(c);
        assertEquals(IncidentStatus.ACCEPTED, i.getStatus());
        assertEquals("Vijayawada Municipal Corporation (Ravi, AE Ward 12)", i.getExternalHandler());
        assertEquals("Vijayawada Municipal Corporation (Ravi, AE Ward 12)", incidents.get(citizen, c.getId(), null, null).getResponderUnit());
        assertTrue(alertsFor(citizen).stream().anyMatch(a -> a.getMessage().equals(
                "Complaint taken up: Vijayawada Municipal Corporation (Ravi, AE Ward 12) has taken up your pothole complaint. \"Team visits tomorrow morning\"")));
        ComplaintFiling f = db.rows(ComplaintFiling.class).get(0);
        assertEquals("ACCEPTED", f.getStatus());
        assertEquals("VMC/2026/4411", f.getOfficialRef());

        filing.reply(token, "RESOLVE", "Ravi, AE Ward 12", "Filled and levelled", null);
        i = incident(c);
        assertEquals(IncidentStatus.RESOLVED, i.getStatus());
        assertEquals(IncidentOutcome.FIXED, i.getOutcome());
        assertTrue(alertsFor(citizen).stream().anyMatch(a -> a.getMessage().startsWith("Help request resolved: Vijayawada Municipal Corporation (Ravi, AE Ward 12) says your pothole complaint is fixed.")));
        assertEquals("RESOLVED", filing.replyView(token).get("answer"));
        ApiException again = assertThrows(ApiException.class, () -> filing.reply(token, "REJECT", null, null, null));
        assertTrue(again.getMessage().contains("already marked it fixed"));
        // Now the person can rate it, as for any fixed complaint.
        com.ngumn.backend.dto.RatingRequest rr = new com.ngumn.backend.dto.RatingRequest();
        rr.setStars(5);
        assertEquals(5, incidents.rate(citizen, c.getId(), rr).getRating());
    }

    @Test
    void notTheirDepartmentAndWithdrawnComplaints() {
        filing.adminSave(null, vmc());
        IncidentResponse c = complain(citizen, IncidentType.ENCROACHMENT, true, LAT);
        filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example");
        String token = tokenIn(mail.sent.get(0));
        filing.reply(token, "REJECT", null, "Town planning handles this", null);
        assertEquals(IncidentStatus.OPEN, incident(c).getStatus(), "Raksio's department team still has it");
        assertTrue(alertsFor(citizen).stream().anyMatch(a -> a.getMessage().contains("says your encroachment complaint isn't theirs to fix")));

        IncidentResponse d = complain(citizen, IncidentType.GARBAGE, true, LAT);
        filing.fileNew(citizen, List.of(d.getId()), "https://raksio.example");
        String t2 = tokenIn(mail.sent.get(1));
        incidents.cancel(citizen, d.getId());
        assertEquals(true, filing.replyView(t2).get("withdrawn"));
        assertThrows(ApiException.class, () -> filing.reply(t2, "ACCEPT", null, null, null));
    }

    @Test
    void callsWhatsAppAndPortalsOpenedFromTheAppAndTheOfficialNumber() {
        IncidentResponse c = complain(citizen, IncidentType.POTHOLE, true, LAT);
        Map<String, Object> v = filing.view(citizen, c.getId());
        assertTrue(((String) v.get("message")).startsWith("Complaint: Pothole or damaged road. Details: Big pothole near the bus stop, left lane. Location: https://maps.google.com/?q=16.506200,80.648000"));
        assertTrue(((String) v.get("message")).endsWith("Raksio complaint no. " + c.getId() + "."));
        List<?> channels = (List<?>) v.get("channels");
        Map<?, ?> pgrs = (Map<?, ?>) channels.get(0);
        assertEquals("1100", pgrs.get("phone"));
        assertEquals("https://meekosam.ap.gov.in", pgrs.get("portalUrl"));
        Long pgrsId = (Long) pgrs.get("id");

        Map<String, Object> call = filing.record(citizen, c.getId(), pgrsId, "call");
        assertEquals("OPENED", call.get("status"));
        assertEquals("called 1100", call.get("detail"));
        assertEquals(call.get("id"), filing.record(citizen, c.getId(), pgrsId, "CALL").get("id"), "the same call again is the same filing");
        assertNotEquals(call.get("id"), filing.record(citizen, c.getId(), pgrsId, "PORTAL").get("id"));
        assertThrows(ApiException.class, () -> filing.record(citizen, c.getId(), pgrsId, "FAX"));
        assertThrows(ApiException.class, () -> filing.record(other, c.getId(), pgrsId, "CALL"), "only the person's own");

        Map<String, Object> withRef = filing.setReference(citizen, (Long) call.get("id"), "  PGRS-2026-123456 ");
        assertEquals("PGRS-2026-123456", withRef.get("officialRef"));
        assertThrows(ApiException.class, () -> filing.setReference(other, (Long) call.get("id"), "x"));
        assertEquals(2, ((List<?>) filing.view(citizen, c.getId()).get("filings")).size());
        assertThrows(ApiException.class, () -> filing.view(other, c.getId()));
        assertNotNull(filing.view(admin, c.getId()));
    }

    @Test
    void oneReminderWhenItsPastItsDeadline() {
        filing.adminSave(null, vmc());
        IncidentResponse c = complain(citizen, IncidentType.DRAINAGE, true, LAT);
        filing.fileNew(citizen, List.of(c.getId()), "https://raksio.example");
        assertEquals(0, filing.remindOverdue("https://raksio.example"), "not due yet");
        incident(c).setDueAt(LocalDateTime.now().minusHours(1));
        assertEquals(1, filing.remindOverdue("https://raksio.example"));
        assertTrue(mail.sent.get(1).subject().startsWith("Reminder - past its deadline: "));
        assertTrue(mail.sent.get(1).text().contains("past the deadline"));
        assertEquals(0, filing.remindOverdue("https://raksio.example"), "only once");
    }

    @Test
    void theAdminOnlyKeepsChannelsThatCanReachSomeone() {
        AuthorityChannelRequest r = new AuthorityChannelRequest();
        r.setName("Somewhere");
        assertThrows(ApiException.class, () -> filing.adminSave(null, r), "no way to reach them");
        r.setEmail("not-an-email");
        assertThrows(ApiException.class, () -> filing.adminSave(null, r));
        r.setEmail("ok@dept.gov.in");
        r.setTypes("POTHOLE,UNICORNS");
        assertThrows(ApiException.class, () -> filing.adminSave(null, r));
        r.setTypes("pothole, garbage");
        r.setPortalUrl("dept.gov.in");
        assertThrows(ApiException.class, () -> filing.adminSave(null, r));
        r.setPortalUrl(null);
        Map<String, Object> saved = filing.adminSave(null, r);
        assertEquals("POTHOLE,GARBAGE", saved.get("types"));
        assertEquals("CITY", saved.get("level"));
        filing.adminDelete((Long) saved.get("id"));
        assertEquals(8, filing.adminList().size());
    }
}
