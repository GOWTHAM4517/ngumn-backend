# Server tests

Automatic checks of the logic.

| File | What it is |
|---|---|
| `CarGuardTest.java` | Car guard: trackers, speed and alarms, test drives (through Buy & Sell), stolen cars (police, people nearby, sightings, plate watch, found), silence, handing a car to... |
| `ComplaintFilingTest.java` | Complaints filed with the real authorities (ComplaintFilingService) |
| `ErrorResponsesTest.java` | A mistake in a request is a 4xx with a plain message; only a real fault is a 500, without internals. |
| `MarketDealsTest.java` | Buy & Sell in the city |
| `MarketTest.java` | Buy & Sell: listings for owners, dealers and showrooms, the stolen-plate check, finding vehicles, the price guide, test drives from request to ratings, reminders,... |
| `NgumnBackendApplicationTests.java` | Automatic tests. |
| `SafetyFlowsTest.java` | Safe Walk, live location sharing, crash / SOS, units on duty, backup, the ambulance's hospital, black spots and analytics - the real service classes against in-memory... |

Folders inside: `support/`

Lost? Open `START-HERE - where is what.md` in the main project folder.
