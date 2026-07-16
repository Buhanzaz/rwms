[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$runner = Join-Path $repositoryRoot 'tools\migration\Apply-SchemaReleases.ps1'
$releaseRoot = Join-Path $PSScriptRoot 'releases'
$fixture = Join-Path $PSScriptRoot 'fixtures\f0-nonempty.sql'
$migrationTools = Join-Path $repositoryRoot 'tools\migration\MigrationTools.psm1'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "rwms-task-board-release-$([Guid]::NewGuid().ToString('N'))"
$container = "rwms-task-board-release-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$previousUser = $env:PGUSER
$previousDatabase = $env:PGDATABASE
$domainTables = @(
  'worker_class', 'worker', 'worker_deletion_intent', 'worker_class_assignment',
  'worker_group', 'worker_group_member', 'work_queue', 'work_queue_class_binding',
  'board_task', 'queue_entry', 'task_assignment', 'task_time_event',
  'task_auto_interruption', 'queue_usage_reference'
)
Import-Module $migrationTools -Force

function Invoke-Runner {
  param(
    [Parameter(Mandatory)][string] $Database,
    [string] $Root = $releaseRoot
  )
  $env:PGDATABASE = $Database
  $powerShell = (Get-Process -Id $PID).Path
  $output = (& $powerShell -NoProfile -File $runner -ServiceName task-board-service `
    -ReleaseRoot $Root -DockerContainer $container 2>&1 | Out-String)
  if ($LASTEXITCODE -ne 0) {
    throw "Task-board schema runner failed for '$Database': $($output.Trim())"
  }
  Write-Output $output.Trim()
}

function Invoke-ExpectedFailure {
  param(
    [Parameter(Mandatory)][scriptblock] $Command,
    [Parameter(Mandatory)][string] $MessagePattern
  )
  try {
    & $Command
    throw 'Expected schema release command to fail.'
  } catch {
    if ($_.Exception.Message -eq 'Expected schema release command to fail.') { throw }
    if ($_.Exception.Message -notmatch $MessagePattern) {
      throw "Schema release failed for an unexpected reason: $($_.Exception.Message)"
    }
  }
}

function Invoke-PsqlScalar {
  param(
    [Parameter(Mandatory)][string] $Database,
    [Parameter(Mandatory)][string] $Sql
  )
  $result = (& docker exec $container psql -X -At -v ON_ERROR_STOP=1 `
    -U task_board_test -d $Database -c $Sql) -join "`n"
  if ($LASTEXITCODE -ne 0) { throw "PostgreSQL query failed for '$Database'." }
  return $result.Trim()
}

function Get-Sha256Text {
  param([Parameter(Mandatory)][AllowEmptyString()][string] $Value)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
  } finally {
    $sha.Dispose()
  }
}

function Get-DomainDigests {
  param([Parameter(Mandatory)][string] $Database)
  $digests = [ordered]@{}
  foreach ($table in $domainTables) {
    $projection = if ($table -eq 'board_task') {
      "to_jsonb(row_value) - 'request_fingerprint'"
    } elseif ($table -eq 'worker') {
      "to_jsonb(row_value) - 'credential_operation_id' - 'credential_operation_type' - 'credential_operation_started_at'"
    } else {
      'to_jsonb(row_value)'
    }
    $canonical = Invoke-PsqlScalar -Database $Database -Sql `
      "select coalesce(jsonb_agg($projection order by id)::text, '[]') from public.$table row_value"
    $digests[$table] = Get-Sha256Text -Value $canonical
  }
  return $digests
}

function Assert-DigestsEqual {
  param(
    [Parameter(Mandatory)] $Before,
    [Parameter(Mandatory)] $After
  )
  foreach ($table in $domainTables) {
    if ($Before[$table] -ne $After[$table]) {
      throw "Reviewed releases changed existing values in '$table': $($Before[$table]) -> $($After[$table])."
    }
  }
}

function Assert-History {
  param([Parameter(Mandatory)][string] $Database)
  $expected = @(Get-ValidatedSchemaReleases -ReleaseRoot $releaseRoot) |
    ForEach-Object { "$($_.version)|$($_.checksum)" }
  $actual = @(Invoke-PsqlScalar -Database $Database -Sql `
    "select version || '|' || checksum from public.rwms_schema_history order by version")
  if (($actual -join "`n") -ne ($expected -join "`n")) {
    throw "Unexpected schema history in '$Database': '$($actual -join ',')'."
  }
}

