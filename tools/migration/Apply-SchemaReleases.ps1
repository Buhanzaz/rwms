[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z][a-z0-9-]{1,62}$')]
    [string] $ServiceName,

    [Parameter(Mandatory)]
    [string] $ReleaseRoot,

    [string] $PsqlPath = 'psql',

    [string] $DockerContainer
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot 'MigrationTools.psm1') -Force

function ConvertTo-SqlLiteral {
    param([Parameter(Mandatory)][string] $Value)
    return "'$($Value.Replace("'", "''"))'"
}

function ConvertTo-PsqlIncludePath {
    param([Parameter(Mandatory)][string] $Path)
    return $Path.Replace('\', '/').Replace("'", "''")
}

function Invoke-DockerCommand {
    param([Parameter(Mandatory)][string[]] $Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE."
    }
}

function New-ReleaseDriver {
    param(
        [Parameter(Mandatory)][object[]] $Releases,
        [Parameter(Mandatory)][scriptblock] $ResolveReleasePath,
        [Parameter(Mandatory)][string] $OutputPath
    )

    $lockKey = ConvertTo-SqlLiteral -Value "rwms-schema:$ServiceName"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('\set ON_ERROR_STOP on')
    $lines.Add('\pset pager off')
    $lines.Add("SELECT pg_catalog.pg_advisory_lock(pg_catalog.hashtextextended($lockKey, 0));")
    $lines.Add('BEGIN;')
    $lines.Add(@'
CREATE TABLE IF NOT EXISTS public.rwms_schema_history (
    version varchar(64) PRIMARY KEY,
    checksum varchar(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    description varchar(255) NOT NULL
);
'@.Trim())
    $lines.Add('COMMIT;')

    foreach ($release in $Releases) {
        $version = ConvertTo-SqlLiteral -Value $release.version
        $checksum = ConvertTo-SqlLiteral -Value $release.checksum
        $description = ConvertTo-SqlLiteral -Value $release.description
        $releasePath = & $ResolveReleasePath $release
        $applyPath = ConvertTo-PsqlIncludePath -Path (Join-Path $releasePath 'apply.sql')
        $verifyPath = ConvertTo-PsqlIncludePath -Path (Join-Path $releasePath 'verify.sql')

        $lines.Add("SELECT CASE WHEN EXISTS (SELECT 1 FROM public.rwms_schema_history WHERE version = $version AND checksum <> $checksum) THEN 'true' ELSE 'false' END AS rwms_checksum_drift \gset")
        $lines.Add('\if :rwms_checksum_drift')
        $lines.Add("\echo 'Checksum drift rejected for release $($release.version).' ")
        $lines.Add("SELECT pg_catalog.pg_advisory_unlock(pg_catalog.hashtextextended($lockKey, 0));")
        $lines.Add("DO `$rwms_drift`$ BEGIN RAISE EXCEPTION 'Checksum drift rejected for release $($release.version).'; END `$rwms_drift`$;")
        $lines.Add('\endif')
        $lines.Add("SELECT CASE WHEN EXISTS (SELECT 1 FROM public.rwms_schema_history WHERE version = $version) THEN 'true' ELSE 'false' END AS rwms_already_applied \gset")
        $lines.Add('\if :rwms_already_applied')
        $lines.Add('BEGIN READ ONLY;')
        $lines.Add("\i '$verifyPath'")
        $lines.Add('COMMIT;')
        $lines.Add('\else')
        $lines.Add('BEGIN;')
        $lines.Add("\i '$applyPath'")
        $lines.Add("\i '$verifyPath'")
        $lines.Add("INSERT INTO public.rwms_schema_history(version, checksum, description) VALUES ($version, $checksum, $description);")
        $lines.Add('COMMIT;')
        $lines.Add('\endif')
    }

    $lines.Add("SELECT pg_catalog.pg_advisory_unlock(pg_catalog.hashtextextended($lockKey, 0));")
    Write-Utf8NoBom -Path $OutputPath -Content (($lines -join "`n") + "`n")
}

$resolvedReleaseRoot = (Resolve-Path -LiteralPath $ReleaseRoot).Path
$releases = @(Get-ValidatedSchemaReleases -ReleaseRoot $resolvedReleaseRoot)
$driverPath = Join-Path ([System.IO.Path]::GetTempPath()) "rwms-schema-driver-$([Guid]::NewGuid().ToString('N')).sql"
$remoteRoot = $null

try {
    if ([string]::IsNullOrWhiteSpace($DockerContainer)) {
        if ([string]::IsNullOrWhiteSpace($env:PGSERVICE) -and [string]::IsNullOrWhiteSpace($env:PGDATABASE)) {
            throw 'Set PGSERVICE or PGDATABASE through the operator environment before applying schema releases.'
        }
        if (-not (Get-Command $PsqlPath -ErrorAction SilentlyContinue)) {
            throw "psql executable was not found: '$PsqlPath'."
        }
        New-ReleaseDriver -Releases $releases -OutputPath $driverPath -ResolveReleasePath {
            param($release)
            return (Join-Path $resolvedReleaseRoot $release.directoryName)
        }
        & $PsqlPath -X -v ON_ERROR_STOP=1 -f $driverPath
        if ($LASTEXITCODE -ne 0) {
            throw "Schema release application failed with psql exit code $LASTEXITCODE."
        }
    }
    else {
        if ([string]::IsNullOrWhiteSpace($env:PGUSER) -or [string]::IsNullOrWhiteSpace($env:PGDATABASE)) {
            throw 'Docker verification mode requires PGUSER and PGDATABASE in the operator environment.'
        }
        $running = (& docker inspect --format '{{.State.Running}}' $DockerContainer 2>$null) -join ''
        if ($LASTEXITCODE -ne 0 -or $running.Trim() -ne 'true') {
            throw "Docker verification container is not running: '$DockerContainer'."
        }

        $remoteRoot = "/tmp/rwms-schema-$([Guid]::NewGuid().ToString('N'))"
        Invoke-DockerCommand -Arguments @('exec', $DockerContainer, 'mkdir', '-p', "$remoteRoot/releases")
        Invoke-DockerCommand -Arguments @('cp', "$resolvedReleaseRoot/.", "${DockerContainer}:$remoteRoot/releases")
        New-ReleaseDriver -Releases $releases -OutputPath $driverPath -ResolveReleasePath {
            param($release)
            return "$remoteRoot/releases/$($release.directoryName)"
        }
        Invoke-DockerCommand -Arguments @('cp', $driverPath, "${DockerContainer}:$remoteRoot/driver.sql")
        & docker exec $DockerContainer psql -X -v ON_ERROR_STOP=1 -U $env:PGUSER -d $env:PGDATABASE -f "$remoteRoot/driver.sql"
        if ($LASTEXITCODE -ne 0) {
            throw "Schema release application failed with psql exit code $LASTEXITCODE."
        }
    }

    Write-Output "Applied and verified $($releases.Count) schema release(s) for '$ServiceName'."
}
finally {
    Remove-Item -LiteralPath $driverPath -Force -ErrorAction SilentlyContinue
    if (-not [string]::IsNullOrWhiteSpace($remoteRoot) -and -not [string]::IsNullOrWhiteSpace($DockerContainer)) {
        & docker exec $DockerContainer rm -rf $remoteRoot *> $null
    }
}
