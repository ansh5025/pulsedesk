# Creates or updates PulseDesk on Azure from the images CI published for the current commit.
# Usage: az login; .\scripts\azure\deploy.ps1 [-ResourceGroup pulsedesk-rg] [-Location japaneast] [-Tag <commit sha>]
# Push your commit first and wait for the CI "Docker images" job: it publishes ghcr.io/<owner>/pulsedesk-{api,web}:<sha>.
# Each run regenerates the JWT secret, so users sign in again. The database password and data are kept.
# Japan East is the default because Azure for Students subscriptions hit SkuNotAvailable or region blocks
# in several closer regions. Pick another with -Location if yours allows it.
param(
    [string]$ResourceGroup = 'pulsedesk-rg',
    [string]$Location = 'japaneast',
    [string]$Tag = ''
)
. "$PSScriptRoot\common.ps1"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$template = Join-Path $root 'infra\main.bicep'

Assert-SignedIn

Write-Host '1/4 Checking the images'
if (-not $Tag) { $Tag = (git -C $root rev-parse HEAD).Trim() }
$remote = (git -C $root remote get-url origin).Trim()
if ($remote -notmatch 'github\.com[:/]([^/]+)/') { throw "Can't work out the GitHub owner from remote '$remote'." }
$owner = $Matches[1].ToLower()
$images = @{}
foreach ($name in 'pulsedesk-api', 'pulsedesk-web') {
    $repo = "$owner/$name"
    switch (Test-PublicImage $repo $Tag) {
        'ok' { $images[$name] = "ghcr.io/$($repo):$Tag"; Write-Host "  ghcr.io/$($repo):$Tag" }
        'missing' { throw "ghcr.io/$($repo):$Tag doesn't exist yet. Push this commit and wait for the CI 'Docker images' job to finish." }
        'private' { throw "ghcr.io/$repo isn't public. On GitHub: your profile > Packages > $name > Package settings > Change visibility > Public." }
    }
}

Write-Host '2/4 Resource group'
az group create --name $ResourceGroup --location $Location --output none
Assert-Az 'Creating the resource group'

# Postgres only applies the password when it first creates its data directory, so reuse the existing one.
$dbPassword = Invoke-Quiet { az containerapp secret show --resource-group $ResourceGroup --name pulsedesk-db --secret-name db-password --query value --output tsv }
if ($LASTEXITCODE -ne 0 -or -not "$dbPassword".Trim()) {
    $dbPassword = 'Pd1' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N').Substring(0, 8)
}
$dbPassword = "$dbPassword".Trim()

Write-Host '3/4 Infrastructure and apps (first run takes about 10 minutes)'
# Keep a custom domain bound by custom-domain.ps1; leaving it out of the template would unbind it.
$binding = Get-PulseDeskDomainBinding $ResourceGroup 'pulsedesk-web'
$extra = @()
if ($binding) {
    Write-Host "  Keeping custom domain $($binding.name)"
    $extra = @("customDomain=$($binding.name)", "customDomainCertificateId=$($binding.cert)")
}
# Pass the secret through a temporary parameters file so it never appears on a command line.
$paramsFile = Join-Path ([IO.Path]::GetTempPath()) "pulsedesk-params-$([guid]::NewGuid().ToString('N')).json"
@{ '$schema' = 'https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#'
   contentVersion = '1.0.0.0'
   parameters = @{ dbPassword = @{ value = $dbPassword } } } | ConvertTo-Json -Depth 5 | Set-Content -Path $paramsFile -Encoding utf8
try {
    $out = az deployment group create --resource-group $ResourceGroup --name pulsedesk --template-file $template `
        --parameters "@$paramsFile" "apiImage=$($images['pulsedesk-api'])" "webImage=$($images['pulsedesk-web'])" @extra `
        --query properties.outputs --output json | ConvertFrom-Json
    Assert-Az 'Deploying'
} finally {
    Remove-Item $paramsFile -ErrorAction SilentlyContinue
}
# Apps stopped by demo-down or the nightly job stay stopped through an update, so start them explicitly.
Invoke-PulseDeskAppAction $ResourceGroup 'start'

$url = $out.webUrl.value
Write-Host '4/4 Waiting for the new version to take over'
Wait-PulseDeskRevisions $ResourceGroup
if (Wait-PulseDeskHealthy $url 600) {
    Write-Host "`nPulseDesk is live at $url" -ForegroundColor Green
} else {
    Write-Warning "Deployed, but $url/healthz is not UP yet. Check logs: az containerapp logs show -g $ResourceGroup -n pulsedesk-api --container api"
}
Write-Host "First time only, load the demo data:  .\scripts\seed-demo.ps1 -Base $url/api/v1"
Write-Host "Stop everything when you're done:     .\scripts\azure\demo-down.ps1"
