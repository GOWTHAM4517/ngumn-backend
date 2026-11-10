# Small helpers

Distance between GPS points (Haversine), report lifetimes, vehicle names in plain words.

| File | What it is |
|---|---|
| `GeoUtil.java` | Geometry helpers used for nearby-vehicle detection, speed-limit lookups and the traffic-rule monitor. |
| `LiveRecipients.java` | Who to tell about something happening on the road |
| `NotificationPrefs.java` | What each person wants to hear about in the area around them - set in the app under Settings > Notifications. |
| `ReportKind.java` | What a hazard report is about, in more detail than its ReportType - the enum only has six values, so several Quick Alerts share one ("Animal on road" and "Road work /... |
| `ReportLifetime.java` | How long a hazard report stays on the road before it clears by itself - see ReportKind for the list. |
| `VehicleLabels.java` | Plain-language names for vehicles in messages people read or hear - "a car (AP 16 BX 2231)", "a bike", "a person walking" - never the internal vehicle code (like... |

Lost? Open `START-HERE - where is what.md` in the main project folder.
