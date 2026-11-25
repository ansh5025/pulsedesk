# Seeds a public demo organisation through the running API. Safe to re-run.
# Usage: powershell -File scripts\seed-demo.ps1 [-Base http://localhost:8080/api/v1]
param([string]$Base = "http://localhost:8080/api/v1")

$Slug = "demo"
$Password = "Demo@12345"

function Call($method, $path, $token, $body) {
    $h = @{}
    if ($token) { $h.Authorization = "Bearer $token" }
    $args = @{ Method = $method; Uri = "$Base$path"; Headers = $h; ContentType = "application/json" }
    if ($body) { $args.Body = ($body | ConvertTo-Json -Depth 5) }
    Invoke-RestMethod @args
}

# Admin: register, or log in if the demo org already exists
try {
    $auth = Call POST "/auth/register" $null @{
        organizationName = "Demo Org"; organizationSlug = $Slug; fullName = "Dana Admin"
        email = "dana@demo.example"; password = $Password }
    $fresh = $true
} catch {
    $adminUser = "dana@$Slug"
    $auth = Call POST "/auth/login" $null @{ organizationSlug = $Slug; username = $adminUser; password = $Password }
    $fresh = $false
}
$admin = $auth.accessToken
if (-not $fresh) { Write-Host "Demo org already exists - nothing to seed."; }
else {
    function Invite($name, $role, $teamId) {
        $b = @{ fullName = $name; role = $role }
        if ($teamId) { $b.teamId = $teamId }
        $inv = Call POST "/invitations" $admin $b
        Call POST "/invitations/accept" $null @{ token = $inv.token; password = $Password }
    }

    $team = Call POST "/teams" $admin @{ name = "Platform" }
    $team2 = Call POST "/teams" $admin @{ name = "Payments" }

    $mgr  = Invite "Marcus Manager" "MANAGER" $null
    $lead = Invite "Tara Lead" "TEAM_LEAD" $null
    $dev1 = Invite "Dev Kumar" "DEVELOPER" $team.id
    $dev2 = Invite "Priya Dev" "DEVELOPER" $team2.id

    $members = Call GET "/organizations/current/members" $admin
    $id = @{}
    foreach ($m in $members) { $id[$m.fullName] = $m.membershipId }
    Call PUT "/teams/$($team.id)/lead" $admin @{ membershipId = $id["Tara Lead"] } | Out-Null

    $api = Call POST "/services" $admin @{ name = "Payments API"; description = "Card payments and order placement"; teamId = $team2.id }
    $web = Call POST "/services" $admin @{ name = "Customer Web App"; description = "Public storefront"; teamId = $team.id }
    $rep = Call POST "/services" $admin @{ name = "Legacy Reports"; description = "Nightly finance exports"; teamId = $team.id }

    function Check($svc, $url, $status) {
        Call PUT "/services/$($svc.id)/health-check" $admin @{ url = $url; expectedStatus = $status
            intervalSeconds = 3600; timeoutMs = 5000; failureThreshold = 1; enabled = $true } | Out-Null
        try { Call POST "/services/$($svc.id)/health-check/run" $admin $null | Out-Null } catch { Write-Host "check skipped: $($_.Exception.Message)" }
    }
    Check $api "https://example.com" 200
    Check $web "https://example.com" 200
    Check $rep "https://example.com/this-page-does-not-exist" 200   # fails on purpose: shows a DOWN service

    function Incident($title, $desc, $prio, $svc, $assignee) {
        $b = @{ title = $title; description = $desc; priority = $prio; serviceId = $svc.id }
        if ($assignee) { $b.assigneeId = $assignee }
        Call POST "/incidents" $admin $b
    }
    $i1 = Incident "Checkout returning 502 for EU customers" "Started after the 14:10 deploy. Roughly 1 in 4 payments fail." "CRITICAL" $api $id["Priya Dev"]
    $i2 = Incident "Slow search results on mobile" "p95 latency above 4s on the product search." "MEDIUM" $web $id["Dev Kumar"]
    $i3 = Incident "Invoice PDF fonts missing" "Finance reports the new invoices render with a fallback font." "LOW" $rep $null
    $i4 = Incident "Login page certificate warning" "Some Safari users see a certificate warning." "HIGH" $web $id["Tara Lead"]
    $i5 = Incident "Duplicate order confirmation emails" "Customers get two emails per order." "MEDIUM" $api $id["Priya Dev"]

    Call PUT "/incidents/$($i1.id)/status" $admin @{ status = "ACKNOWLEDGED" } | Out-Null
    Call POST "/incidents/$($i1.id)/comments" $admin @{ body = "Seeing 502s from the EU gateway. Rolling back the 14:10 deploy." } | Out-Null
    Call PUT "/incidents/$($i2.id)/status" $admin @{ status = "IN_PROGRESS" } | Out-Null
    Call POST "/incidents/$($i2.id)/comments" $admin @{ body = "Index on products.name was dropped in the last migration. Restoring it." } | Out-Null
    foreach ($s in "ACKNOWLEDGED", "RESOLVED") { Call PUT "/incidents/$($i4.id)/status" $admin @{ status = $s } | Out-Null }
    foreach ($s in "IN_PROGRESS", "RESOLVED") { Call PUT "/incidents/$($i5.id)/status" $admin @{ status = $s } | Out-Null }
    Call PUT "/incidents/$($i5.id)/status" $admin @{ status = "CLOSED" } | Out-Null
}

Write-Host ""
$site = if ($Base -match 'localhost:8080') { 'http://localhost:3000' } else { $Base -replace '/api/v1/?$', '' }
Write-Host "Demo org ready. Sign in at $site with organisation '$Slug' and password '$Password'."
$login = Call GET "/organizations/current/members" $admin
$login | ForEach-Object { "{0,-18} {1,-10} username: {2}" -f $_.fullName, $_.role, $_.username }
