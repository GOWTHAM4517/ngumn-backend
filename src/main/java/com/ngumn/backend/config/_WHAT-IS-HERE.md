# Server settings in code

Start-up settings: who may call the server (CORS), the live-message channel, web pages, first admin account.

| File | What it is |
|---|---|
| `CorsConfig.java` | Which websites may call the server. |
| `DataSeeder.java` | Seeds a default admin login plus demo traffic-rule zones (two speed zones, a one-way street and a no-entry area) in the Demo Mode area, so the app is immediately... |
| `WebSocketConfig.java` | Opens the live-message channel (/ws). |
| `WebUiConfig.java` | Spring Boot only auto-serves index.html as a "welcome page" at the site root ("/"). |

Lost? Open `START-HERE - where is what.md` in the main project folder.
