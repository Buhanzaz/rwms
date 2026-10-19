[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$migrationTools = Join-Path $repositoryRoot 'tools\migration'
$releaseRoot = Join-Path $PSScriptRoot 'releases'
$containerName = "rwms-auth-release-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "rwms-auth-release-$([Guid]::NewGuid().ToString('N'))"
$previousPgUser = $env:PGUSER
$previousPgDatabase = $env:PGDATABASE

Import-Module (Join-Path $migrationTools 'MigrationTools.psm1') -Force

function Invoke-Runner {
    param([Parameter(Mandatory)][string] $Root)
    & (Join-Path $migrationTools 'Apply-SchemaReleases.ps1') `
        -ServiceName auth-service `
        -ReleaseRoot $Root `
        -DockerContainer $containerName
}

function Invoke-ExpectedFailure {
    param([Parameter(Mandatory)][scriptblock] $Command)
    try {
        & $Command
        throw 'Expected schema release command to fail.'
    }
    catch {
        if ($_.Exception.Message -eq 'Expected schema release command to fail.') {
            throw
        }
    }
}

try {
    & docker run -d --name $containerName --network none `
        -e POSTGRES_USER=auth_test `
        -e POSTGRES_PASSWORD=auth_test `
        -e POSTGRES_DB=auth_test `
        postgres:17-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to start disposable PostgreSQL.'
    }
    $ready = $false
    1..60 | ForEach-Object {
        if (-not $ready) {
            & docker exec $containerName pg_isready -U auth_test -d auth_test *> $null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
            }
            else {
                Start-Sleep -Milliseconds 250
            }
        }
    }
    if (-not $ready) {
        throw 'Disposable PostgreSQL did not become ready.'
    }

    $env:PGUSER = 'auth_test'
    $env:PGDATABASE = 'auth_test'
    Invoke-Runner -Root $releaseRoot
    Invoke-Runner -Root $releaseRoot

    $release = @(Get-ValidatedSchemaReleases -ReleaseRoot $releaseRoot)
    $history = (& docker exec $containerName psql -X -At -U auth_test -d auth_test `
        -c 'select version || ''|'' || checksum from public.rwms_schema_history order by version') -join "`n"
    $expectedHistory = ($release | ForEach-Object { "$($_.version)|$($_.checksum)" }) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $history -ne $expectedHistory) {
        throw "Unexpected auth schema history: '$history'."
    }

    $driftRoot = Join-Path $temporaryRoot 'drift'
    New-Item -ItemType Directory -Path $driftRoot -Force | Out-Null
    Copy-Item -Path (Join-Path $releaseRoot '*') -Destination $driftRoot -Recurse
    Add-Content -LiteralPath (Join-Path $driftRoot 'V0002__canonicalize-warehouse-identifiers\apply.sql') `
        -Value '-- checksum drift fixture'
    Invoke-ExpectedFailure { Invoke-Runner -Root $driftRoot }

    $rollbackRoot = Join-Path $temporaryRoot 'rollback'
    New-Item -ItemType Directory -Path $rollbackRoot -Force | Out-Null
    Copy-Item -Path (Join-Path $releaseRoot '*') -Destination $rollbackRoot -Recurse
    $failedRelease = Join-Path $rollbackRoot 'V0003__failed-verification'
    New-Item -ItemType Directory -Path $failedRelease -Force | Out-Null
    Write-Utf8NoBom -Path (Join-Path $failedRelease 'apply.sql') `
        -Content "CREATE TABLE public.must_rollback (id integer PRIMARY KEY);`n"
    Write-Utf8NoBom -Path (Join-Path $failedRelease 'verify.sql') `
        -Content "DO `$rwms`$ BEGIN RAISE EXCEPTION 'expected verification failure'; END `$rwms`$;`n"
    Write-SchemaReleaseManifest -ReleaseDirectory $failedRelease `
        -Description 'Failed verification fixture' | Out-Null
    Invoke-ExpectedFailure { Invoke-Runner -Root $rollbackRoot }

    $sentinel = (& docker exec $containerName psql -X -At -U auth_test -d auth_test `
        -c "select to_regclass('public.must_rollback') is null") -join ''
    $historyCount = (& docker exec $containerName psql -X -At -U auth_test -d auth_test `
        -c 'select count(*) from public.rwms_schema_history') -join ''
    if ($LASTEXITCODE -ne 0 -or $sentinel -ne 't' -or $historyCount -ne "$($release.Count)") {
        throw 'Failed verification did not roll back cleanly.'
    }

    Write-Output 'Auth schema release clean install, rerun, checksum drift and rollback checks passed.'
}
finally {
    if ($null -eq $previousPgUser) {
        Remove-Item Env:PGUSER -ErrorAction SilentlyContinue
    }
    else {
        $env:PGUSER = $previousPgUser
    }
    if ($null -eq $previousPgDatabase) {
        Remove-Item Env:PGDATABASE -ErrorAction SilentlyContinue
    }
    else {
        $env:PGDATABASE = $previousPgDatabase
    }
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    & docker rm -f $containerName *> $null
}
