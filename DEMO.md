# DropPoint — 5 minute demo script

**Never paste more than one block at a time.** PowerShell evaluates variables when a
block is parsed, not when each line runs. Note also that Windows PowerShell 5.1 does
not enumerate an `Invoke-RestMethod` list when you pipe it inline - assign it to a
variable first, as blocks A and E do, or the pipe yields one empty row.

## Before they arrive

1. Docker running: `docker ps` shows `droppoint-postgres`
2. Terminal: `$env:GROQ_API_KEY = "..."` then `.\mvnw spring-boot:run`
3. Check health: open `http://localhost:8081/api/demo/health`
   - seededOrders 162, deliveryProofs 162, quietCustomers 12, groqKeySet true
4. Reset: POST `http://localhost:8081/api/demo/reset`
   - note the `waitingOrderId` it returns — you need it in step 5
5. Open two tabs: dashboard `/`, control console `/control.html`
6. Start on the dashboard tab

## The five minutes

### 1. Open on the dashboard (30 s)
"This is what the owner sees. 57 deliveries in the last 30 days, 86% on time.
The one number I'd act on today is here —" point at Mahesh Babu's late check-ins.
"He's been late 16 times. Nobody else is late at all."

### 2. Attendance (45 s)
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

### 3. Assignment and ETA (60 s) — tab: control console
Hand the waiting DEMO- order (Priya Sharma) to Ravi, the agent who just checked in:
```powershell
$all = Invoke-RestMethod http://localhost:8081/api/orders
$id = ($all | Where-Object { $_.orderNo -like 'DEMO-*' } | Select-Object -First 1).id
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/orders/$id/assign/3" | Format-List
```

Point at the ETA it comes back with: "Google would tell us the drive takes X
minutes. This says X plus 15 — because Priya is on the 5th floor of a gated
building and there are two cylinders to carry. That's the time that actually
makes deliveries late, and no maps API knows it."

### 4. Delivery proof (60 s)
"The customer reads out a 4-digit code." One block at a time.

**A — pick up the order and the agent it went to**
```powershell
$all = Invoke-RestMethod http://localhost:8081/api/orders
$o = $all | Where-Object { $_.orderNo -like 'DEMO-*' } | Select-Object -First 1
$id = $o.id; $agent = $o.agentId
"order $id, assigned to $($o.agentName) (agent $agent), code $($o.deliveryCode)"
```

**B — wrong code, right place**
```powershell
$b = @{ agentId = $agent; latitude = 17.3712; longitude = 78.5580; code = "0000" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/orders/$id/deliver" -ContentType "application/json" -Body $b | Format-List
```
"Blocked - WRONG_CODE. The order is still open, so he can try again."

**C — correct code, right place**
```powershell
$b = @{ agentId = $agent; latitude = 17.3712; longitude = 78.5580; code = $o.deliveryCode } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/orders/$id/deliver" -ContentType "application/json" -Body $b | Format-List
```
"VERIFIED, a few metres from her door. Beyond 150 m it still records the delivery -
the agent gets paid - but the manager sees it."

**D — the owner's numbers move on their own**
```powershell
(Invoke-RestMethod http://localhost:8081/api/dashboard).today | Format-List
```
"delivered_today just went up. Nobody typed that in anywhere."

**E — what the manager reviews**
```powershell
$flagged = Invoke-RestMethod http://localhost:8081/api/deliveries/suspicious
$flagged | Select-Object -First 3 orderId, agentId, @{n='metresAway';e={[math]::Round($_.distanceM)}}, verdict, lateMinutes | Format-Table
```
"Right code, but 2 to 3 km from the address. That is the case worth a phone call."

### 5. The AI (75 s) — tab: dashboard
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
The business rules are plain Java with 40 tests behind them. Next steps are
route planning across multiple stops, WhatsApp booking, and stock settlement."

## If something breaks
- Assistant errors → GROQ_API_KEY not set in this terminal
- Delivery says NOT_ASSIGNED_AGENT → block A picked the wrong order; use the agent id block A printed
- Port 8081 busy → `Get-NetTCPConnection -LocalPort 8081 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`
- Anything unexplained → reset and carry on; never debug live
