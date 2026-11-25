# Creates or updates PulseDesk on Azure: infrastructure, images and apps.
# Usage: az login; .\scripts\azure\deploy.ps1 [-ResourceGroup pulsedesk-rg] [-Location japaneast]
# Re-running is safe; it builds fresh images. Each run regenerates the JWT secret, so users sign in again.
# Japan East is the default because Azure for Students subscriptions get SkuNotAvailable for the B1ms
# database in several closer regions (tested: Central India, Southeast Asia, Korea Central; South India,
# East US and West Europe are blocked outright). Pick another region with -Location if yours allows it.
param(
    [string]$ResourceGroup = 'pulsedesk-rg',
    [string]$Location = 'japaneast'
)
. "$PSScriptRoot\common.ps1"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$template = Join-Path $root 'infra\main.bicep'

Assert-SignedIn
Invoke-Quiet { docker version --format '{{.Server.Version}}' } | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop and try again.' }

Write-Host '1/5 Resource group'
az group create --name $ResourceGroup --location $Location --output none
Assert-Az 'Creating the resource group'
# Azure cannot update a stopped Postgres server, so wake it if a previous deploy exists.
Start-PulseDeskDatabase $ResourceGroup

Write-Host '2/5 Registry, database and environment (first run takes about 10 minutes)'
$base = az deployment group create --resource-group $ResourceGroup --name pulsedesk-base --template-file $template `
    --parameters deployApps=false --query properties.outputs --output json | ConvertFrom-Json
Assert-Az 'Deploying the base infrastructure'
$registry = $base.acrLoginServer.value

Write-Host '3/5 Building and pushing images'
$tag = Get-Date -Format 'yyyyMMddHHmm'
# A just-created registry can take a minute to become visible to `az acr login`.
foreach ($attempt in 1..6) {
    Invoke-Quiet { az acr login --name $base.acrName.value } | Out-Null
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "  Registry not ready yet, retrying in 15 s ($attempt/6)"
    Start-Sleep -Seconds 15
}
Assert-Az 'Signing in to the container registry'
$images = @{}
foreach ($image in @(@{ Name = 'pulsedesk-api'; Dir = 'backend' }, @{ Name = 'pulsedesk-web'; Dir = 'frontend' })) {
    $ref = "$registry/$($image.Name):$tag"
    docker build --platform linux/amd64 --tag $ref (Join-Path $root $image.Dir)
    Assert-Az "Building $($image.Name)"
    docker push $ref
    Assert-Az "Pushing $($image.Name)"
    $images[$image.Name] = $ref
}

Write-Host '4/5 Container apps and nightly auto-stop job'
# Keep a custom domain bound by custom-domain.ps1; leaving it out of the template would unbind it.
$binding = Get-PulseDeskDomainBinding $ResourceGroup 'pulsedesk-web'
$domainParams = @()
if ($binding) {
    Write-Host "  Keeping custom domain $($binding.name)"
    $domainParams = @("customDomain=$($binding.name)", "customDomainCertificateId=$($binding.cert)")
}
$out = az deployment group create --resource-group $ResourceGroup --name pulsedesk-apps --template-file $template `
    --parameters deployApps=true "apiImage=$($images['pulsedesk-api'])" "webImage=$($images['pulsedesk-web'])" @domainParams `
    --query properties.outputs --output json | ConvertFrom-Json
Assert-Az 'Deploying the apps'
# Apps stopped by demo-down or the nightly job stay stopped through an update, so start them explicitly.
Invoke-PulseDeskAppAction $ResourceGroup 'start'

$url = $out.webUrl.value
Write-Host '5/5 Waiting for the backend to start'
if (Wait-PulseDeskHealthy $url) {
    Write-Host "`nPulseDesk is live at $url" -ForegroundColor Green
} else {
    Write-Warning "Deployed, but $url/healthz is not UP yet. Check logs: az containerapp logs show -g $ResourceGroup -n pulsedesk-api --container api"
}
Write-Host "First time only, load the demo data:  .\scripts\seed-demo.ps1 -Base $url/api/v1"
Write-Host "Stop everything when you're done:     .\scripts\azure\demo-down.ps1"
