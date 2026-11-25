// PulseDesk on Azure Container Apps, sized for on-demand demos.
// Deploy with scripts/azure/deploy.ps1 rather than by hand: it builds and pushes the images between two passes.

@description('Azure region for every resource.')
param location string = resourceGroup().location

@description('Short prefix used in resource names.')
@minLength(3)
@maxLength(12)
param prefix string = 'pulsedesk'

@description('First pass creates the registry and database only; second pass adds the apps once images exist.')
param deployApps bool = true

@description('Full image reference for the backend, e.g. myacr.azurecr.io/pulsedesk-api:202611251200.')
param apiImage string = ''

@description('Full image reference for the frontend.')
param webImage string = ''

@description('JWT signing secret. Regenerated on each deployment, which signs everyone out.')
@secure()
param jwtSecret string = '${newGuid()}${newGuid()}'

@description('Postgres admin password. Regenerated on each deployment and passed to the backend in the same pass.')
@secure()
param pgPassword string = 'Pd1!${newGuid()}'

@description('UTC cron for the nightly safety stop of apps and database. Default is 01:00 IST.')
param autoStopCron string = '30 19 * * *'

@description('Custom domain for the frontend, e.g. pulsedesk.anshrai.in. Bind it first with scripts/azure/custom-domain.ps1.')
param customDomain string = ''

@description('Managed certificate already bound to customDomain. deploy.ps1 looks this up so redeploys keep the binding.')
param customDomainCertificateId string = ''

var suffix = uniqueString(resourceGroup().id)
var pgAdmin = 'pulsedeskadmin'
var apiName = '${prefix}-api'
var webName = '${prefix}-web'
var publicHost = empty(customDomain) ? '${webName}.${env.properties.defaultDomain}' : customDomain

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${prefix}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: '${prefix}${suffix}'
  location: location
  sku: { name: 'Basic' }
  properties: {
    adminUserEnabled: true
  }
}

resource pg 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: '${prefix}-pg-${suffix}'
  location: location
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '16'
    administratorLogin: pgAdmin
    administratorLoginPassword: pgPassword
    storage: {
      storageSizeGB: 32
      autoGrow: 'Disabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: { mode: 'Disabled' }
    network: { publicNetworkAccess: 'Enabled' }
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
    }
  }
}

resource pgDatabase 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: pg
  name: 'pulsedesk'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// 0.0.0.0 is Azure's marker for "allow connections from Azure services"; there is no public internet rule.
resource pgAllowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: pg
  name: 'AllowAzureServices'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${prefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

var registry = [
  {
    server: acr.properties.loginServer
    username: acr.listCredentials().username
    passwordSecretRef: 'acr-password'
  }
]

resource api 'Microsoft.App/containerApps@2024-03-01' = if (deployApps) {
  name: apiName
  location: location
  dependsOn: [pgDatabase, pgAllowAzure]
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      activeRevisionsMode: 'Single'
      // Internal only: the browser reaches the API through the frontend's /api proxy.
      ingress: {
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: true
      }
      registries: registry
      secrets: [
        { name: 'acr-password', value: acr.listCredentials().passwords[0].value }
        { name: 'jwt-secret', value: jwtSecret }
        { name: 'db-password', value: pgPassword }
      ]
    }
    template: {
      containers: [
        {
          name: 'api'
          image: apiImage
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: [
            { name: 'PULSEDESK_DB_URL', value: 'jdbc:postgresql://${pg.properties.fullyQualifiedDomainName}:5432/pulsedesk?sslmode=require' }
            { name: 'PULSEDESK_DB_USER', value: pgAdmin }
            { name: 'PULSEDESK_DB_PASSWORD', secretRef: 'db-password' }
            { name: 'PULSEDESK_JWT_SECRET', secretRef: 'jwt-secret' }
            { name: 'PULSEDESK_REDIS_HOST', value: 'localhost' }
            { name: 'PULSEDESK_MESSAGING_ENABLED', value: 'false' }
            { name: 'PULSEDESK_INVITE_URL', value: 'https://${publicHost}/accept-invite' }
            // Trust X-Forwarded-For from the in-cluster proxies so login rate limiting sees the real client IP.
            { name: 'SERVER_FORWARD_HEADERS_STRATEGY', value: 'native' }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: { path: '/actuator/health', port: 8080 }
              initialDelaySeconds: 20
              periodSeconds: 10
              failureThreshold: 10
            }
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health', port: 8080 }
              periodSeconds: 30
              failureThreshold: 3
            }
          ]
        }
        {
          // Cache and login counters only, so losing it on restart is fine.
          name: 'redis'
          image: 'docker.io/library/redis:7-alpine'
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
        }
      ]
      // Exactly one replica: health checks and SLA escalation run on in-process timers.
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

resource web 'Microsoft.App/containerApps@2024-03-01' = if (deployApps) {
  name: webName
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 80
        transport: 'http'
        allowInsecure: false
        // Must be listed on every deploy, or ARM removes a binding made by custom-domain.ps1.
        customDomains: empty(customDomainCertificateId) ? [] : [
          {
            name: customDomain
            certificateId: customDomainCertificateId
            bindingType: 'SniEnabled'
          }
        ]
      }
      registries: registry
      secrets: [
        { name: 'acr-password', value: acr.listCredentials().passwords[0].value }
      ]
    }
    template: {
      containers: [
        {
          name: 'web'
          image: webImage
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          env: [
            { name: 'BACKEND_URL', value: 'http://${apiName}' }
          ]
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

// Nightly safety net: stops both apps and the database in case a demo was left running,
// and catches Azure's automatic restart of a Postgres server that has been stopped for 7 days.
resource autoStop 'Microsoft.App/jobs@2024-03-01' = if (deployApps) {
  name: '${prefix}-autostop'
  location: location
  identity: { type: 'SystemAssigned' }
  properties: {
    environmentId: env.id
    configuration: {
      triggerType: 'Schedule'
      replicaTimeout: 900
      replicaRetryLimit: 1
      scheduleTriggerConfig: {
        cronExpression: autoStopCron
        parallelism: 1
        replicaCompletionCount: 1
      }
    }
    template: {
      containers: [
        {
          name: 'stop'
          image: 'mcr.microsoft.com/azure-cli:latest'
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          command: ['/bin/bash', '-c']
          args: [
            'az login --identity --output none && v=$(az provider show -n Microsoft.App --query "resourceTypes[?resourceType==\'containerApps\'].apiVersions[0]" -o tsv) && for id in "$API_ID" "$WEB_ID"; do az rest --method post --url "\${ARM_URL%/}$id/stop?api-version=$v" || true; done; az postgres flexible-server stop --ids "$PG_ID" || true'
          ]
          env: [
            { name: 'ARM_URL', value: environment().resourceManager }
            { name: 'API_ID', value: api.id }
            { name: 'WEB_ID', value: web.id }
            { name: 'PG_ID', value: pg.id }
          ]
        }
      ]
    }
  }
}

var contributorRoleId = 'b24988ac-6180-42a0-ab88-20f7382dd24c'

resource autoStopRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (deployApps) {
  name: guid(resourceGroup().id, '${prefix}-autostop', contributorRoleId)
  properties: {
    principalId: autoStop!.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', contributorRoleId)
  }
}

output acrName string = acr.name
output acrLoginServer string = acr.properties.loginServer
output postgresName string = pg.name
output apiName string = apiName
output webName string = webName
output webUrl string = deployApps ? 'https://${empty(customDomainCertificateId) ? web!.properties.configuration.ingress.fqdn : customDomain}' : ''
