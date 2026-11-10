# Email, SMS and calls out

Sending email (Brevo/Resend), SMS and calls (Twilio), and forwarding to departments.

| File | What it is |
|---|---|
| `AuthorityForwarder.java` | Passes a new help request or complaint straight on to the real authority, outside Raksio, as soon as it's made |
| `MailGateway.java` | Sends real email through an email service's HTTPS API - no extra library |
| `NotifyGateway.java` | Sends real SMS and places real automatic voice calls through Twilio's REST API (plain HTTPS - no extra library). |

Lost? Open `START-HERE - where is what.md` in the main project folder.
