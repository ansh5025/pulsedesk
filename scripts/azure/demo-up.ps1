# Starts PulseDesk before an interview and keeps it awake, so nobody waits on the wake-up screen.
# Takes 2-3 minutes. Without this, a visit to the site wakes it anyway (limited to an hour a day).
# Usage: .\scripts\azure\demo-up.ps1 [-ResourceGroup pulsedesk-rg] [-Minutes 120]
param(
    [string]$ResourceGroup = 'pulsedesk-rg',
    [int]$Minutes = 120
)
. "$PSScriptRoot\common.ps1"

Assert-SignedIn
Write-Host 'Starting PulseDesk'
Invoke-PulseDeskAppAction $ResourceGroup 'start'

$url = Get-PulseDeskWebUrl $ResourceGroup
# The site may take a few seconds to wake up, so retry the hold briefly.
$held = $false
for ($i = 0; $i -lt 6 -and -not $held; $i++) {
    $held = Set-PulseDeskHold $ResourceGroup $Minutes -Quiet
    if (-not $held) { Start-Sleep -Seconds 10 }
}
if (-not $held) { Write-Warning "Couldn't set the hold; PulseDesk will sleep 3 minutes after the last visitor leaves." }

if (Wait-PulseDeskHealthy $url) {
    Write-Host "`nReady: $url" -ForegroundColor Green
    Write-Host 'Demo sign-in: organization demo, password Demo@12345 (e.g. dana@demo).'
} else {
    Write-Warning "Started, but $url/healthz is not UP yet. Check logs: az containerapp logs show -g $ResourceGroup -n pulsedesk-api --container api"
}
if ($held) { Write-Host "Kept awake for $Minutes minutes; after that it sleeps 3 minutes after the last visitor leaves." }
Write-Host 'Done early? Run .\scripts\azure\demo-down.ps1 (a nightly job also stops it at 01:00 IST).'
