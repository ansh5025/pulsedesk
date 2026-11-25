# Stops PulseDesk after an interview so only storage and the registry are billed.
# Usage: .\scripts\azure\demo-down.ps1 [-ResourceGroup pulsedesk-rg]
param([string]$ResourceGroup = 'pulsedesk-rg')
. "$PSScriptRoot\common.ps1"

Assert-SignedIn
Write-Host 'Stopping PulseDesk'
Invoke-PulseDeskAppAction $ResourceGroup 'stop'
Stop-PulseDeskDatabase $ResourceGroup
Write-Host "`nStopped. Demo data is kept; run demo-up.ps1 next time." -ForegroundColor Green
