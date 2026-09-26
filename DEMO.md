# DropPoint — 5 minute demo script

## Before they arrive

1. Docker running: `docker ps` shows `droppoint-postgres`
2. Terminal: `$env:GROQ_API_KEY = "..."` then `.\mvnw spring-boot:run`
3. Check health: open `http://localhost:8081/api/demo/health`
   - seededOrders 162, deliveryProofs 162, quietCustomers 12, groqKeySet true
4. Reset: POST `http://localhost:8081/api/demo/reset`
   - note the `waitingOrderId` it returns — you need it in step 5
5. Open three tabs: dashboard `/`, order board `/board.html`, live map `/map.html`
6. Start on the dashboard tab

## The five minutes

### 1. Open on the dashboard (30 s)
"This is what the owner sees. 57 deliveries in the last 30 days, 86% on time.
The one number I'd act on today is here —" point at Mahesh Babu's late check-ins.
"He's been late 16 times. Nobody else is late at all."

### 2. Attendance (45 s) — tab: map
"Agents used to sign a paper register. Now check-in only works at the branch."

Run from PowerShell, from home coordinates:
```powershell
$b = @{ latitude = 17.3616; longitude = 78.4747; accuracyMetres = 15; mockLocation = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/agents/3/check-in -ContentType "application/json" -Body $b | Format-List
```
"Refused — 8.4 km from the branch." Then from the branch:
```powershell
$b = @{ latitude = 17.3458; longitude = 78.5523; accuracyMetres = 12; mockLocation = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/agents/3/check-in -ContentType "application/json" -Body $b | Format-List
```
"Accepted. And every attempt is logged, including the refused one."

### 3. Assignment and ETA (60 s) — tab: order board
Click **Auto-assign** on the waiting order.
"It didn't just pick the nearest agent — it weighed distance against how many
orders each already has."

Point at the ETA: "Google would tell us the drive takes X minutes. This says
X plus 15 — because Priya is on the 5th floor of a gated building and there are
two cylinders to carry. That's the time that actually makes deliveries late,
and no maps API knows it."

### 4. Live tracking (60 s) — tab: map
Click **Drive Ravi to the customer**. Talk while it moves.
"Position every 30 seconds, only between check-in and check-out — we don't
track anyone off duty. Speed is calculated here; anything above 150 km/h
gets flagged as impossible."

### 5. Delivery proof (45 s)
"The customer reads out a 4-digit code."
```powershell
# wrong code
$d = @{ agentId = 3; latitude = 17.3712; longitude = 78.5580; code = "0000" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/orders/ORDER_ID/deliver" -ContentType "application/json" -Body $d | Format-List
# correct code
$d = @{ agentId = 3; latitude = 17.3712; longitude = 78.5580; code = "4747" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/orders/ORDER_ID/deliver" -ContentType "application/json" -Body $d | Format-List
```
"Wrong code blocks it. Right code at the right place verifies it. Right code
3 km away would still record the delivery — but flag it for the manager."

### 6. The AI (75 s) — tab: dashboard
Click **Who was late most this month?**
"Mahesh Babu, 16 late check-ins." Point at the `queried:` line.
"That's the important part — it ran a real query. The model can't write SQL
and can't see the database. It can only call six functions I wrote."

Click **Which customers have we lost and why?**
"Three or four customers, each compared against their own ordering rhythm.
Anil Verma is 75 days quiet when he normally orders every 30 — and he had
4 late deliveries. That's a call to make today, and nobody had to run a report."

## Closing line
"Everything you saw runs on one Spring Boot service and one Postgres database.
The business rules are plain Java with 48 tests behind them. Next steps are
route planning across multiple stops, WhatsApp booking, and stock settlement."

## If something breaks
- Assistant errors → GROQ_API_KEY not set in this terminal
- Map empty → nobody checked in today, run the reset
- Drive does nothing → agent 3 must be checked in first (step 2)
- Port 8081 busy → `Get-NetTCPConnection -LocalPort 8081 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`
- Anything unexplained → reset and carry on; never debug live
