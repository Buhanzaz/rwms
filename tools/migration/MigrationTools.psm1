Set-StrictMode -Version Latest

function Write-Utf8NoBom {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $Path,

        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string] $Content
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-PortableRelativePath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $BasePath,

        [Parameter(Mandatory)]
        [string] $Path
    )

    $baseFullPath = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $pathFullPath = [System.IO.Path]::GetFullPath($Path)
    $baseUri = New-Object System.Uri($baseFullPath)
    $pathUri = New-Object System.Uri($pathFullPath)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace('\', '/')
}

function Get-PathStringComparison {
    if ([System.IO.Path]::DirectorySeparatorChar -eq '\') {
        return [System.StringComparison]::OrdinalIgnoreCase
    }
    return [System.StringComparison]::Ordinal
}

function Assert-SafePathWithinRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $RootPath,

        [Parameter(Mandatory)]
        [string] $Path
    )

    $rootItem = Get-Item -LiteralPath $RootPath -Force -ErrorAction Stop
    $pathItem = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    $rootFullPath = [System.IO.Path]::GetFullPath($rootItem.FullName).TrimEnd('\', '/')
    $pathFullPath = [System.IO.Path]::GetFullPath($pathItem.FullName)
    $rootPrefix = $rootFullPath + [System.IO.Path]::DirectorySeparatorChar
    $comparison = Get-PathStringComparison

    if (-not $pathFullPath.Equals($rootFullPath, $comparison) -and -not $pathFullPath.StartsWith($rootPrefix, $comparison)) {
        throw "Path escapes the approved root '$rootFullPath': '$pathFullPath'."
    }

    $current = $pathItem
    while ($null -ne $current) {
        $isReparsePoint = ($current.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
        $hasLinkType = $current.PSObject.Properties.Name -contains 'LinkType' -and -not [string]::IsNullOrWhiteSpace([string] $current.LinkType)
        if ($isReparsePoint -or $hasLinkType) {
            throw "Links, junctions, and reparse points are forbidden in reviewed schema releases: '$($current.FullName)'."
        }
        if ([System.IO.Path]::GetFullPath($current.FullName).TrimEnd('\', '/').Equals($rootFullPath, $comparison)) {
            break
        }
        $current = if ($current -is [System.IO.DirectoryInfo]) { $current.Parent } else { $current.Directory }
    }
    if ($null -eq $current) {
        throw "Could not prove that '$pathFullPath' is contained by '$rootFullPath'."
    }
    return $true
}

function Assert-DisjointPathRoots {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $FirstPath,
        [Parameter(Mandatory)][string] $SecondPath
    )

    $first = [System.IO.Path]::GetFullPath($FirstPath).TrimEnd('\', '/')
    $second = [System.IO.Path]::GetFullPath($SecondPath).TrimEnd('\', '/')
    $comparison = Get-PathStringComparison
    $firstPrefix = $first + [System.IO.Path]::DirectorySeparatorChar
    $secondPrefix = $second + [System.IO.Path]::DirectorySeparatorChar
    if ($first.Equals($second, $comparison) -or $first.StartsWith($secondPrefix, $comparison) -or $second.StartsWith($firstPrefix, $comparison)) {
        throw "Trust anchor and backup roots must be disjoint: '$first' and '$second'."
    }
    return $true
}

function Assert-CleanupObserved {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Resource,
        [Parameter(Mandatory)][int] $CleanupExitCode,
        [Parameter(Mandatory)][int] $ObservationExitCode,
        [Parameter(Mandatory)][bool] $Absent
    )

    if ($CleanupExitCode -ne 0) {
        throw "Cleanup command failed for '$Resource' with exit code $CleanupExitCode."
    }
    if ($ObservationExitCode -ne 0) {
        throw "Cleanup observation failed for '$Resource' with exit code $ObservationExitCode."
    }
    if (-not $Absent) {
        throw "Cleanup was not proven for '$Resource'."
    }
    return $true
}

function Assert-DetachedManifestHash {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $ManifestPath,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string] $ExpectedSha256
    )

    $actualSha256 = Get-Sha256Lower -Path $ManifestPath
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "Detached manifest SHA-256 mismatch for '$ManifestPath'."
    }
    return $actualSha256
}

