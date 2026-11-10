# Database tables

One file = one table in the database (users, vehicles, reports, alerts...).

| File | What it is |
|---|---|
| `Alert.java` | Database table: alerts. |
| `AlertType.java` | A fixed list of choices: HIGH_RISK, OVERSPEED, NEARBY_HAZARD, EMERGENCY_VEHICLE, PEDESTRIAN_SAFETY... |
| `AuthToken.java` | A simple opaque bearer token issued at login. |
| `AuthorityChannel.java` | An official way to reach an authority about a complaint or a request for help (ComplaintFilingService) |
| `CarPosition.java` | Database table: car_positions. |
| `CarSighting.java` | Database table: car_sightings. |
| `CommunityVote.java` | Database table: community_votes. |
| `ComplaintFiling.java` | Database table: complaint_filings. |
| `EmergencyContact.java` | Database table: emergency_contacts. |
| `EmergencyEvent.java` | Represents a "Green Corridor" priority-route simulation triggered by an emergency vehicle. |
| `EmergencyStatus.java` | A fixed list of choices: ACTIVE, COMPLETED... |
| `FeedbackKind.java` | One-tap feedback on someone else's report. |
| `GuardedCar.java` | Database table: guarded_cars. |
| `Incident.java` | Database table: incidents. |
| `IncidentOutcome.java` | What the responder did - chosen when they close a help request as RESOLVED. |
| `IncidentStatus.java` | A help request's life |
| `IncidentType.java` | What someone needs help with - or wants fixed - which team gets it, and how urgent it is to start with (see IncidentService.priorityOf for the rest of the score). |
| `ListingPhoto.java` | Database table: listing_photos. |
| `ListingReport.java` | Database table: listing_reports. |
| `ListingSighting.java` | Database table: listing_sightings. |
| `LocationLog.java` | Historical location trail for a vehicle. |
| `MarketListing.java` | Database table: market_listings. |
| `MarketOffer.java` | Database table: market_offers. |
| `MarketWish.java` | Database table: market_wishes. |
| `ReportComment.java` | Database table: report_comments. |
| `ReportFeedback.java` | Database table: report_feedback. |
| `ReportStatus.java` | A fixed list of choices: PENDING, VERIFIED... |
| `ReportType.java` | A fixed list of choices: ACCIDENT, POTHOLE, TRAFFIC_JAM, ROAD_HAZARD, EMERGENCY... |
| `ResponderTeam.java` | Who answers a help request (see Incident). |
| `Reward.java` | Database table: rewards. |
| `RiskAnalysis.java` | Database table: risk_analyses. |
| `RiskLevel.java` | A fixed list of choices: LOW, MEDIUM... |
| `RoadReport.java` | Database table: road_reports. |
| `RoadSpeedLimit.java` | Prototype configurable speed-limit dataset. |
| `Role.java` | A fixed list of choices: DRIVER, PEDESTRIAN, EMERGENCY... |
| `RsuNode.java` | Simulated Roadside Unit (RSU). |
| `RuleZoneType.java` | Place-based traffic rules (besides speed limits, which live in RoadSpeedLimit). |
| `SosDelivery.java` | Database table: sos_deliveries. |
| `SosEvent.java` | Database table: sos_events. |
| `TestDriveBooking.java` | Database table: test_drive_bookings. |
| `TrafficRuleZone.java` | A traffic rule tied to a place, checked by Drive Guard in the app |
| `TrafficViolation.java` | One traffic-rule violation by a vehicle on the network, from one of three sources (see ViolationSource) |
| `TravelMode.java` | How someone is getting around right now. |
| `User.java` | Database table: users. |
| `Vehicle.java` | Database table: vehicles. |
| `VehicleStatus.java` | A fixed list of choices: ACTIVE... |
| `VehicleType.java` | A fixed list of choices: CAR, BIKE, BUS, TRUCK, EMERGENCY... |
| `ViolationComplaint.java` | A road user's report that someone broke a traffic rule (wrong-side driving, jumping a signal, no helmet...). |
| `ViolationSource.java` | How a traffic violation was detected. |
| `ViolationType.java` | Traffic-rule violations Raksio knows about. |
| `VoteTarget.java` | What a community vote is about. |

Lost? Open `START-HERE - where is what.md` in the main project folder.
