# DropPoint — 5 minute demo script

**Never paste more than one PowerShell block at a time.** PowerShell evaluates
variables when a block is parsed, not when each line runs.

## Before they arrive

1. Docker running, database up: `docker compose up -d`
2. **Start from a clean day.** This clears orders, customers, plans, attendance, GPS and SOS.
   Users, the office location and the areas stay.
   ```powershell
   docker exec droppoint-postgres psql -U droppoint -d droppoint -c "TRUNCATE sos_alert, location_ping, checkin_attempt, attendance, plan_assignment, day_plan, agent_availability, orders, customer RESTART IDENTITY CASCADE;"
   ```
   Do **not** run `docker compose down -v`: it deletes the database and the migrations
   bring back the old demo users and the old office location.
3. Start the app: `.\mvnw spring-boot:run`. The Groq key comes from `local.properties`;
   without it the assistant and the plan summary fall back to plain text.
4. Check it is up: `http://localhost:8081/api/ping`
5. Open two browser windows on `http://localhost:8081/login.html`:
   - **Manager window**: normal window. Click the **Manager** quick-login (`2222222222`).
   - **Agent window**: an **Incognito** window, so the two logins don't overwrite each other.
     Press F12, toggle device mode (Ctrl+Shift+M) so it looks like a phone, and click the
     **Delivery agent** quick-login (Ramesh, `3333333333`).
6. In the agent window's DevTools, open **Sensors** (⋮ → More tools → Sensors) and set
   Location to the office: `17.4467, 78.3769`. Laptop GPS is often too weak for check-in.

All PINs are `1234`. Use `localhost` for the agent window: browsers only allow GPS on
`https` or `localhost`.

## The five minutes

### 1. Orders come in (45 s) — manager window, **Orders**
"Orders arrive as WhatsApp messages or a spreadsheet. Nobody types coordinates."

Click **Upload file** and pick `src/main/resources/sample_orders.csv`, then **Preview Orders**.
The preview shows 14 valid rows grouped by area. Click **Import to Waiting Pile**.
It takes about 15 seconds: each new address is geocoded, one per second.
"Phone numbers must be exactly 10 digits; a bad line is reported, the rest still import."

### 2. Plan the day (60 s) — **Today**, Day plan card
Click **Plan the day**.
"Every agent has a daily limit. The plan splits areas across whoever is in today, and
whatever doesn't fit carries over to tomorrow, first in line."
Point at the summary paragraph: "That's written by the AI from the plan's numbers."

Optionally move an area to another agent with the dropdown, then click **Confirm and Assign**.
"Made a mistake? **Reopen** puts every undelivered order back and I can plan again."

### 3. Attendance (45 s)
"Agents used to sign a paper register. Check-in only works at the office, and every
attempt is logged, including the refused ones."

From Dilsukhnagar, about 18 km away:
```powershell
$b = @{ latitude = 17.3688; longitude = 78.5247; accuracyMetres = 12; mockLocation = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/agents/4/check-in -ContentType "application/json" -Body $b | Format-List
```
"Refused: TOO_FAR." Then in the **agent window**, **Duty** tab, tap **Check In**. Accepted.
In the manager window, refresh the page: **Today at a glance** now shows Ramesh "On duty since …".

(The check-in radius is 200 m around the office. Presenting somewhere else? Keep the
Sensors location on the office.)

### 4. Delivery proof (60 s) — agent window
"The customer reads out a 4-digit code. Without it, the delivery doesn't count."

The codes are in the manager window: **Orders** → **Assigned orders & delivery codes**.
("In production the customer gets this by SMS. Agents never see the codes.")

Agent window, **Deliveries** tab, tap **Delivered** on a stop:
- Type `0000`: "Blocked, wrong code. The order stays open."
- Tap again and type the real code: "Delivery confirmed."

On another stop tap **Failed** and pick **Nobody home** from the list.

Back in the manager window, refresh the page. **Today at a glance**: delivered and failed counts have moved.
"Nobody typed that in."

### 5. SOS (20 s, optional)
Agent window, **SOS** tab: press and hold the button for 2 seconds. Within 10 seconds the
alert appears in the manager window under **SOS alerts** (the sidebar shows a red count),
with the agent's position. Click **Acknowledge Alert**.

### 6. The AI (60 s) — manager window, **AI assistant**
Click **What is happening today?**, then **Which agent has the most failed deliveries this week?**
"It ran a real query. The model can't write SQL and can't see the database. It can only
call six read-only functions I wrote."

Don't ask about lost customers or monthly trends: a clean day has no history, and it
will truthfully say there's no data.

## Closing line
"Everything you saw runs on one Spring Boot service and one Postgres database.
The business rules are plain Java with tests behind them. Next steps are secure logins,
SMS delivery codes, route order for each agent, and WhatsApp booking."

## If something breaks
- Assistant errors → the Groq key is missing from `local.properties`, or the free tier's
  rate limit hit; wait 10 seconds and ask again
- Check-in says LOW_ACCURACY or TOO_FAR → set the office location in DevTools Sensors (step 6)
- The agent window suddenly shows the manager → both logins were in one normal window; use Incognito
- Port 8081 busy → `Get-NetTCPConnection -LocalPort 8081 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`
- Anything unexplained → run the clean-day command from step 2 and carry on; never debug live
