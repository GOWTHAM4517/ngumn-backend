# Message shapes (JSON in and out)

The exact fields the app sends (…Request) and the server answers (…Response).

| File | What it is |
|---|---|
| `AdminIncidentAction.java` | Admin dashboard: assign / change status / reopen a help request or complaint. |
| `AdminUserUpdate.java` | Admin dashboard > Users |
| `AlertResponse.java` | Fields the server answers with (Alert). |
| `ApiError.java` | The error message shape sent to the app. |
| `AuthResponse.java` | Fields the server answers with (Auth). |
| `AuthorityChannelRequest.java` | The admin adds or changes an official complaint channel (ComplaintFilingService.adminSave). |
| `AuthorityReplyRequest.java` | A department's answer through the reply link in its complaint email. |
| `BackupRequest.java` | A responder at the spot asks another team (or another unit) to come too. |
| `BookingActionRequest.java` | Answering or running a test drive |
| `BookingRequest.java` | A buyer asks for a test drive (meetAt, note, driverLicence) - or a seller starts one for a walk-in customer (name, phone, minutes, radiusM). |
| `BroadcastRequest.java` | Admin dashboard > Broadcast |
| `CarPointRequest.java` | A place: where a stolen car was last seen (report / "I saw it"), or a position sent by the owner's phone standing in for a tracker (a demo). |
| `CarRequest.java` | Adding a car to Car guard, or changing it (PATCH |
| `CarTransferRequest.java` | A showroom hands a sold car (and its tracker) to the buyer's Raksio account |
| `CommentRequest.java` | Fields the app sends (Comment). |
| `CommentResponse.java` | A comment as the person looking sees it |
| `ComplaintRequest.java` | "Report a rule-breaker" from the app. |
| `ComplaintResponse.java` | A rule-breaker complaint as the app sees it. |
| `ContactInput.java` | One emergency contact as the app sends it. |
| `ContactsSaveRequest.java` | The whole list, in calling order (first = called first). |
| `EmergencyEventResponse.java` | Fields the server answers with (EmergencyEvent). |
| `EmergencyStartRequest.java` | Fields the app sends (EmergencyStart). |
| `FilingRequest.java` | The person opened an official channel from the app (CALL, WHATSAPP, SMS, PORTAL), or adds the official number they got. |
| `HospitalRequest.java` | The ambulance's destination hospital (picked from nearby hospitals in the app). |
| `IncidentRequest.java` | "I need help": what, where, and a couple of quick answers (all optional but the first three). |
| `IncidentResponse.java` | A help request as the app shows it. |
| `IncidentUpdateRequest.java` | A responder moving a request on |
| `ListingReportRequest.java` | Reporting a vehicle for sale |
| `ListingRequest.java` | Putting a vehicle up for sale, or changing it (PATCH |
| `LocationUpdateRequest.java` | Fields the app sends (LocationUpdate). |
| `LoginRequest.java` | Fields the app sends (Login). |
| `NearbyVehicleResponse.java` | Fields the server answers with (NearbyVehicle). |
| `NotificationSettings.java` | Settings > Notifications in the app |
| `OfferRequest.java` | A buyer's offer: a price (kind PRICE, price), or their vehicle in exchange (kind EXCHANGE |
| `PasswordChangeRequest.java` | Fields the app sends (PasswordChange). |
| `PhotoRequest.java` | A photo of a vehicle for sale |
| `RatingRequest.java` | How well a resolved request or complaint was handled (the person who asked). |
| `ReactionRequest.java` | Like, dislike or neither |
| `RegisterRequest.java` | Fields the app sends (Register). |
| `ReportStatusUpdateRequest.java` | Fields the app sends (ReportStatusUpdate). |
| `ResponderAccessRequest.java` | "I'm police / ambulance / fire / a department officer" - waits for an admin to approve. |
| `ResponderCreateRequest.java` | Admin dashboard > Responders |
| `RewardResponse.java` | Fields the server answers with (Reward). |
| `RoadReportRequest.java` | Fields the app sends (RoadReport). |
| `RoadReportResponse.java` | A hazard report as the app sees it, including community verification |
| `RuleZoneRequest.java` | Admin request to add a rule zone. |
| `RuleZoneResponse.java` | One rule zone as the app sees it. |
| `SoldRequest.java` | Marking a vehicle sold |
| `SosRequest.java` | A crash Raksio detected, or an SOS the person pressed (see SosService). |
| `TestDriveRequest.java` | A showroom starts a customer's test drive. |
| `TrustResponse.java` | A person's standing in the community |
| `VehicleCreateRequest.java` | Fields the app sends (VehicleCreate). |
| `VehicleResponse.java` | A vehicle as other people see it. |
| `VehicleUpdateRequest.java` | Changes to your own vehicle from the app. |
| `ViolationRequest.java` | Sent by Drive Guard on the driver's phone when it detects a violation. |
| `ViolationResponse.java` | Fields the server answers with (Violation). |
| `VoteRequest.java` | A "true" / "false" answer about a report or complaint. |
| `WishRequest.java` | "Looking for": what to watch for (all optional), and how to be told. |

Lost? Open `START-HERE - where is what.md` in the main project folder.
