# One-time setup: serve PulseDesk at a custom domain with a free Azure-managed HTTPS certificate.
# Usage (after deploy.ps1, with the apps running): .\scripts\azure\custom-domain.ps1 [-Domain pulsedesk.anshrai.in]
# The certificate renews automatically as long as the CNAME record stays in place.
param(
    [string]$Domain = 'pulsedesk.anshrai.in',
    [string]$ResourceGroup = 'pulsedesk-rg',
    [int]$DnsTimeoutMinutes = 30
)
. "$PSScriptRoot\common.ps1"

Assert-SignedIn
$web = 'pulsedesk-web'
$app = az containerapp show --resource-group $ResourceGroup --name $web --query "{fqdn:properties.configuration.ingress.fqdn, verifyId:properties.customDomainVerificationId, envId:properties.managedEnvironmentId}" --output json | ConvertFrom-Json
Assert-Az "Reading $web (run deploy.ps1 first)"

$label = $Domain.Split('.')[0]
Write-Host "`nAdd these two records where the DNS for $($Domain.Substring($label.Length + 1)) is managed:`n" -ForegroundColor Cyan
Write-Host ("  {0,-6} {1,-20} {2}" -f 'Type', 'Name / Host', 'Value')
Write-Host ("  {0,-6} {1,-20} {2}" -f 'CNAME', $label, $app.fqdn)
Write-Host ("  {0,-6} {1,-20} {2}" -f 'TXT', "asuid.$label", $app.verifyId)
Write-Host "`nIf the domain is on Cloudflare, set the CNAME to 'DNS only' (grey cloud), not proxied."

Write-Host "`nWaiting for the records to appear in public DNS (checks every 30 s, up to $DnsTimeoutMinutes min)..."
$deadline = (Get-Date).AddMinutes($DnsTimeoutMinutes)
$cnameOk = $false; $txtOk = $false
while ((Get-Date) -lt $deadline -and -not ($cnameOk -and $txtOk)) {
    $cname = Resolve-DnsName -Name $Domain -Type CNAME -Server 1.1.1.1 -DnsOnly -ErrorAction SilentlyContinue | Where-Object { $_.Type -eq 'CNAME' }
    $txt = Resolve-DnsName -Name "asuid.$Domain" -Type TXT -Server 1.1.1.1 -DnsOnly -ErrorAction SilentlyContinue | Where-Object { $_.Type -eq 'TXT' }
    $cnameOk = [bool]($cname | Where-Object { $_.NameHost.TrimEnd('.') -eq $app.fqdn })
    $txtOk = [bool]($txt | Where-Object { $_.Strings -contains $app.verifyId })
    Write-Host ("  {0:HH:mm:ss}  CNAME {1}  TXT {2}" -f (Get-Date), $(if ($cnameOk) { 'ok' } else { 'waiting' }), $(if ($txtOk) { 'ok' } else { 'waiting' }))
    if (-not ($cnameOk -and $txtOk)) { Start-Sleep -Seconds 30 }
}
if (-not ($cnameOk -and $txtOk)) { throw "DNS records not visible after $DnsTimeoutMinutes minutes. Check them, then run this script again." }

Write-Host "`nBinding $Domain and issuing the certificate (can take 5-20 minutes)..."
Invoke-Quiet { az containerapp hostname add --resource-group $ResourceGroup --name $web --hostname $Domain --output none } | Out-Null
az containerapp hostname bind --resource-group $ResourceGroup --name $web --hostname $Domain `
    --environment $app.envId --validation-method CNAME --output none
Assert-Az "Binding $Domain"

# Invitation emails and links should use the new address.
az containerapp update --resource-group $ResourceGroup --name pulsedesk-api `
    --set-env-vars "PULSEDESK_INVITE_URL=https://$Domain/accept-invite" --output none
Assert-Az 'Updating the invite URL'

Write-Host "`nDone: https://$Domain" -ForegroundColor Green
Write-Host 'Future deploy.ps1 runs keep this binding automatically.'
