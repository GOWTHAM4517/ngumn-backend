# The logic (rules and algorithms)

The brain. Risk score, alerts, crash/SOS, complaints, escalation, Buy & Sell - all the rules are here.

| File | What it is |
|---|---|
| `AdminControlService.java` | The admin's controls over people |
| `AlertService.java` | Creates alerts and sends them live to the right people. |
| `AnalyticsService.java` | Admin dashboard > Analytics |
| `AuthService.java` | Sign up, login, and checking the login token on every request. |
| `CarGuardService.java` | Car guard: watching a car without a phone in it. |
| `CommentService.java` | Comments on hazard reports and rule-breaker reports - "Still there at 6 pm", "Cleared now" - shown under the report like comments under a post. |
| `CommunityService.java` | Community trust - road users check each other's reports with likes and dislikes, no admin needed. |
| `ComplaintFilingService.java` | Files complaints - and requests for help - with the real authorities, through their official channels (AuthorityChannel), not only with the department teams inside Raksio |
| `ContactService.java` | Emergency contacts (at least 3, at most 5) and the person's own phone number, plus asking to become a responder. |
| `DemoSimulatorService.java` | DEMO MODE --------- Simulates multiple moving vehicles, hazards and an emergency-vehicle scenario entirely in software, so the full Raksio flow can be shown without... |
| `EmergencyService.java` | "Green Corridor" priority-route module. |
| `EscalationService.java` | Every minute: raises help requests nobody has accepted and complaints that are missing their deadline (IncidentService.escalateDue), and closes crash / SOS alerts... |
| `HazardAheadService.java` | People hear about a hazard when they're about to reach it - not about everything reported around them. |
| `HotspotService.java` | Accident black spots |
| `IncidentService.java` | Help requests (see Incident) |
| `MarketArea.java` | Where Buy & Sell works |
| `MarketOfferService.java` | Offers in Buy & Sell - how showrooms, dealers and owners actually agree a deal |
| `MarketRoadService.java` | "Looking for" and "for sale near you" - Buy & Sell on the road. |
| `MarketService.java` | Buy & Sell: vehicles for sale and their test drives, for every kind of seller - a vehicle's owner, someone selling it for family or a friend, a used-vehicle dealer... |
| `NearbyDangerService.java` | Tells the people AROUND a rule-breaker, not just the offender - the moment it happens, so nobody has to open the app to find out |
| `NotificationSettingsService.java` | Each person's choice of what to be told about in the area around them (see NotificationPrefs for how the warning services use it). |
| `ReportFeedbackService.java` | One-tap feedback on reports, and taking reports down once they're over. |
| `ResponderService.java` | Police, ambulance and department accounts. |
| `RewardService.java` | Reward points: adds or removes points. |
| `RiskEngineService.java` | Prototype risk-analysis engine. |
| `RoadReportService.java` | Hazard reports. Reports are verified by the community (people nearby answer "is this true?" - see CommunityService); the admin status change below is kept as a... |
| `RuleMonitorService.java` | Automatic traffic-rule monitoring for every vehicle that shares its location. |
| `SosService.java` | Crash alerts and SOS. |
| `TrackerFormats.java` | What GPS trackers send to /api/tracker, turned into a CarGuardService.Fix. |
| `TrafficRuleService.java` | Traffic rules and violations. |
| `VehicleService.java` | Vehicles - or rather, people on the move |

Lost? Open `START-HERE - where is what.md` in the main project folder.
