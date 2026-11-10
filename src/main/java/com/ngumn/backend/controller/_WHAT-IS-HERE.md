# API doors (what the app can call)

Every address the app or website calls, like /api/incidents. A controller checks the login and passes the work to a service.

| File | What it is |
|---|---|
| `AdminControlController.java` | Admin dashboard - see and control everything (admin accounts only) |
| `AdminController.java` | Backs the admin dashboard's summary tiles |
| `AlertController.java` | API addresses for Alert. |
| `AuthController.java` | API addresses for Auth. |
| `AuthorityController.java` | Official complaint channels (ComplaintFilingService) |
| `CarGuardController.java` | Car guard (see CarGuardService). |
| `CommentController.java` | Deleting a comment. Comments are listed and added under their report |
| `CommunityController.java` | Trust scores from community verification (see CommunityService). |
| `ComplaintController.java` | "Report a rule-breaker" - complaints about other drivers, trusted or taken down by people nearby liking or disliking them rather than by an admin (see CommunityService). |
| `DemoController.java` | Starts/stops the simulated-traffic demo (used for showing the app working without real hardware or several physical devices). |
| `EmergencyController.java` | API addresses for Emergency. |
| `HealthController.java` | A plain, unauthenticated "is the server up" endpoint. |
| `HotspotController.java` | GET /api/hotspots?lat=&lng=&radiusMeters= - accident black spots near a point (see HotspotService). |
| `IncidentController.java` | Help requests (see IncidentService). |
| `MarketController.java` | Buy & Sell (see MarketService). |
| `NotificationController.java` | What you're told about in the area around you - the app's Settings > Notifications. |
| `ProfileController.java` | The signed-in person's own things (see ContactService) |
| `ResponderAdminController.java` | Admin dashboard > Responders |
| `RewardController.java` | API addresses for Reward. |
| `RoadReportController.java` | API addresses for RoadReport. |
| `RsuController.java` | Simulated Roadside Unit (RSU) nodes. |
| `RuleZoneController.java` | Place-based traffic rules |
| `SosController.java` | Crash alerts and SOS (see SosService). |
| `VehicleController.java` | API addresses for Vehicle. |
| `ViolationController.java` | Traffic violations - a driver's own record (/mine), reports from Drive Guard on the phone (POST), and the network-wide list for admins. |

Lost? Open `START-HERE - where is what.md` in the main project folder.
