[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $BackupPath,

    [Parameter(Mandatory)]
    [ValidateSet('auth-db', 'task-board-db')]
    [string] $ComposeService,

    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string] $ExpectedManifestSha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'MigrationTools.psm1') -Force

$resolvedBackup = (Resolve-Path -LiteralPath $BackupPath).Path
$manifestPath = Join-Path $resolvedBackup 'manifest.sha256.json'
$actualManifestSha256 = Assert-DetachedManifestHash -ManifestPath $manifestPath -ExpectedSha256 $ExpectedManifestSha256
$manifestResult = Test-ChecksumManifest -RootPath $resolvedBackup
if (-not $manifestResult.matches) {
    throw "Backup manifest validation failed: $($manifestResult.errors -join '; ')"
}
$serviceBackup = Join-Path $resolvedBackup $ComposeService
Assert-BackupLayout -ServiceBackupPath $serviceBackup

$dumpHash = Get-Sha256Lower -Path (Join-Path $serviceBackup 'database.dump')
$inventoryHash = Get-Sha256Lower -Path (Join-Path $serviceBackup 'inventory.json')

@"
RWMS F0 rollback plan (PLAN ONLY - no command was executed)

Backup:          $resolvedBackup
Compose service: $ComposeService
Dump SHA-256:    $dumpHash
Inventory SHA:   $inventoryHash
Manifest SHA:    $actualManifestSha256

Required operator gates:
1. Stop application writers and record the incident/change ticket.
2. Verify manifest.sha256.json and the hashes above.
3. Restore first into a disposable PostgreSQL instance and compare inventory.
4. Take a fresh pre-rollback backup of the current target database.
5. Obtain explicit operator approval for destructive replacement.
6. Follow tools/migration/ROLLBACK.md; do not delete databasechangelog tables.

This script intentionally does not print a password-bearing or destructive
command. Database credentials must be supplied at execution time through the
approved deployment secret mechanism.
"@