try {
  New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
  docker run -d --name $container --network none `
    -e POSTGRES_USER=task_board_test `
    -e POSTGRES_PASSWORD=task_board_test `
    -e POSTGRES_DB=task_board_clean postgres:17-alpine | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Failed to start task-board PostgreSQL.' }
  Start-Sleep -Seconds 2
  $ready = $false
  1..60 | ForEach-Object {
    if (-not $ready) {
      $probe = (docker exec $container psql -X -At -U task_board_test -d task_board_clean `
        -c 'select 1' 2>$null) -join ''
      if ($LASTEXITCODE -eq 0 -and $probe.Trim() -eq '1') {
        $ready = $true
      } else {
        Start-Sleep -Milliseconds 250
      }
    }
  }
  if (-not $ready) { throw 'Task-board PostgreSQL did not become ready.' }
  $env:PGUSER = 'task_board_test'
  docker exec $container createdb -U task_board_test task_board_upgrade
  if ($LASTEXITCODE -ne 0) { throw 'Failed to create upgrade verification database.' }
  docker exec $container createdb -U task_board_test task_board_duplicate
  if ($LASTEXITCODE -ne 0) { throw 'Failed to create duplicate-preflight database.' }
  docker exec $container createdb -U task_board_test task_board_duplicate_worker
  if ($LASTEXITCODE -ne 0) { throw 'Failed to create worker duplicate-preflight database.' }
  docker exec $container createdb -U task_board_test task_board_duplicate_queue
  if ($LASTEXITCODE -ne 0) { throw 'Failed to create queue duplicate-preflight database.' }

  # Clean install and repeat safety are independent from the populated upgrade.
  Invoke-Runner -Database task_board_clean
  Invoke-Runner -Database task_board_clean
  Assert-History -Database task_board_clean

  # Build a V0001-only release root, load the complete compatibility graph, then
  # apply V0002 and prove every legacy table is byte-for-byte equivalent.
  $v1Root = Join-Path $temporaryRoot 'v1-only'
  New-Item -ItemType Directory -Path $v1Root -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $releaseRoot 'V0001__adopt-task-board-schema') `
    -Destination $v1Root -Recurse
  Invoke-Runner -Database task_board_upgrade -Root $v1Root
  docker cp $fixture "${container}:/tmp/f0-nonempty.sql" | Out-Null
  docker exec $container psql -X -v ON_ERROR_STOP=1 -U task_board_test -d task_board_upgrade `
    -f /tmp/f0-nonempty.sql | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Non-empty V0001 compatibility fixture failed.' }
  $before = Get-DomainDigests -Database task_board_upgrade
  Invoke-Runner -Database task_board_upgrade
  $after = Get-DomainDigests -Database task_board_upgrade
  Assert-DigestsEqual -Before $before -After $after
  Assert-History -Database task_board_upgrade
  $integrationState = Invoke-PsqlScalar -Database task_board_upgrade -Sql @'
select concat_ws('|',
  (select count(*) from board_task where request_fingerprint is not null),
  (select count(*) from task_board_outbox),
  (select count(*) from task_board_inbox))
'@
  if ($integrationState -ne '0|0|0') {
    throw "V0002 synthesized integration state during adoption: '$integrationState'."
  }

  $credentialOperationState = Invoke-PsqlScalar -Database task_board_upgrade -Sql @'
select concat_ws('|',
  (select count(*) from worker where credential_operation_id is not null),
  (select count(*) from worker where credential_operation_type is not null),
  (select count(*) from worker where credential_operation_started_at is not null))
'@
  if ($credentialOperationState -ne '0|0|0') {
    throw "V0004 synthesized worker credential operation metadata: '$credentialOperationState'."
  }

  $graphProof = Invoke-PsqlScalar -Database task_board_upgrade -Sql @'
