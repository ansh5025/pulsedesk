# Starts PulseDesk before an interview: database first, then the API and site. Takes 2-3 minutes.
# Usage: .\scripts\azure\demo-up.ps1 [-ResourceGroup pulsedesk-rg]
param([string]$ResourceGroup = 'pulsedesk-rg')
. "$PSScriptRoot\common.ps1"

Assert-SignedIn
Write-Host 'Starting PulseDesk'
Invoke-PulseDeskAppAction $ResourceGroup 'start'

$url = Get-PulseDeskWebUrl $ResourceGroup
if (Wait-PulseDeskHealthy $url) {
    Write-Host "`nReady: $url" -ForegroundColor Green
    Write-Host 'Demo sign-in: organization demo, password Demo@12345 (e.g. dana@demo).'
} else {
    Write-Warning "Started, but $url/healthz is not UP yet. Check logs: az containerapp logs show -g $ResourceGroup -n pulsedesk-api --container api"
}
Write-Host 'Afterwards run .\scripts\azure\demo-down.ps1 (a nightly job also stops everything at 01:00 IST).'