function Get-Sha256Lower {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-NormalizedSchemaSha256 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )

    $normalizedLines = foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\\(un)?restrict\s+') {
            continue
        }
        if ($line -match '^-- Dumped (from|by) database version') {
            continue
        }
        $line.TrimEnd()
    }

    $normalized = ($normalizedLines -join "`n") + "`n"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($normalized)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
    }
    finally {
        $sha256.Dispose()
    }
    return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Write-ChecksumManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $RootPath,

        [string] $OutputFileName = 'manifest.sha256.json'
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $RootPath).Path
    $entries = Get-ChildItem -LiteralPath $resolvedRoot -File -Recurse |
        Where-Object { $_.Name -ne $OutputFileName } |
        ForEach-Object {
            $relativePath = Get-PortableRelativePath -BasePath $resolvedRoot -Path $_.FullName
            [pscustomobject][ordered]@{
                path   = $relativePath
                bytes  = $_.Length
                sha256 = Get-Sha256Lower -Path $_.FullName
            }
        } |
        Sort-Object -Property path

    $manifest = [ordered]@{
        algorithm = 'SHA-256'
        generatedAt = [DateTimeOffset]::UtcNow.ToString('O')
        files = @($entries)
    }

    $manifestPath = Join-Path $resolvedRoot $OutputFileName
    Write-Utf8NoBom -Path $manifestPath -Content ($manifest | ConvertTo-Json -Depth 6)
    return $manifestPath
}

function Compare-InventoryFiles {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $ExpectedPath,

        [Parameter(Mandatory)]
        [string] $ActualPath
    )

    $expectedHash = Get-Sha256Lower -Path $ExpectedPath
    $actualHash = Get-Sha256Lower -Path $ActualPath
    return [ordered]@{
        matches = $expectedHash -eq $actualHash
        expectedSha256 = $expectedHash
        actualSha256 = $actualHash
    }
}

function Test-ChecksumManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $RootPath,

        [string] $ManifestFileName = 'manifest.sha256.json'
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $RootPath).Path
    $rootPrefix = $resolvedRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $manifestPath = Join-Path $resolvedRoot $ManifestFileName
    Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $resolvedRoot | Out-Null
    Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $manifestPath | Out-Null
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $errors = New-Object System.Collections.Generic.List[string]
    $comparison = Get-PathStringComparison

    foreach ($entry in $manifest.files) {
        $candidate = [System.IO.Path]::GetFullPath((Join-Path $resolvedRoot ($entry.path.Replace('/', [System.IO.Path]::DirectorySeparatorChar))))
        if (-not $candidate.StartsWith($rootPrefix, $comparison)) {
            $errors.Add("Manifest path escapes backup root: $($entry.path)")
            continue
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            $errors.Add("Missing artifact: $($entry.path)")
            continue
        }
        try {
            Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $candidate | Out-Null
        }
        catch {
            $errors.Add($_.Exception.Message)
            continue
        }
        $file = Get-Item -LiteralPath $candidate
        if ($file.Length -ne [long] $entry.bytes) {
            $errors.Add("Size mismatch: $($entry.path)")
        }
        if ((Get-Sha256Lower -Path $candidate) -ne $entry.sha256) {
            $errors.Add("SHA-256 mismatch: $($entry.path)")
        }
    }

    $expectedPaths = @($manifest.files | ForEach-Object { $_.path } | Sort-Object)
    $actualPaths = @(
        Get-ChildItem -LiteralPath $resolvedRoot -File -Recurse |
            Where-Object { $_.Name -ne $ManifestFileName } |
            ForEach-Object { Get-PortableRelativePath -BasePath $resolvedRoot -Path $_.FullName } |
            Sort-Object
    )
    $unexpected = @(Compare-Object -ReferenceObject $expectedPaths -DifferenceObject $actualPaths)
    foreach ($difference in $unexpected) {
        $errors.Add("Manifest file set mismatch: $($difference.InputObject) $($difference.SideIndicator)")
    }

    return [ordered]@{
        matches = $errors.Count -eq 0
        errors = @($errors)
    }
}

