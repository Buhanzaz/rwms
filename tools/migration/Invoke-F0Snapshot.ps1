[CmdletBinding()]
param(
    [string] $ComposeFile,
    [string] $OutputRoot,
    [string] $TrustAnchorRoot,
    [string[]] $ComposeServices = @('auth-db', 'task-board-db')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'MigrationTools.psm1') -Force

function Invoke-Docker {
    param(
        [Parameter(Mandatory)]
        [string[]] $Arguments,

        [switch] $CaptureOutput
    )

    if ($CaptureOutput) {
        $output = & docker @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
        }
        return ($output -join "`n").Trim()
    }

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Copy-FromContainer {
    param([string] $ContainerId, [string] $SourcePath, [string] $DestinationPath)
    Invoke-Docker -Arguments @('cp', "${ContainerId}:$SourcePath", $DestinationPath)
}

function Copy-ToContainer {
    param([string] $SourcePath, [string] $ContainerId, [string] $DestinationPath)
    Invoke-Docker -Arguments @('cp', $SourcePath, "${ContainerId}:$DestinationPath")
}

function Wait-Postgres {
    param([string] $ContainerName, [string] $User, [string] $Database)
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        & docker exec $ContainerName pg_isready -U $User -d $Database *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Disposable PostgreSQL container '$ContainerName' did not become ready."
}

function Export-Inventory {
    param(
        [string] $ContainerId,
        [string] $RemoteSqlPath,
        [string] $RemoteOutputPath,
        [string] $LocalOutputPath,
        [string] $UserExpression = '"$POSTGRES_USER"',
        [string] $DatabaseExpression = '"$POSTGRES_DB"'
    )

    $command = "psql -X -qAt -v ON_ERROR_STOP=1 -U $UserExpression -d $DatabaseExpression -f '$RemoteSqlPath' > '$RemoteOutputPath'"
    Invoke-Docker -Arguments @('exec', $ContainerId, 'sh', '-ceu', $command)
    Copy-FromContainer -ContainerId $ContainerId -SourcePath $RemoteOutputPath -DestinationPath $LocalOutputPath
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $repositoryRoot 'compose.yaml'
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repositoryRoot '.rwms-migration-backups'
}
if ([string]::IsNullOrWhiteSpace($TrustAnchorRoot)) {
    $localApplicationData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($localApplicationData)) {
        throw 'TrustAnchorRoot is required when LocalApplicationData is unavailable.'
    }
    $TrustAnchorRoot = Join-Path $localApplicationData 'RWMS\migration-backup-index'
}
Assert-DisjointPathRoots -FirstPath $OutputRoot -SecondPath $TrustAnchorRoot | Out-Null
$null = New-Item -ItemType Directory -Path $TrustAnchorRoot -Force
Assert-SafePathWithinRoot -RootPath $TrustAnchorRoot -Path $TrustAnchorRoot | Out-Null
$resolvedComposeFile = (Resolve-Path -LiteralPath $ComposeFile).Path
$inventorySqlPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'inventory.sql')).Path
$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$backupRoot = Join-Path $OutputRoot $timestamp
$null = New-Item -ItemType Directory -Path $OutputRoot -Force
Assert-SafePathWithinRoot -RootPath $OutputRoot -Path $OutputRoot | Out-Null
if (Test-Path -LiteralPath $backupRoot) {
    throw "Backup path already exists: '$backupRoot'."
}
$null = New-Item -ItemType Directory -Path $backupRoot
Assert-SafePathWithinRoot -RootPath $OutputRoot -Path $backupRoot | Out-Null

$summary = [ordered]@{
    formatVersion = 1
    createdAt = [DateTimeOffset]::UtcNow.ToString('O')
    composeFile = Get-PortableRelativePath -BasePath $repositoryRoot -Path $resolvedComposeFile
    services = @()
}

