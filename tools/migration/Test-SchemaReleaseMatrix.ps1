[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$commands = @(@(
    Get-Command powershell -ErrorAction SilentlyContinue
    Get-Command pwsh -ErrorAction SilentlyContinue
) | Where-Object { $null -ne $_ } | Sort-Object -Property Path -Unique)

if ($commands.Count -eq 0) {
    throw 'No supported PowerShell executable is available.'
}

foreach ($command in $commands) {
    Write-Output "Testing migration tooling with '$($command.Path)'."
    & $command.Path -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Test-MigrationTools.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Migration tooling self-tests failed with '$($command.Path)'."
    }
    & $command.Path -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Test-SchemaReleaseIntegration.ps1') -PowerShellExecutable $command.Path
    if ($LASTEXITCODE -ne 0) {
        throw "Schema release integration tests failed with '$($command.Path)'."
    }
}

Write-Output "Schema release matrix passed for $($commands.Count) PowerShell host(s)."