function New-JpaCompatibilityFixture {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $InventoryPath,

        [Parameter(Mandatory)]
        [string] $SchemaPath,

        [Parameter(Mandatory)]
        [string] $OutputPath
    )

    $inventory = Get-Content -LiteralPath $InventoryPath -Raw | ConvertFrom-Json
    $liquibaseTables = @(
        $inventory.tables |
            Where-Object { $_.table_name -in @('databasechangelog', 'databasechangeloglock') } |
            ForEach-Object { "$($_.schema_name).$($_.table_name)" } |
            Sort-Object
    )

    $tableSignatures = @(
        foreach ($table in $inventory.tables) {
            $qualifiedName = "$($table.schema_name).$($table.table_name)"
            $columns = @(
                $inventory.columns |
                    Where-Object {
                        $_.schema_name -eq $table.schema_name -and
                        $_.table_name -eq $table.table_name
                    } |
                    Sort-Object -Property ordinal_position |
                    ForEach-Object {
                        [ordered]@{
                            name = $_.column_name
                            type = $_.data_type
                            nullable = $_.nullable
                            default = $_.default_expression
                        }
                    }
            )
            [ordered]@{
                table = $qualifiedName
                columns = $columns
            }
        }
    )

    $fixture = [ordered]@{
        formatVersion = 1
        purpose = 'Liquibase-to-JPA schema compatibility evidence'
        generatedAt = [DateTimeOffset]::UtcNow.ToString('O')
        schemaSha256 = Get-Sha256Lower -Path $SchemaPath
        normalizedSchemaSha256 = Get-NormalizedSchemaSha256 -Path $SchemaPath
        inventorySha256 = Get-Sha256Lower -Path $InventoryPath
        retainedLiquibaseTables = $liquibaseTables
        tables = $tableSignatures
    }

    Write-Utf8NoBom -Path $OutputPath -Content ($fixture | ConvertTo-Json -Depth 10)
    return $OutputPath
}

function Assert-BackupLayout {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $ServiceBackupPath
    )

    $required = @(
        'database.dump',
        'schema.sql',
        'inventory.json',
        'jpa-compatibility-fixture.json',
        'cleanup.json',
        'verification.json'
    )
    $missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $ServiceBackupPath $_) -PathType Leaf) })
    if ($missing.Count -gt 0) {
        throw "Incomplete backup layout at '$ServiceBackupPath'. Missing: $($missing -join ', ')"
    }
}

function Write-SchemaReleaseManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $ReleaseDirectory,

        [Parameter(Mandatory)]
        [ValidateLength(1, 255)]
        [string] $Description
    )

    $directory = Get-Item -LiteralPath $ReleaseDirectory -ErrorAction Stop
    if (-not $directory.PSIsContainer -or $directory.Name -notmatch '^V(?<version>[0-9]{4})__[a-z0-9][a-z0-9-]*$') {
        throw "Release directory must use VNNNN__lowercase-slug: '$ReleaseDirectory'."
    }
    $version = $Matches.version
    Assert-SafePathWithinRoot -RootPath $directory.Parent.FullName -Path $directory.FullName | Out-Null
    if ($Description -match '[\x00-\x1f]') {
        throw 'Release description must not contain control characters.'
    }

    $applyPath = Join-Path $directory.FullName 'apply.sql'
    $verifyPath = Join-Path $directory.FullName 'verify.sql'
    foreach ($requiredPath in @($applyPath, $verifyPath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Missing schema release file: '$requiredPath'."
        }
        if ((Get-Item -LiteralPath $requiredPath).Length -eq 0) {
            throw "Schema release file must not be empty: '$requiredPath'."
        }
        Assert-SafePathWithinRoot -RootPath $directory.Parent.FullName -Path $requiredPath | Out-Null
    }

    $manifest = [ordered]@{
        formatVersion = 1
        version = $version
        description = $Description
        applySha256 = Get-Sha256Lower -Path $applyPath
        verifySha256 = Get-Sha256Lower -Path $verifyPath
    }
    $manifestPath = Join-Path $directory.FullName 'manifest.json'
    Write-Utf8NoBom -Path $manifestPath -Content ($manifest | ConvertTo-Json -Depth 4)
    Assert-SafePathWithinRoot -RootPath $directory.Parent.FullName -Path $manifestPath | Out-Null
    return $manifestPath
}

