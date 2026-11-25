// PulseDesk on Azure Container Apps, sized for on-demand demos: nothing but a tiny file share is billed while stopped.
// Images come from GitHub Container Registry (published by CI); deploy with scripts/azure/deploy.ps1.

@description('Azure region for every resource.')
param location string = resourceGroup().location

@description('Short prefix used in resource names.')
@minLength(3)
@maxLength(9)
param prefix string = 'pulsedesk'

@description('Public backend image, e.g. ghcr.io/ansh5025/pulsedesk-api:<commit sha>.')
param apiImage string

@description('Public frontend image.')
param webImage string

@description('JWT signing secret. Regenerated on each deployment, which signs everyone out.')
@secure()
param jwtSecret string = '${newGuid()}${newGuid()}'

@description('Database password. Must stay the same across deploys: Postgres only applies it when the data directory is first created.')
@secure()
param dbPassword string

@description('UTC cron for the nightly safety stop of all apps. Default is 01:00 IST.')
param autoStopCron string = '30 19 * * *'

@description('Custom domain for the frontend, e.g. pulsedesk.anshrai.in. Bind it first with scripts/azure/custom-domain.ps1.')
param customDomain string = ''

@description('Managed certificate already bound to customDomain. deploy.ps1 looks this up so redeploys keep the binding.')
param customDomainCertificateId string = ''

var suffix = uniqueString(resourceGroup().id)
var apiName = '${prefix}-api'
var webName = '${prefix}-web'
var dbName = '${prefix}-db'
var dbUser = 'pulsedesk'
var publicHost = empty(customDomain) ? '${webName}.${env.properties.defaultDomain}' : customDomain

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${prefix}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

// Holds the Postgres data directory, so data survives stops and restarts. Pay-per-GB-used: a few rupees a month.
resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: '${prefix}st${suffix}'
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
  }
}

resource pgShare 'Microsoft.Storage/storageAccounts/fileServices/shares@2023-05-01' = {
  name: '${storage.name}/default/pgdata'
  properties: {
    shareQuota: 5
    accessTier: 'TransactionOptimized'
  }
}

// Newer subscriptions default to the 'Express' mode, which rejects sidecar containers (the API's Redis sidecar),
// so pin the workload-profiles mode with the standard serverless Consumption profile. Billing is the same.
resource env 'Microsoft.App/managedEnvironments@2026-07-01' = {
  name: '${prefix}-env'
  location: location
  properties: {
    environmentMode: 'WorkloadProfiles'
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

resource envStorage 'Microsoft.App/managedEnvironments/storages@2026-07-01' = {
  parent: env
  name: 'pgdata'
  dependsOn: [pgShare]
  properties: {
    azureFile: {
      accountName: storage.name
      accountKey: storage.listKeys().keys[0].value
      shareName: 'pgdata'
      accessMode: 'ReadWrite'
    }
  }
}

// Postgres 16 with its data on the file share. Internal TCP only: reachable from the API as pulsedesk-db:5432.
resource db 'Microsoft.App/containerApps@2026-07-01' = {
  name: dbName
  location: location
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false
        transport: 'tcp'
        targetPort: 5432
        exposedPort: 5432
      }
      secrets: [
        { name: 'db-password', value: dbPassword }
      ]
    }
    template: {
      // Postgres shuts down on SIGINT (the image's stop signal); allow time to checkpoint to the share.
      terminationGracePeriodSeconds: 60
      containers: [
        {
          name: 'postgres'
          image: 'docker.io/library/postgres:16-alpine'
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          env: [
            { name: 'POSTGRES_DB', value: 'pulsedesk' }
            { name: 'POSTGRES_USER', value: dbUser }
            { name: 'POSTGRES_PASSWORD', secretRef: 'db-password' }
            { name: 'PGDATA', value: '/var/lib/postgresql/data/pgdata' }
          ]
          volumeMounts: [
            { volumeName: 'pgdata', mountPath: '/var/lib/postgresql/data' }
          ]
          probes: [
            {
              type: 'Startup'
              tcpSocket: { port: 5432 }
              periodSeconds: 5
              failureThreshold: 60
            }
          ]
        }
      ]
      volumes: [
        {
          name: 'pgdata'
          storageType: 'AzureFile'
          storageName: envStorage.name
          // SMB has no Unix ownership: present everything as the image's postgres user (uid 70) with the
          // 0700/0600 modes Postgres insists on; nobrl avoids byte-range lock errors on the share.
          mountOptions: 'uid=70,gid=70,dir_mode=0700,file_mode=0600,nobrl,mfsymlinks,cache=strict'
        }
      ]
      // One writer only: two Postgres processes must never share a data directory.
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

resource api 'Microsoft.App/containerApps@2026-07-01' = {
  name: apiName
  location: location
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      // Internal only: the browser reaches the API through the frontend's /api proxy.
      ingress: {
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: true
      }
      secrets: [
        { name: 'jwt-secret', value: jwtSecret }
        { name: 'db-password', value: dbPassword }
      ]
    }
    template: {
      containers: [
        {
          name: 'api'
          image: apiImage
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: [
            { name: 'PULSEDESK_DB_URL', value: 'jdbc:postgresql://${dbName}:5432/pulsedesk' }
            { name: 'PULSEDESK_DB_USER', value: dbUser }
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
              // Generous: the database may still be starting when the API starts.
              type: 'Startup'
              httpGet: { path: '/actuator/health', port: 8080 }
              initialDelaySeconds: 20
              periodSeconds: 10
              failureThreshold: 18
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

resource web 'Microsoft.App/containerApps@2026-07-01' = {
  name: webName
  location: location
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
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

// Nightly safety net: stops every app in case a demo was left running. The database goes last so it can
// shut down cleanly after the API has disconnected.
resource autoStop 'Microsoft.App/jobs@2026-07-01' = {
  name: '${prefix}-autostop'
  location: location
  identity: { type: 'SystemAssigned' }
  properties: {
    environmentId: env.id
    workloadProfileName: 'Consumption'
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
            'az login --identity --output none && v=$(az provider show -n Microsoft.App --query "resourceTypes[?resourceType==\'containerApps\'].apiVersions[0]" -o tsv) && for id in "$WEB_ID" "$API_ID" "$DB_ID"; do az rest --method post --url "\${ARM_URL%/}$id/stop?api-version=$v" || true; done'
          ]
          env: [
            { name: 'ARM_URL', value: environment().resourceManager }
            { name: 'WEB_ID', value: web.id }
            { name: 'API_ID', value: api.id }
            { name: 'DB_ID', value: db.id }
          ]
        }
      ]
    }
  }
}

var contributorRoleId = 'b24988ac-6180-42a0-ab88-20f7382dd24c'

resource autoStopRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(resourceGroup().id, '${prefix}-autostop', contributorRoleId)
  properties: {
    principalId: autoStop.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', contributorRoleId)
  }
}

output webUrl string = 'https://${empty(customDomainCertificateId) ? web.properties.configuration.ingress.fqdn : customDomain}'