try {
    foreach ($service in $ComposeServices) {
        Write-Host "Snapshotting $service..."
        $containerId = Invoke-Docker -Arguments @('compose', '-f', $resolvedComposeFile, 'ps', '-q', $service) -CaptureOutput
        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw "Compose service '$service' is not running."
        }

        $running = Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Running}}', $containerId) -CaptureOutput
        $health = Invoke-Docker -Arguments @('inspect', '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}', $containerId) -CaptureOutput
        if ($running -ne 'true' -or $health -notin @('healthy', 'none')) {
            throw "Compose service '$service' is not ready (running=$running, health=$health)."
        }

        $databaseName = Invoke-Docker -Arguments @('exec', $containerId, 'printenv', 'POSTGRES_DB') -CaptureOutput
        $databaseUser = Invoke-Docker -Arguments @('exec', $containerId, 'printenv', 'POSTGRES_USER') -CaptureOutput
        $imageName = Invoke-Docker -Arguments @('inspect', '--format', '{{.Config.Image}}', $containerId) -CaptureOutput
        if ([string]::IsNullOrWhiteSpace($databaseName) -or [string]::IsNullOrWhiteSpace($databaseUser)) {
            throw "Compose service '$service' must expose POSTGRES_DB and POSTGRES_USER."
        }

        $serviceDirectory = Join-Path $backupRoot $service
        New-Item -ItemType Directory -Path $serviceDirectory -Force | Out-Null
        $token = [Guid]::NewGuid().ToString('N')
        $remoteRoot = "/tmp/rwms-f0-$token"
        $remoteDump = "$remoteRoot.dump"
        $remoteSchema = "$remoteRoot.schema.sql"
        $remoteInventorySql = "$remoteRoot.inventory.sql"
        $remoteInventory = "$remoteRoot.inventory.json"
        $remoteInventoryAfter = "$remoteRoot.inventory-after.json"
        $cleanupEvidence = [ordered]@{
            observedAt = $null
            sourceCleanupExitCode = $null
            sourceObservationExitCode = $null
            sourceTemporaryFilesAbsent = $false
            restoreCleanupExitCode = $null
            restoreObservationExitCode = $null
            restoreContainerAbsent = $false
        }
        $cleanupEvidencePath = Join-Path $serviceDirectory 'cleanup.json'

        try {
            Copy-ToContainer -SourcePath $inventorySqlPath -ContainerId $containerId -DestinationPath $remoteInventorySql
            Export-Inventory -ContainerId $containerId -RemoteSqlPath $remoteInventorySql -RemoteOutputPath $remoteInventory -LocalOutputPath (Join-Path $serviceDirectory 'inventory.json')
            Invoke-Docker -Arguments @(
                'exec', $containerId, 'sh', '-ceu',
                "pg_dump --format=custom --no-owner --no-privileges -U `"`$POSTGRES_USER`" -d `"`$POSTGRES_DB`" -f '$remoteDump'"
            )
            Invoke-Docker -Arguments @(
                'exec', $containerId, 'sh', '-ceu',
                "pg_dump --schema-only --no-owner --no-privileges -U `"`$POSTGRES_USER`" -d `"`$POSTGRES_DB`" -f '$remoteSchema'"
            )
            Copy-FromContainer -ContainerId $containerId -SourcePath $remoteDump -DestinationPath (Join-Path $serviceDirectory 'database.dump')
            Copy-FromContainer -ContainerId $containerId -SourcePath $remoteSchema -DestinationPath (Join-Path $serviceDirectory 'schema.sql')
            Export-Inventory -ContainerId $containerId -RemoteSqlPath $remoteInventorySql -RemoteOutputPath $remoteInventoryAfter -LocalOutputPath (Join-Path $serviceDirectory 'inventory.source-after.json')
        }
        finally {
            & docker exec $containerId rm -f $remoteDump $remoteSchema $remoteInventorySql $remoteInventory $remoteInventoryAfter *> $null
            $cleanupEvidence.sourceCleanupExitCode = $LASTEXITCODE
            $remainingSourceFiles = @(& docker exec $containerId sh -ceu 'for item in "$@"; do if [ -e "$item" ]; then printf "%s\n" "$item"; fi; done' 'rwms-cleanup-check' $remoteDump $remoteSchema $remoteInventorySql $remoteInventory $remoteInventoryAfter 2>$null)
            $cleanupEvidence.sourceObservationExitCode = $LASTEXITCODE
            $cleanupEvidence.sourceTemporaryFilesAbsent = $cleanupEvidence.sourceObservationExitCode -eq 0 -and $remainingSourceFiles.Count -eq 0
            $cleanupEvidence.observedAt = [DateTimeOffset]::UtcNow.ToString('O')
            Write-Utf8NoBom -Path $cleanupEvidencePath -Content ($cleanupEvidence | ConvertTo-Json -Depth 4)
            Assert-CleanupObserved `
                -Resource "$service source /tmp files" `
                -CleanupExitCode $cleanupEvidence.sourceCleanupExitCode `
                -ObservationExitCode $cleanupEvidence.sourceObservationExitCode `
                -Absent $cleanupEvidence.sourceTemporaryFilesAbsent | Out-Null
        }

        $sourceStable = Compare-InventoryFiles `
            -ExpectedPath (Join-Path $serviceDirectory 'inventory.json') `
            -ActualPath (Join-Path $serviceDirectory 'inventory.source-after.json')
        if (-not $sourceStable.matches) {
            throw "Source inventory for '$service' changed during snapshot. Stop writers and retry."
        }

        New-JpaCompatibilityFixture `
            -InventoryPath (Join-Path $serviceDirectory 'inventory.json') `
            -SchemaPath (Join-Path $serviceDirectory 'schema.sql') `
            -OutputPath (Join-Path $serviceDirectory 'jpa-compatibility-fixture.json') | Out-Null

        $restoreContainer = "rwms-f0-restore-$($service.Replace('-db', ''))-$($token.Substring(0, 8))"
        $restoreUser = 'rwms_restore'
        $restoreDatabase = 'rwms_restore'
        $restoreStarted = $false
        try {
            Invoke-Docker -Arguments @(
                'run', '--detach', '--rm', '--network', 'none', '--name', $restoreContainer,
                '--env', 'POSTGRES_HOST_AUTH_METHOD=trust',
                '--env', "POSTGRES_USER=$restoreUser",
                '--env', "POSTGRES_DB=$restoreDatabase",
                $imageName
            ) | Out-Null
            $restoreStarted = $true
            Wait-Postgres -ContainerName $restoreContainer -User $restoreUser -Database $restoreDatabase
            Copy-ToContainer -SourcePath (Join-Path $serviceDirectory 'database.dump') -ContainerId $restoreContainer -DestinationPath '/tmp/database.dump'
            Copy-ToContainer -SourcePath $inventorySqlPath -ContainerId $restoreContainer -DestinationPath '/tmp/inventory.sql'
            Invoke-Docker -Arguments @(
                'exec', $restoreContainer,
                'pg_restore', '--exit-on-error', '--no-owner', '--no-privileges',
                '-U', $restoreUser, '-d', $restoreDatabase, '/tmp/database.dump'
            )
            Export-Inventory `
                -ContainerId $restoreContainer `
                -RemoteSqlPath '/tmp/inventory.sql' `
                -RemoteOutputPath '/tmp/inventory.restored.json' `
                -LocalOutputPath (Join-Path $serviceDirectory 'inventory.restored.json') `
                -UserExpression $restoreUser `
                -DatabaseExpression $restoreDatabase
            Invoke-Docker -Arguments @(
                'exec', $restoreContainer,
                'pg_dump', '--schema-only', '--no-owner', '--no-privileges',
                '-U', $restoreUser, '-d', $restoreDatabase, '-f', '/tmp/schema.restored.sql'
            )
            Copy-FromContainer -ContainerId $restoreContainer -SourcePath '/tmp/schema.restored.sql' -DestinationPath (Join-Path $serviceDirectory 'schema.restored.sql')
        }
        finally {
            & docker rm -f $restoreContainer *> $null
            $rawRestoreCleanupExitCode = $LASTEXITCODE
            $containerNames = @(& docker ps -a --format '{{.Names}}' 2>$null)
            $cleanupEvidence.restoreObservationExitCode = $LASTEXITCODE
            $cleanupEvidence.restoreContainerAbsent = $cleanupEvidence.restoreObservationExitCode -eq 0 -and $containerNames -notcontains $restoreContainer
            $cleanupEvidence.restoreCleanupExitCode = if (-not $restoreStarted -and $cleanupEvidence.restoreContainerAbsent) { 0 } else { $rawRestoreCleanupExitCode }
            $cleanupEvidence.observedAt = [DateTimeOffset]::UtcNow.ToString('O')
            Write-Utf8NoBom -Path $cleanupEvidencePath -Content ($cleanupEvidence | ConvertTo-Json -Depth 4)
            Assert-CleanupObserved `
                -Resource "$service disposable restore container" `
                -CleanupExitCode $cleanupEvidence.restoreCleanupExitCode `
                -ObservationExitCode $cleanupEvidence.restoreObservationExitCode `
                -Absent $cleanupEvidence.restoreContainerAbsent | Out-Null
        }

        $inventoryComparison = Compare-InventoryFiles `
            -ExpectedPath (Join-Path $serviceDirectory 'inventory.json') `
            -ActualPath (Join-Path $serviceDirectory 'inventory.restored.json')
        $sourceSchemaHash = Get-NormalizedSchemaSha256 -Path (Join-Path $serviceDirectory 'schema.sql')
        $restoredSchemaHash = Get-NormalizedSchemaSha256 -Path (Join-Path $serviceDirectory 'schema.restored.sql')
        $verification = [ordered]@{
            verifiedAt = [DateTimeOffset]::UtcNow.ToString('O')
            sourceInventoryStable = $sourceStable.matches
            restoredInventoryMatches = $inventoryComparison.matches
            sourceInventorySha256 = $inventoryComparison.expectedSha256
            restoredInventorySha256 = $inventoryComparison.actualSha256
            normalizedSchemaMatches = $sourceSchemaHash -eq $restoredSchemaHash
            sourceNormalizedSchemaSha256 = $sourceSchemaHash
            restoredNormalizedSchemaSha256 = $restoredSchemaHash
            sourceTemporaryFilesAbsent = $cleanupEvidence.sourceTemporaryFilesAbsent
            restoreContainerAbsent = $cleanupEvidence.restoreContainerAbsent
        }
        Write-Utf8NoBom -Path (Join-Path $serviceDirectory 'verification.json') -Content ($verification | ConvertTo-Json -Depth 5)
        Assert-BackupLayout -ServiceBackupPath $serviceDirectory

        if (-not $inventoryComparison.matches -or $sourceSchemaHash -ne $restoredSchemaHash -or -not $cleanupEvidence.sourceTemporaryFilesAbsent -or -not $cleanupEvidence.restoreContainerAbsent) {
            throw "Restore verification failed for '$service'. Inspect '$serviceDirectory'."
        }

        $summary.services += [ordered]@{
            composeService = $service
            database = $databaseName
            image = $imageName
            sourceContainer = $containerId
            verification = 'PASSED'
        }
    }

    $summary.completedAt = [DateTimeOffset]::UtcNow.ToString('O')
    $summary.status = 'PASSED'
    Write-Utf8NoBom -Path (Join-Path $backupRoot 'summary.json') -Content ($summary | ConvertTo-Json -Depth 7)
    $manifestPath = Write-ChecksumManifest -RootPath $backupRoot
    $manifestVerification = Test-ChecksumManifest -RootPath $backupRoot
    if (-not $manifestVerification.matches) {
        throw "Backup manifest verification failed: $($manifestVerification.errors -join '; ')"
    }
    $manifestSha256 = Get-Sha256Lower -Path $manifestPath
    $trustAnchorPath = Join-Path $TrustAnchorRoot "$timestamp.manifest.sha256.json"
    if (Test-Path -LiteralPath $trustAnchorPath) {
        throw "Detached trust anchor already exists: '$trustAnchorPath'."
    }
    $trustAnchor = [ordered]@{
        formatVersion = 1
        backupId = $timestamp
        manifestSha256 = $manifestSha256
        recordedAt = [DateTimeOffset]::UtcNow.ToString('O')
    }
    $temporaryTrustAnchorPath = "$trustAnchorPath.tmp-$([Guid]::NewGuid().ToString('N'))"
    try {
        Write-Utf8NoBom -Path $temporaryTrustAnchorPath -Content ($trustAnchor | ConvertTo-Json -Depth 4)
        Move-Item -LiteralPath $temporaryTrustAnchorPath -Destination $trustAnchorPath
    }
    finally {
        Remove-Item -LiteralPath $temporaryTrustAnchorPath -Force -ErrorAction SilentlyContinue
    }
    Assert-SafePathWithinRoot -RootPath $TrustAnchorRoot -Path $trustAnchorPath | Out-Null
    Write-Output "F0 snapshot and disposable restore verification passed: $backupRoot"
    Write-Output "Detached manifest SHA-256: $manifestSha256"
    Write-Output "Detached trust anchor: $trustAnchorPath"
}
catch {
    $summary.completedAt = [DateTimeOffset]::UtcNow.ToString('O')
    $summary.status = 'FAILED'
    $summary.error = $_.Exception.Message
    Write-Utf8NoBom -Path (Join-Path $backupRoot 'summary.json') -Content ($summary | ConvertTo-Json -Depth 7)
    Write-ChecksumManifest -RootPath $backupRoot | Out-Null
    throw
}