function Get-ValidatedSchemaReleases {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string] $ReleaseRoot
    )

    $resolvedRoot = (Resolve-Path -LiteralPath $ReleaseRoot).Path
    Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $resolvedRoot | Out-Null
    $releaseDirectories = @(Get-ChildItem -LiteralPath $resolvedRoot -Directory)
    if ($releaseDirectories.Count -eq 0) {
        throw "No schema release directories found in '$resolvedRoot'."
    }

    $invalidDirectories = @($releaseDirectories | Where-Object { $_.Name -notmatch '^V[0-9]{4}__[a-z0-9][a-z0-9-]*$' })
    if ($invalidDirectories.Count -gt 0) {
        throw "Invalid schema release directories: $($invalidDirectories.Name -join ', ')."
    }

    $releases = foreach ($directory in $releaseDirectories) {
        if ($directory.Name -notmatch '^V(?<version>[0-9]{4})__(?<slug>[a-z0-9][a-z0-9-]*)$') {
            throw "Invalid schema release directory '$($directory.Name)'."
        }
        $version = $Matches.version
        $applyPath = Join-Path $directory.FullName 'apply.sql'
        $verifyPath = Join-Path $directory.FullName 'verify.sql'
        $manifestPath = Join-Path $directory.FullName 'manifest.json'
        foreach ($requiredPath in @($applyPath, $verifyPath, $manifestPath)) {
            if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
                throw "Incomplete schema release '$($directory.Name)': missing '$([System.IO.Path]::GetFileName($requiredPath))'."
            }
            Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $requiredPath | Out-Null
        }
        Assert-SafePathWithinRoot -RootPath $resolvedRoot -Path $directory.FullName | Out-Null
        foreach ($sqlPath in @($applyPath, $verifyPath)) {
            if ((Get-Item -LiteralPath $sqlPath).Length -eq 0) {
                throw "Schema release file must not be empty: '$sqlPath'."
            }
        }

        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($manifest.formatVersion -ne 1 -or $manifest.version -ne $version) {
            throw "Manifest identity mismatch in '$($directory.Name)'."
        }
        if ([string]::IsNullOrWhiteSpace($manifest.description) -or $manifest.description.Length -gt 255 -or $manifest.description -match '[\x00-\x1f]') {
            throw "Manifest description is invalid in '$($directory.Name)'."
        }
        $applyHash = Get-Sha256Lower -Path $applyPath
        $verifyHash = Get-Sha256Lower -Path $verifyPath
        if ($manifest.applySha256 -ne $applyHash) {
            throw "apply.sql checksum mismatch in '$($directory.Name)'."
        }
        if ($manifest.verifySha256 -ne $verifyHash) {
            throw "verify.sql checksum mismatch in '$($directory.Name)'."
        }
        foreach ($sqlPath in @($applyPath, $verifyPath)) {
            $sqlText = Get-Content -LiteralPath $sqlPath -Raw
            if ($sqlText.Length -gt 0 -and $sqlText[0] -eq [char] 0xFEFF) {
                $sqlText = $sqlText.Substring(1)
            }
            if ($sqlText -match '(?m)^\s*\\') {
                throw "psql meta-commands are forbidden in reviewed release SQL: '$sqlPath'."
            }
            if ($sqlText -match '(?im)^\s*(?:COMMIT|ROLLBACK|START\s+TRANSACTION)\b|^\s*BEGIN\s*;') {
                throw "Transaction control is forbidden in reviewed release SQL: '$sqlPath'."
            }
        }

        [pscustomobject][ordered]@{
            version = $version
            numericVersion = [int] $version
            description = [string] $manifest.description
            directoryName = $directory.Name
            applyPath = $applyPath
            verifyPath = $verifyPath
            manifestPath = $manifestPath
            checksum = Get-Sha256Lower -Path $manifestPath
        }
    }

    $ordered = @($releases | Sort-Object -Property numericVersion)
    $duplicateVersions = @($ordered | Group-Object -Property numericVersion | Where-Object { $_.Count -gt 1 })
    if ($duplicateVersions.Count -gt 0) {
        throw "Duplicate schema release versions: $($duplicateVersions.Name -join ', ')."
    }
    return $ordered
}

Export-ModuleMember -Function @(
    'Assert-CleanupObserved',
    'Assert-DetachedManifestHash',
    'Assert-DisjointPathRoots',
    'Assert-BackupLayout',
    'Assert-SafePathWithinRoot',
    'Compare-InventoryFiles',
    'Get-NormalizedSchemaSha256',
    'Get-PortableRelativePath',
    'Get-Sha256Lower',
    'Get-ValidatedSchemaReleases',
    'New-JpaCompatibilityFixture',
    'Test-ChecksumManifest',
    'Write-SchemaReleaseManifest',
    'Write-Utf8NoBom',
    'Write-ChecksumManifest'
)
