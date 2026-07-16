[CmdletBinding()]
param(
    [string] $PowerShellExecutable
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'MigrationTools.psm1') -Force

if ([string]::IsNullOrWhiteSpace($PowerShellExecutable)) {
    $PowerShellExecutable = (Get-Process -Id $PID).Path
}
$resolvedPowerShell = Get-Command $PowerShellExecutable -ErrorAction Stop
$script:powerShellExecutable = $resolvedPowerShell.Path

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

function Invoke-DatabaseQuery {
    param([Parameter(Mandatory)][string] $Sql)
    $output = & docker exec $script:containerName psql -X -qAt -v ON_ERROR_STOP=1 -U $env:PGUSER -d $env:PGDATABASE -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'Disposable PostgreSQL verification query failed.'
    }
    return ($output -join "`n").Trim()
}

function Add-TestRelease {
    param(
        [Parameter(Mandatory)][string] $DirectoryName,
        [Parameter(Mandatory)][string] $Description,
        [Parameter(Mandatory)][string] $ApplySql,
        [Parameter(Mandatory)][string] $VerifySql
    )
    $directory = Join-Path $script:releaseRoot $DirectoryName
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Write-Utf8NoBom -Path (Join-Path $directory 'apply.sql') -Content $ApplySql
    Write-Utf8NoBom -Path (Join-Path $directory 'verify.sql') -Content $VerifySql
    Write-SchemaReleaseManifest -ReleaseDirectory $directory -Description $Description | Out-Null
}

function Invoke-ReleaseRunner {
    param([switch] $ExpectFailure)
    & $script:powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Apply-SchemaReleases.ps1') `
        -ServiceName 'integration-test' `
        -ReleaseRoot $script:releaseRoot `
        -DockerContainer $script:containerName
    $exitCode = $LASTEXITCODE
    if ($ExpectFailure -and $exitCode -eq 0) {
        throw 'Schema release runner unexpectedly succeeded.'
    }
    if (-not $ExpectFailure -and $exitCode -ne 0) {
        throw "Schema release runner failed with exit code $exitCode."
    }
}

$script:containerName = "rwms-schema-release-test-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$script:releaseRoot = Join-Path ([System.IO.Path]::GetTempPath()) "rwms-schema-releases-$([Guid]::NewGuid().ToString('N'))"
$previousPgUser = $env:PGUSER
$previousPgDatabase = $env:PGDATABASE
New-Item -ItemType Directory -Path $script:releaseRoot | Out-Null

