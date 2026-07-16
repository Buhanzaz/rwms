[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'MigrationTools.psm1') -Force

function Assert-Equal {
    param(
        [Parameter(Mandatory)] $Expected,
        [Parameter(Mandatory)] $Actual,
        [Parameter(Mandatory)] [string] $Message
    )

    if ($Expected -ne $Actual) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "rwms-migration-tools-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $testRoot | Out-Null

try {
    Write-Utf8NoBom -Path (Join-Path $testRoot 'alpha.txt') -Content 'alpha'
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'nested') | Out-Null
    Write-Utf8NoBom -Path (Join-Path $testRoot 'nested/beta.txt') -Content 'beta'

    $manifestPath = Write-ChecksumManifest -RootPath $testRoot
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    Assert-Equal 2 $manifest.files.Count 'Manifest must contain every artifact except itself.'
    Assert-Equal 'alpha.txt' $manifest.files[0].path 'Manifest paths must be canonical and sorted.'
    Assert-Equal 'nested/beta.txt' $manifest.files[1].path 'Nested path separators must be portable.'
    $manifestResult = Test-ChecksumManifest -RootPath $testRoot
    Assert-Equal $true $manifestResult.matches 'A fresh manifest must verify.'

    Write-Utf8NoBom -Path (Join-Path $testRoot 'nested/beta.txt') -Content 'tampered'
    $tamperedManifestResult = Test-ChecksumManifest -RootPath $testRoot
    Assert-Equal $false $tamperedManifestResult.matches 'Manifest verification must detect tampering.'
    Write-Utf8NoBom -Path (Join-Path $testRoot 'nested/beta.txt') -Content 'beta'

    $same = Compare-InventoryFiles -ExpectedPath (Join-Path $testRoot 'alpha.txt') -ActualPath (Join-Path $testRoot 'alpha.txt')
    Assert-Equal $true $same.matches 'Equal inventories must match.'
    $different = Compare-InventoryFiles -ExpectedPath (Join-Path $testRoot 'alpha.txt') -ActualPath (Join-Path $testRoot 'nested/beta.txt')
    Assert-Equal $false $different.matches 'Different inventories must not match.'

    $inventory = [ordered]@{
        tables = @(
            [ordered]@{ schema_name = 'public'; table_name = 'databasechangelog' },
            [ordered]@{ schema_name = 'public'; table_name = 'users' }
        )
        columns = @(
            [ordered]@{
                schema_name = 'public'; table_name = 'users'; ordinal_position = 1
                column_name = 'id'; data_type = 'uuid'; nullable = $false; default_expression = $null
            }
        )
    }
    $inventoryPath = Join-Path $testRoot 'inventory.json'
    $schemaPath = Join-Path $testRoot 'schema.sql'
    $fixturePath = Join-Path $testRoot 'fixture.json'
    Write-Utf8NoBom -Path $inventoryPath -Content ($inventory | ConvertTo-Json -Depth 6 -Compress)
    Write-Utf8NoBom -Path $schemaPath -Content "\restrict random-token`nCREATE TABLE users(id uuid);`n\unrestrict random-token"
    New-JpaCompatibilityFixture -InventoryPath $inventoryPath -SchemaPath $schemaPath -OutputPath $fixturePath | Out-Null
    $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
    Assert-Equal 'public.databasechangelog' $fixture.retainedLiquibaseTables[0] 'Fixture must preserve Liquibase evidence.'
    Assert-Equal 'public.users' $fixture.tables[1].table 'Fixture must expose table/column evidence for JPA validation.'

    $releaseRoot = Join-Path $testRoot 'release-root'
    $releaseTwo = Join-Path $releaseRoot 'V0002__second-release'
    $releaseOne = Join-Path $releaseRoot 'V0001__first-release'
    New-Item -ItemType Directory -Path $releaseTwo -Force | Out-Null
    New-Item -ItemType Directory -Path $releaseOne -Force | Out-Null
    foreach ($releaseDirectory in @($releaseOne, $releaseTwo)) {
        Write-Utf8NoBom -Path (Join-Path $releaseDirectory 'apply.sql') -Content 'SELECT 1;'
        Write-Utf8NoBom -Path (Join-Path $releaseDirectory 'verify.sql') -Content 'SELECT 1;'
        Write-SchemaReleaseManifest -ReleaseDirectory $releaseDirectory -Description (Split-Path $releaseDirectory -Leaf) | Out-Null
    }
    $validatedReleases = @(Get-ValidatedSchemaReleases -ReleaseRoot $releaseRoot)
    Assert-Equal '0001' $validatedReleases[0].version 'Schema releases must be ordered numerically.'
    Assert-Equal '0002' $validatedReleases[1].version 'Schema releases must be ordered numerically.'

    Write-Utf8NoBom -Path (Join-Path $releaseOne 'apply.sql') -Content 'SELECT 2;'
    $checksumMismatchRejected = $false
    try {
        Get-ValidatedSchemaReleases -ReleaseRoot $releaseRoot | Out-Null
    }
    catch {
        $checksumMismatchRejected = $_.Exception.Message -like '*checksum mismatch*'
    }
    Assert-Equal $true $checksumMismatchRejected 'Local release checksum drift must be rejected before database access.'

    $cleanupPassed = Assert-CleanupObserved -Resource 'mock-cleanup' -CleanupExitCode 0 -ObservationExitCode 0 -Absent $true
    Assert-Equal $true $cleanupPassed 'Observed cleanup success must pass.'
    $cleanupFailureRejected = $false
    try {
        Assert-CleanupObserved -Resource 'mock-cleanup' -CleanupExitCode 0 -ObservationExitCode 0 -Absent $false | Out-Null
    }
    catch {
        $cleanupFailureRejected = $_.Exception.Message -like '*not proven*'
    }
    Assert-Equal $true $cleanupFailureRejected 'Unproven cleanup must fail closed.'

    $detachedHash = Get-Sha256Lower -Path $manifestPath
    Assert-Equal $detachedHash (Assert-DetachedManifestHash -ManifestPath $manifestPath -ExpectedSha256 $detachedHash) 'Detached manifest hash must validate.'
    $detachedMismatchRejected = $false
    try {
        Assert-DetachedManifestHash -ManifestPath $manifestPath -ExpectedSha256 ('0' * 64) | Out-Null
    }
    catch {
        $detachedMismatchRejected = $_.Exception.Message -like '*mismatch*'
    }
    Assert-Equal $true $detachedMismatchRejected 'Detached manifest mismatch must fail closed.'

    $outsideRoot = Join-Path $testRoot 'outside-root'
    New-Item -ItemType Directory -Path $outsideRoot | Out-Null
    $pathEscapeRejected = $false
    try {
        Assert-SafePathWithinRoot -RootPath $releaseRoot -Path $outsideRoot | Out-Null
    }
    catch {
        $pathEscapeRejected = $_.Exception.Message -like '*escapes*'
    }
    Assert-Equal $true $pathEscapeRejected 'Canonical path escape must be rejected.'

    $escapeManifestRoot = Join-Path $testRoot 'escape-manifest-root'
    New-Item -ItemType Directory -Path $escapeManifestRoot | Out-Null
    $escapeManifest = [ordered]@{
        algorithm = 'SHA-256'
        files = @([ordered]@{ path = '../outside-root/payload.txt'; bytes = 1; sha256 = ('0' * 64) })
    }
    Write-Utf8NoBom -Path (Join-Path $escapeManifestRoot 'manifest.sha256.json') -Content ($escapeManifest | ConvertTo-Json -Depth 5)
    $escapeManifestResult = Test-ChecksumManifest -RootPath $escapeManifestRoot
    Assert-Equal $false $escapeManifestResult.matches 'Manifest canonical path escape must be rejected.'

    $overlapRejected = $false
    try {
        Assert-DisjointPathRoots -FirstPath $releaseRoot -SecondPath (Join-Path $releaseRoot 'anchors') | Out-Null
    }
    catch {
        $overlapRejected = $_.Exception.Message -like '*disjoint*'
    }
    Assert-Equal $true $overlapRejected 'Backup and trust-anchor roots must not overlap.'

    $junctionRoot = Join-Path $testRoot 'junction-root'
    $junctionTarget = Join-Path $testRoot 'junction-target'
    $junctionRelease = Join-Path $junctionRoot 'V0001__linked-release'
    New-Item -ItemType Directory -Path $junctionRoot, $junctionTarget | Out-Null
    Write-Utf8NoBom -Path (Join-Path $junctionTarget 'apply.sql') -Content 'SELECT 1;'
    Write-Utf8NoBom -Path (Join-Path $junctionTarget 'verify.sql') -Content 'SELECT 1;'
    $linkCreated = $false
    try {
        if ([System.IO.Path]::DirectorySeparatorChar -eq '\') {
            & cmd /c "mklink /J `"$junctionRelease`" `"$junctionTarget`"" *> $null
            $linkCreated = $LASTEXITCODE -eq 0
        }
        else {
            New-Item -ItemType SymbolicLink -Path $junctionRelease -Target $junctionTarget | Out-Null
            $linkCreated = $true
        }
        if (-not $linkCreated) {
            throw 'Could not create a junction/symlink for the release safety test.'
        }
        $linkRejected = $false
        try {
            Get-ValidatedSchemaReleases -ReleaseRoot $junctionRoot | Out-Null
        }
        catch {
            $linkRejected = $_.Exception.Message -like '*forbidden*'
        }
        Assert-Equal $true $linkRejected 'Junction/symlink release directories must be rejected.'

        $manifestPayload = Join-Path $junctionTarget 'payload.txt'
        Write-Utf8NoBom -Path $manifestPayload -Content 'payload'
        $linkedManifest = [ordered]@{
            algorithm = 'SHA-256'
            files = @([ordered]@{
                path = 'V0001__linked-release/payload.txt'
                bytes = (Get-Item -LiteralPath $manifestPayload).Length
                sha256 = Get-Sha256Lower -Path $manifestPayload
            })
        }
        Write-Utf8NoBom -Path (Join-Path $junctionRoot 'manifest.sha256.json') -Content ($linkedManifest | ConvertTo-Json -Depth 5)
        $linkedManifestResult = Test-ChecksumManifest -RootPath $junctionRoot
        Assert-Equal $false $linkedManifestResult.matches 'Manifest entries through a junction/symlink must be rejected.'
    }
    finally {
        if ($linkCreated -and (Test-Path -LiteralPath $junctionRelease)) {
            if ([System.IO.Path]::DirectorySeparatorChar -eq '\') {
                & cmd /c "rmdir `"$junctionRelease`"" *> $null
            }
            else {
                Remove-Item -LiteralPath $junctionRelease -Force
            }
        }
    }

    Write-Output 'Migration tooling self-tests passed.'
}
finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
