# CampusIQ — Smart Campus Attendance ERP (enhanced prototype)

Responsive web UI + Spring Boot REST API + H2/MySQL schema for multi-course college attendance. Includes course/section-aware timetable, classroom master, session tokens, attendance records, audit logs, password hashing, email-based password reset, basic per-IP request throttling, and configurable Tomcat connection/thread limits.

## Important status (read before official use)
This is an **enhanced development prototype**, not yet a production-secure biometric attendance system. Camera preview is not face recognition. The attendance endpoint currently validates student ID, section, session token and duplicate records, but the real face-matching and liveness/anti-spoofing engine is not connected. Also, login currently verifies credentials but does not yet issue a production access token or enforce authentication/roles across all ERP routes. Do not use this version for official attendance or expose it publicly until those security items are completed and tested.

## Requirements
- JDK 21+ (JDK 25 may work, but JDK 21 is the project's configured target)
- Maven 3.9+
- Default mode uses local H2; no separate database service required
- Optional MySQL 8+ and SMTP account for password reset

## Run on Windows
1. Extract the ZIP.
2. Open PowerShell in the folder containing `pom.xml`. Verify with `dir pom.xml`.
3. Run `java -version` and `mvn -version`.
4. Start the app: `mvn spring-boot:run`
5. Open `http://localhost:8081` for the dashboard or `http://localhost:8081/login.html` for login.

If PowerShell says there is no POM, you are in the wrong folder. Use `cd` into the inner `Smart-Campus-Attendance-ERP` folder where `pom.xml` exists. The default port is 8081. If port 8081 is occupied, stop the other app or set `$env:SERVER_PORT=8082` (then open port 8082).

## Open from your phone on the same Wi-Fi
1. Start the app on the PC.
2. Run `ipconfig` on Windows and note the PC's IPv4 address for the active Wi-Fi adapter (for example `192.168.1.20`).
3. Allow Java/Maven through Windows Defender Firewall for **Private networks only** or add an inbound TCP rule for port 8081.
4. On the phone connected to the same Wi-Fi, open `http://PC_IPV4_ADDRESS:8081`. Do not use `localhost` on the phone: it means the phone itself.
5. Public Wi-Fi/client isolation may block device-to-device connections. Do not port-forward this prototype to the public internet.

Camera APIs on phones generally require HTTPS or localhost. Plain `http://192.168.x.x:8081` may not allow camera access in mobile browsers; use HTTPS for a real deployment.

## SMTP password reset
SMTP is intentionally disabled until configured. Never put real credentials in Git or share them in screenshots. For Gmail, enable 2-Step Verification and create a Google App Password; use that app password, not your normal Gmail password. Some college SMTP servers use different host/port/security settings.

To load SMTP settings automatically on Windows, copy `.env.example` to `.env`, replace the example values with your own settings, and restart the application. The `.env` file is ignored by Git. For Gmail, remove spaces from the App Password and use the authenticated Gmail address for `SMTP_FROM` unless that account has permission to send as another address.

In PowerShell, set these variables in the same terminal before starting the app (example only):

```powershell
$env:SMTP_HOST="smtp.gmail.com"
$env:SMTP_PORT="587"
$env:SMTP_USERNAME="your-sender@gmail.com"
$env:SMTP_PASSWORD="your-16-character-app-password"
$env:SMTP_FROM="your-sender@gmail.com"
mvn spring-boot:run
```

Pages and flow:
- `http://localhost:8081/login.html` — email/password login
- `http://localhost:8081/forgot-password.html` — request reset email
- `http://localhost:8081/reset-password.html` — paste the one-time token from email and set a new password

Reset tokens are random, stored as SHA-256 hashes, expire after 15 minutes, and are invalidated after use. Passwords are hashed with BCrypt. Passwords must be 10–72 characters and contain uppercase, lowercase, a number, and a symbol. Basic in-memory rate limits apply per IP and reset when the app restarts; production should use a shared store (e.g. Redis) behind a trusted proxy.

**First-time student account setup:** use the Forgot Password flow for a pre-created active student email. The same emailed token can be used to set the first password or reset an existing password. Seed/demo emails are example addresses and will not receive mail; add a real student email through the admin UI before testing SMTP.

## MySQL mode
1. Ensure MySQL is running and the database account has permission to create/use `smart_campus`.
2. In PowerShell: set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` from `.env.example`.
3. Start with `mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"`.
4. The schema file is `src/main/resources/schema-mysql.sql`.

## API overview
- `GET /api/health` — health check
- `GET /api/dashboard` — dashboard counters
- `GET|POST /api/students` — list/create students
- `GET|POST /api/classrooms` — list/create rooms
- `GET|POST /api/timetable` — timetable
- `POST /api/sessions/start` — start session and issue temporary token
- `GET /api/sessions` — list sessions
- `POST /api/sessions/{id}/close` — close session
- `POST /api/attendance/mark` — prototype attendance validation (not biometric-ready)
- `GET /api/attendance` — attendance register
- `GET /api/audit` — recent audit events
- `POST /api/auth/login` — check email/password
- `POST /api/auth/forgot-password` — email a reset token
- `POST /api/auth/reset-password` — consume token and update password

## Configurable connection limits
Defaults: max connections 200, accept queue 50, max request threads 100, min spare threads 10. Override with `SERVER_MAX_CONNECTIONS`, `SERVER_ACCEPT_COUNT`, `SERVER_MAX_THREADS`, `SERVER_MIN_THREADS`. These are server resource limits, not a guarantee that every client device can connect; Windows Firewall, Wi-Fi isolation, IP address, port availability, and browser camera security can still prevent access.

## Before production
- Add Spring Security with role-based route protection and secure access/refresh tokens; ensure every admin/teacher/student endpoint is authorized.
- Integrate a real face verification model and tested liveness/anti-spoofing service; never trust a client-sent `FACE_VERIFIED` flag.
- Add session-bound, signed QR challenges and robust replay prevention.
- Use HTTPS, secure cookies/token storage, CSRF protections as applicable, shared rate-limit storage, monitoring, backups and secret management.
- Add consent, retention/deletion controls, restricted biometric access, encrypted face templates and a manual appeal route. GPS cannot reliably distinguish adjacent indoor classrooms.
- Run automated tests, dependency scans, concurrency tests and real-device tests before college deployment.


## UI fix (October 2026)
- Fixed a JavaScript syntax error in CSV export (`join('\n')`) that prevented the main app script from loading; navigation, modal, search and action handlers were therefore not attaching.
- Added a visible Sign in / Sign out control to the dashboard. Successful student login now returns to the dashboard; sign out clears the browser-side student login state.
- The current `/api/auth/login` endpoint authenticates **student** accounts only. This is not yet secure administrator authentication, and the existing admin APIs are not protected by a server-side login session. Do not use this prototype for real campus records until role-based server-side authentication and API authorization are implemented.