try {
    & docker run --detach --rm --network none --name $script:containerName `
        --env POSTGRES_HOST_AUTH_METHOD=trust `
        --env POSTGRES_USER=rwms_release_test `
        --env POSTGRES_DB=rwms_release_test `
        postgres:17-alpine *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start disposable PostgreSQL for schema release tests.'
    }
    $env:PGUSER = 'rwms_release_test'
    $env:PGDATABASE = 'rwms_release_test'

    for ($attempt = 1; $attempt -le 60; $attempt++) {
        & docker exec $script:containerName pg_isready -U $env:PGUSER -d $env:PGDATABASE *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ($attempt -eq 60) { throw 'Disposable PostgreSQL did not become ready.' }
        Start-Sleep -Milliseconds 500
    }

    Add-TestRelease `
        -DirectoryName 'V0001__create-widget' `
        -Description 'Create widget table' `
        -ApplySql "CREATE TABLE public.widget (id bigint PRIMARY KEY);`nINSERT INTO public.widget(id) VALUES (1);`n" `
        -VerifySql "DO `$verify`$ BEGIN IF (SELECT count(*) FROM public.widget) <> 1 THEN RAISE EXCEPTION 'widget verification failed'; END IF; END `$verify`$;`n"

    Invoke-ReleaseRunner
    Assert-Equal '0001' (Invoke-DatabaseQuery -Sql 'SELECT version FROM public.rwms_schema_history ORDER BY version;') 'Clean install must apply the first release.'
    Assert-Equal '1' (Invoke-DatabaseQuery -Sql 'SELECT count(*) FROM public.widget;') 'Clean install must create fixture data.'

    Add-TestRelease `
        -DirectoryName 'V0002__add-widget-name' `
        -Description 'Add widget name' `
        -ApplySql "ALTER TABLE public.widget ADD COLUMN name text;`nUPDATE public.widget SET name = 'first';`nALTER TABLE public.widget ALTER COLUMN name SET NOT NULL;`n" `
        -VerifySql "DO `$verify`$ BEGIN IF (SELECT name FROM public.widget WHERE id = 1) <> 'first' THEN RAISE EXCEPTION 'upgrade verification failed'; END IF; END `$verify`$;`n"

    Invoke-ReleaseRunner
    Assert-Equal '0001,0002' (Invoke-DatabaseQuery -Sql "SELECT string_agg(version, ',' ORDER BY version) FROM public.rwms_schema_history;") 'Upgrade must apply releases in order.'
    $historyBeforeRerun = Invoke-DatabaseQuery -Sql "SELECT string_agg(version || ':' || checksum || ':' || applied_at::text, ',' ORDER BY version) FROM public.rwms_schema_history;"
    Invoke-ReleaseRunner
    $historyAfterRerun = Invoke-DatabaseQuery -Sql "SELECT string_agg(version || ':' || checksum || ':' || applied_at::text, ',' ORDER BY version) FROM public.rwms_schema_history;"
    Assert-Equal $historyBeforeRerun $historyAfterRerun 'Idempotent rerun must not rewrite schema history.'

    $lockJob = Start-Job -ScriptBlock {
        param($Container, $User, $Database)
        & docker exec $Container psql -X -qAt -v ON_ERROR_STOP=1 -U $User -d $Database -c "SELECT pg_advisory_lock(hashtextextended('rwms-schema:integration-test', 0)); SELECT pg_sleep(3);"
        if ($LASTEXITCODE -ne 0) { throw 'Advisory-lock holder failed.' }
    } -ArgumentList $script:containerName, $env:PGUSER, $env:PGDATABASE
    try {
        for ($attempt = 1; $attempt -le 30; $attempt++) {
            $heldLocks = Invoke-DatabaseQuery -Sql "SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype = 'advisory' AND granted;"
            if ([int] $heldLocks -gt 0) { break }
            if ($attempt -eq 30) { throw 'Test advisory lock was not acquired.' }
            Start-Sleep -Milliseconds 100
        }
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-ReleaseRunner
        $stopwatch.Stop()
        if ($stopwatch.Elapsed.TotalSeconds -lt 1.0) {
            throw 'Schema runner did not wait for the service advisory lock.'
        }
    }
    finally {
        Wait-Job -Job $lockJob -Timeout 10 | Out-Null
        Receive-Job -Job $lockJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job -Job $lockJob -Force -ErrorAction SilentlyContinue
    }

    $releaseOne = Join-Path $script:releaseRoot 'V0001__create-widget'
    Write-Utf8NoBom -Path (Join-Path $releaseOne 'apply.sql') -Content "CREATE TABLE public.widget (id bigint PRIMARY KEY, drift text);`n"
    Write-SchemaReleaseManifest -ReleaseDirectory $releaseOne -Description 'Create widget table' | Out-Null
    Invoke-ReleaseRunner -ExpectFailure
    Assert-Equal '0001,0002' (Invoke-DatabaseQuery -Sql "SELECT string_agg(version, ',' ORDER BY version) FROM public.rwms_schema_history;") 'Checksum drift must not alter history.'

    Write-Utf8NoBom -Path (Join-Path $releaseOne 'apply.sql') -Content "CREATE TABLE public.widget (id bigint PRIMARY KEY);`nINSERT INTO public.widget(id) VALUES (1);`n"
    Write-SchemaReleaseManifest -ReleaseDirectory $releaseOne -Description 'Create widget table' | Out-Null
    Add-TestRelease `
        -DirectoryName 'V0003__verify-failure' `
        -Description 'Prove failed verification rollback' `
        -ApplySql "CREATE TABLE public.must_rollback (id bigint PRIMARY KEY);`n" `
        -VerifySql "SELECT 1 / 0;`n"

    Invoke-ReleaseRunner -ExpectFailure
    Assert-Equal '' (Invoke-DatabaseQuery -Sql "SELECT to_regclass('public.must_rollback')::text;") 'Failed verify must roll back release DDL.'
    Assert-Equal '0' (Invoke-DatabaseQuery -Sql "SELECT count(*) FROM public.rwms_schema_history WHERE version = '0003';") 'Failed verify must not record schema history.'

    Write-Output "Schema release integration tests passed with $($PSVersionTable.PSEdition) $($PSVersionTable.PSVersion)."
}
finally {
    if ($null -eq $previousPgUser) { Remove-Item Env:PGUSER -ErrorAction SilentlyContinue } else { $env:PGUSER = $previousPgUser }
    if ($null -eq $previousPgDatabase) { Remove-Item Env:PGDATABASE -ErrorAction SilentlyContinue } else { $env:PGDATABASE = $previousPgDatabase }
    & docker rm -f $script:containerName *> $null
    Remove-Item -LiteralPath $script:releaseRoot -Recurse -Force -ErrorAction SilentlyContinue
}
