# DropPoint — 5 minute demo script

**Never paste more than one PowerShell block at a time.** PowerShell evaluates
variables when a block is parsed, not when each line runs. Assign an
`Invoke-RestMethod` list to a variable before piping it, or the pipe yields one empty row.

## Before they arrive

1. Docker running.
2. **Reset to the seed data.** This wipes the demo database, and the migrations reseed it
   on start: 3 agents, 6 areas, 8 waiting orders.
   ```powershell
   docker compose down -v; docker compose up -d
   ```
3. Start the app: `.\mvnw spring-boot:run`. The Groq key comes from `local.properties`;
   without it the assistant and the plan summary fall back to plain text.
4. Check it is up: `http://localhost:8081/api/ping`
5. Open two browser windows, both on `http://localhost:8081/login.html`:
   - **Manager window**: normal size. Click the **Manager** quick-login.
   - **Agent window**: press F12, toggle device mode (Ctrl+Shift+M) so it looks like a phone.
     Click the **Agent** quick-login (Ramesh Kumar, agent 4).
6. In the agent window's DevTools, open **Sensors** (⋮ → More tools → Sensors) and set
   Location to `17.3688, 78.5247` (Dilsukhnagar). Real laptop GPS is often too weak for
   check-in and too far from customers for a clean delivery.

Use `localhost` for the agent window, not your laptop's IP from a phone: browsers only
allow GPS on `https` or `localhost`.

## The five minutes

### 1. Orders come in (45 s) — manager window, **Orders**
"Orders arrive as WhatsApp messages or a spreadsheet. Nobody types coordinates."

Click **Upload File** and pick `src/main/resources/sample_orders.csv`. The preview shows
14 rows: 8 known customers, 6 new ones, grouped by area. Click **Import to Waiting Pile**.
"New customers are placed by geocoding their address, or at the area centre if that fails."

### 2. Plan the day (60 s) — **Today**, Day plan card
Click **Plan the day**.
"Every agent has a daily cap. The plan splits areas across whoever is in today, keeps an
agent on the area they know, and whatever doesn't fit carries over to tomorrow, first in line."
Point at the summary paragraph: "That's written by the AI from the plan's numbers."

Optionally move an area to another agent with the dropdown, then click **Confirm and Assign**.
(Reopen is safe now: it releases undelivered orders and you can plan again.)

### 3. Attendance (45 s) — PowerShell
"Agents used to sign a paper register. Check-in only works near the branch, and every
attempt is logged, including the refused ones."

From Gachibowli, about 15 km away:
```powershell
$b = @{ latitude = 17.4401; longitude = 78.3489; accuracyMetres = 12; mockLocation = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/agents/4/check-in -ContentType "application/json" -Body $b | Format-List
```
"Refused: TOO_FAR." Then in the **agent window**, **Duty** tab, tap **Check In**. Accepted.

(The branch radius is 10 km so the live check-in works from wherever you present. To
refuse at a tighter distance, lower `branch.checkin_radius_m`.)

### 4. Delivery proof (60 s) — agent window
"The customer reads out a 4-digit code. Without it, the delivery doesn't count."

Read the code from the manager window: **Orders** → **Open orders & delivery codes**.
("In production the customer gets this by SMS.")

On a Dilsukhnagar order, tap **Delivered**:
- Type `0000`: "Blocked, wrong code. The order stays open."
- Tap again and type the real code: "Delivery verified."

"If the code is right but the agent is more than 150 m from the address, it still counts
but is flagged for the manager." (Change the Sensors location to show it.)

On another order tap **Failed** and pick **Nobody home** from the list.

Back in the manager window, **Today** at a glance: delivered and failed counts have moved.
"Nobody typed that in."

### 5. SOS (20 s, optional)
Agent window, **SOS** tab (it appears after check-in): press and hold the SOS button. Within a few seconds the alert appears in
the manager window under **SOS alerts** (the sidebar shows a red count), with the agent's position. Click **Acknowledge Alert**.

### 6. The AI (60 s) — manager window, **Ask Assistant**
Click **What is happening today?**, then **Which agent has the most failed deliveries this week?**
"It ran a real query. The model can't write SQL and can't see the database. It can only
call six read-only functions I wrote."

Don't ask about lost customers or monthly trends: a fresh reset has no history, and it
will truthfully say there's no data.

## Closing line
"Everything you saw runs on one Spring Boot service and one Postgres database.
The business rules are plain Java with tests behind them. Next steps are route planning
across stops, WhatsApp booking, and SMS delivery codes."

## If something breaks
- Assistant errors → the Groq key is missing from `local.properties`, or the free tier's
  rate limit hit; wait 10 seconds and ask again
- Check-in says LOW_ACCURACY → set a location in DevTools Sensors (step 6 above)
- Delivery flagged thousands of km away → the browser had no location; set Sensors
- Port 8081 busy → `Get-NetTCPConnection -LocalPort 8081 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`
- Anything unexplained → reset and carry on; never debug live
