# Stops the PulseDesk API and database after an interview. The site itself stays: it sleeps on its own,
# and a visit wakes everything again (limited to an hour a day). Demo data is kept.
# Usage: .\scripts\azure\demo-down.ps1 [-ResourceGroup pulsedesk-rg]
param([string]$ResourceGroup = 'pulsedesk-rg')
. "$PSScriptRoot\common.ps1"

Assert-SignedIn
Write-Host 'Stopping PulseDesk'
Set-PulseDeskHold $ResourceGroup 0 -Quiet | Out-Null
Invoke-PulseDeskAppAction $ResourceGroup 'stop'
Write-Host "`nStopped. Demo data is kept; a visit to the site or demo-up.ps1 starts it again." -ForegroundColor Green
