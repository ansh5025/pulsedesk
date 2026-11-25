# Shared helpers for the PulseDesk Azure scripts. Dot-source this file; don't run it directly.
$ErrorActionPreference = 'Stop'

function Assert-Az([string]$What) {
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)." }
}

# Runs a native command with stderr hidden. Windows PowerShell 5.1 turns redirected stderr into
# a terminating error under ErrorActionPreference=Stop, so relax it here and let callers check $LASTEXITCODE.
function Invoke-Quiet([scriptblock]$Command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command 2>$null } finally { $ErrorActionPreference = $saved }
}

function Assert-SignedIn {
    Invoke-Quiet { az account show --output none } | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Not signed in to Azure. Run 'az login' first." }
}

function Get-PulseDeskApps([string]$ResourceGroup) {
    $json = az containerapp list --resource-group $ResourceGroup --query "[].{id:id, name:name, fqdn:properties.configuration.ingress.fqdn, external:properties.configuration.ingress.external}" --output json
    Assert-Az 'Listing the container apps'
    # Windows PowerShell 5.1 emits a JSON array as one object; ForEach-Object unrolls it into items.
    return @(($json | Out-String) | ConvertFrom-Json | ForEach-Object { $_ })
}

# The CLI has no start/stop for container apps, so call the management API directly.
# Start the database first and stop it last, so the API never loses it mid-request and Postgres shuts down cleanly.
function Invoke-PulseDeskAppAction([string]$ResourceGroup, [ValidateSet('start', 'stop')][string]$Action) {
    $arm = (az cloud show --query endpoints.resourceManager --output tsv).TrimEnd('/')
    $version = az provider show --namespace Microsoft.App --query "resourceTypes[?resourceType=='containerApps'].apiVersions[0]" --output tsv
    Assert-Az 'Looking up the Container Apps API version'
    $order = @{ 'pulsedesk-db' = 0; 'pulsedesk-api' = 1; 'pulsedesk-web' = 2 }
    $apps = Get-PulseDeskApps $ResourceGroup | Sort-Object { if ($order.ContainsKey($_.name)) { $order[$_.name] } else { 1 } } -Descending:($Action -eq 'stop')
    foreach ($app in $apps) {
        Write-Host "  $Action $($app.name)"
        az rest --method post --url "$arm$($app.id)/$($Action)?api-version=$version" --output none
        Assert-Az "$Action $($app.name)"
    }
}

# Checks an image can be pulled anonymously from GitHub Container Registry: 'ok', 'missing' or 'private'.
function Test-PublicImage([string]$Repository, [string]$Tag) {
    try {
        $token = (Invoke-RestMethod "https://ghcr.io/token?scope=repository:$($Repository):pull" -TimeoutSec 20).token
    } catch { return 'private' }
    $headers = @{
        Authorization = "Bearer $token"
        Accept = 'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json'
    }
    try {
        Invoke-WebRequest "https://ghcr.io/v2/$Repository/manifests/$Tag" -Method Head -Headers $headers -UseBasicParsing -TimeoutSec 20 | Out-Null
        return 'ok'
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -eq 404) { return 'missing' }
        return 'private'
    }
}

function Wait-PulseDeskHealthy([string]$WebUrl, [int]$TimeoutSeconds = 420) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host "  Waiting for $WebUrl to report healthy..."
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$WebUrl/healthz" -TimeoutSec 10
            if ($health.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 10
    }
    return $false
}

function Get-PulseDeskWebUrl([string]$ResourceGroup) {
    $web = Get-PulseDeskApps $ResourceGroup | Where-Object { $_.external } | Select-Object -First 1
    if (-not $web) { throw "No public container app found in $ResourceGroup. Run deploy.ps1 first." }
    $binding = Get-PulseDeskDomainBinding $ResourceGroup $web.name
    if ($binding) { return "https://$($binding.name)" }
    return "https://$($web.fqdn)"
}

# Returns {name, cert} for a custom domain bound to the app, or $null if there is none (or no app yet).
function Get-PulseDeskDomainBinding([string]$ResourceGroup, [string]$AppName) {
    $json = Invoke-Quiet { az containerapp hostname list --resource-group $ResourceGroup --name $AppName --query "[?certificateId!=null] | [0].{name:name, cert:certificateId}" --output json }
    if ($LASTEXITCODE -ne 0 -or -not $json -or "$json".Trim() -eq '' -or "$json".Trim() -eq 'null') { return $null }
    return ($json | Out-String) | ConvertFrom-Json
}