select concat_ws('|',
  (select count(distinct warehouse_id) from worker),
  (select count(distinct credential_status) from worker),
  (select count(distinct queue_type) from work_queue),
  (select count(distinct status) from board_task),
  (select count(distinct status) from queue_entry),
  (select count(distinct entry_type) from queue_entry),
  (select count(*) from queue_entry where queue_id is null),
  (select count(*) from task_auto_interruption where not active and resolved_at is not null),
  (select count(*) from task_assignment where worker_id is null and worker_name_snapshot is not null))
'@
  if ($graphProof -ne '2|4|3|3|5|2|1|1|1') {
    throw "Compatibility graph was not readable after upgrade: '$graphProof'."
  }

  # V0003 must reject legacy case variants before creating any functional
  # identity index, without deleting or rewriting the conflicting rows.
  $preV3Root = Join-Path $temporaryRoot 'pre-v3'
  New-Item -ItemType Directory -Path $preV3Root -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $releaseRoot 'V0001__adopt-task-board-schema') `
    -Destination $preV3Root -Recurse
  Copy-Item -LiteralPath (Join-Path $releaseRoot 'V0002__add-integration-messaging') `
    -Destination $preV3Root -Recurse
  Invoke-Runner -Database task_board_duplicate -Root $preV3Root
  $duplicateSql = @'
insert into worker_class(id,version,revision_marker,code,name,sort_order,active) values
 ('91000000-0000-0000-0000-000000000001',0,'91000000-0000-0000-0000-000000000011','MixedCase','One',0,true),
 ('91000000-0000-0000-0000-000000000002',0,'91000000-0000-0000-0000-000000000012','mixedcase','Two',1,true);
'@
  Invoke-PsqlScalar -Database task_board_duplicate -Sql $duplicateSql | Out-Null
  Invoke-ExpectedFailure -MessagePattern 'duplicate normalized values exist' `
    -Command { Invoke-Runner -Database task_board_duplicate }
  $duplicateState = Invoke-PsqlScalar -Database task_board_duplicate -Sql @'
select concat_ws('|',
  (select count(*) from worker_class),
  (select count(*) from rwms_schema_history),
  (select to_regclass('public.uk_worker_class_code_ci') is null),
  (select to_regclass('public.uk_worker_app_login_ci') is null),
  (select to_regclass('public.uk_work_queue_code_ci') is null))
'@
  if ($duplicateState -ne '2|2|t|t|t') {
    throw "V0003 duplicate preflight changed legacy state: '$duplicateState'."
  }

  Invoke-Runner -Database task_board_duplicate_worker -Root $preV3Root
  Invoke-PsqlScalar -Database task_board_duplicate_worker -Sql @'
insert into worker(id,version,revision_marker,warehouse_id,display_name,app_login,credential_status) values
 ('92000000-0000-0000-0000-000000000001',0,'92000000-0000-0000-0000-000000000011','92000000-0000-0000-0000-000000000021','One','Mixed.Login','ACTIVE'),
 ('92000000-0000-0000-0000-000000000002',0,'92000000-0000-0000-0000-000000000012','92000000-0000-0000-0000-000000000021','Two','mixed.login','ACTIVE');
'@ | Out-Null
  Invoke-ExpectedFailure -MessagePattern 'duplicate normalized values exist' `
    -Command { Invoke-Runner -Database task_board_duplicate_worker }
  $workerDuplicateState = Invoke-PsqlScalar -Database task_board_duplicate_worker -Sql @'
select concat_ws('|',
  (select count(*) from worker),
  (select count(*) from rwms_schema_history),
  (select to_regclass('public.uk_worker_class_code_ci') is null),
  (select to_regclass('public.uk_worker_app_login_ci') is null),
  (select to_regclass('public.uk_work_queue_code_ci') is null))
'@
  if ($workerDuplicateState -ne '2|2|t|t|t') {
    throw "V0003 worker duplicate preflight changed legacy state: '$workerDuplicateState'."
  }

  Invoke-Runner -Database task_board_duplicate_queue -Root $preV3Root
  Invoke-PsqlScalar -Database task_board_duplicate_queue -Sql @'
insert into work_queue(id,version,revision_marker,warehouse_id,code,name,queue_type,sort_order,active,hidden,collapsed,notify_when_threshold_reached) values
 ('93000000-0000-0000-0000-000000000001',0,'93000000-0000-0000-0000-000000000011','93000000-0000-0000-0000-000000000021','MixedQueue','One','REPAIR',10,true,false,false,false),
 ('93000000-0000-0000-0000-000000000002',0,'93000000-0000-0000-0000-000000000012','93000000-0000-0000-0000-000000000021','mixedqueue','Two','REPAIR',20,true,false,false,false);
'@ | Out-Null
  Invoke-ExpectedFailure -MessagePattern 'duplicate normalized values exist' `
    -Command { Invoke-Runner -Database task_board_duplicate_queue }
  $queueDuplicateState = Invoke-PsqlScalar -Database task_board_duplicate_queue -Sql @'
select concat_ws('|',
  (select count(*) from work_queue),
  (select count(*) from rwms_schema_history),
  (select to_regclass('public.uk_worker_class_code_ci') is null),
  (select to_regclass('public.uk_worker_app_login_ci') is null),
  (select to_regclass('public.uk_work_queue_code_ci') is null))
'@
  if ($queueDuplicateState -ne '2|2|t|t|t') {
    throw "V0003 queue duplicate preflight changed legacy state: '$queueDuplicateState'."
  }

  # A modified reviewed release is rejected without changing history.
  $driftRoot = Join-Path $temporaryRoot 'drift'
  Copy-Item -LiteralPath $releaseRoot -Destination $driftRoot -Recurse
  Add-Content -LiteralPath (Join-Path $driftRoot 'V0002__add-integration-messaging\apply.sql') `
    -Value '-- checksum drift fixture'
  Invoke-ExpectedFailure -MessagePattern 'checksum|manifest|SHA-256' `
    -Command { Invoke-Runner -Database task_board_clean -Root $driftRoot }
  Assert-History -Database task_board_clean

  # Apply and verify share one transaction: a verification failure leaves
  # neither its sentinel table nor a schema-history row.
  $rollbackRoot = Join-Path $temporaryRoot 'rollback'
  Copy-Item -LiteralPath $releaseRoot -Destination $rollbackRoot -Recurse
  $releaseFive = Join-Path $rollbackRoot 'V0005__failed-verification'
  New-Item -ItemType Directory -Path $releaseFive -Force | Out-Null
  Write-Utf8NoBom -Path (Join-Path $releaseFive 'apply.sql') `
    -Content "CREATE TABLE public.must_rollback (id integer PRIMARY KEY);`n"
  Write-Utf8NoBom -Path (Join-Path $releaseFive 'verify.sql') `
    -Content "DO `$rwms`$ BEGIN RAISE EXCEPTION 'expected verification failure'; END `$rwms`$;`n"
  Write-SchemaReleaseManifest -ReleaseDirectory $releaseFive `
    -Description 'Failed verification fixture' | Out-Null
  Invoke-ExpectedFailure -MessagePattern 'expected verification failure' `
    -Command { Invoke-Runner -Database task_board_clean -Root $rollbackRoot }
  $rolledBack = Invoke-PsqlScalar -Database task_board_clean -Sql `
    "select to_regclass('public.must_rollback') is null"
  if ($rolledBack -ne 't') { throw 'Failed release did not roll back.' }
  Assert-History -Database task_board_clean

  Write-Output 'Task-board clean install, repeat, populated V0004 upgrade, SHA-256 preservation, identity preflight, checksum drift and rollback checks passed.'
}
finally {
  if ($null -eq $previousUser) { Remove-Item Env:PGUSER -ErrorAction SilentlyContinue } else { $env:PGUSER = $previousUser }
  if ($null -eq $previousDatabase) { Remove-Item Env:PGDATABASE -ErrorAction SilentlyContinue } else { $env:PGDATABASE = $previousDatabase }
  docker rm -f $container 2>$null | Out-Null
  Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
}
