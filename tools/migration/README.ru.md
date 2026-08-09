# Средства безопасной миграции баз F0

[English version](README.md)

Этот каталог собирает read-only evidence из существующих Compose PostgreSQL
databases и доказывает, что каждый dump в custom format восстанавливается в
одноразовый изолированный от сети PostgreSQL container.

Инструменты никогда не читают и не сохраняют `POSTGRES_PASSWORD`. В source
containers вызываются только `pg_dump`, catalog queries, `printenv` для
несекретных имён database/user, `docker cp` и удаление временных файлов в
`/tmp`.

## Запуск

```powershell
.\tools\migration\Test-MigrationTools.ps1
.\tools\migration\Invoke-F0Snapshot.ps1
```

Snapshot также пишет отдельный manifest digest в операторский индекс по
умолчанию `%LOCALAPPDATA%\RWMS\migration-backup-index`. Переопределяйте его
через `-TrustAnchorRoot` только каталогом вне `.rwms-migration-backups`, который
с ним не пересекается. Скопируйте anchor в access-controlled append-only/WORM
operator store. Digest, лежащий только рядом с backup, не доказывает, что его
manifest не был заменён.

Artifacts записываются в `.rwms-migration-backups/<UTC timestamp>/`:

- `database.dump` — backup данных и схемы в custom format;
- `schema.sql` — свидетельство только схемы;
- `inventory.json` — canonical tables, columns, indexes, constraints и точные
  row counts;
- `jpa-compatibility-fixture.json` — schema evidence для проверки будущих JPA
  mappings;
- `inventory.restored.json` и `schema.restored.sql` — evidence одноразового
  restore;
- `verification.json` — результат сравнения;
- корневой `manifest.sha256.json` — SHA-256 и byte length каждого artifact.

Каждый каталог сервиса также содержит `cleanup.json`. Успех требует
подтверждённого отсутствия каждого source-файла `/tmp/rwms-f0-*` и одноразового
restore container. Ошибка cleanup command или проверки отсутствия делает весь
запуск неуспешным.

Script завершается ошибкой, если source inventory изменился во время capture,
restore не совпал точно или одноразовый container невозможно удалить. Backups
содержат данные production-формы и должны оставаться ignored, encrypted at
rest, access controlled и вне Git.

Используйте `New-RollbackPlan.ps1`, чтобы проверить layout backup и вывести
обязательные operator gates. `-ExpectedManifestSha256` обязателен и должен
поступать из отдельного operator index, а не из backup subtree. Команда
намеренно никогда не выполняет разрушительный restore.

## Проверенные SQL releases

Каждый stateful service владеет упорядоченным release root. Применённые release
directories неизменяемы и используют точный layout:

```text
database/releases/
└── V0001__short-lowercase-slug/
    ├── apply.sql
    ├── verify.sql
    └── manifest.json
```

`apply.sql` и `verify.sql` содержат только PostgreSQL SQL; psql meta-commands
запрещены. Ни один файл не управляет outer transaction. После окончательной
готовности обоих файлов создайте проверенный manifest:

```powershell
Import-Module .\tools\migration\MigrationTools.psm1 -Force
Write-SchemaReleaseManifest `
  -ReleaseDirectory .\services\example-service\database\releases\V0001__baseline `
  -Description 'Create example service baseline'
```

Manifest записывает независимые SHA-256 для `apply.sql` и `verify.sql`. Его
собственный SHA-256 хранится в service database. Не генерируйте заново и не
редактируйте применённый release; добавляйте следующий numbered directory.
Release roots, directories, SQL files и manifests должны быть обычными
filesystem objects внутри их canonical root. Junctions, symlinks, reparse
points и выходы из canonical path запрещены в Windows и case-sensitive
systems.

`Apply-SchemaReleases.ps1` не принимает database password или connection string
parameter. Передавайте connection через стандартные libpq environment
variables или защищённую конфигурацию `PGSERVICE`/`.pgpass`:

```powershell
$env:PGSERVICE = 'rwms-example-production'
.\tools\migration\Apply-SchemaReleases.ps1 `
  -ServiceName example-service `
  -ReleaseRoot .\services\example-service\database\releases
```

Runner:

- берёт session advisory lock с ключом `rwms-schema:<service>`;
- создаёт принадлежащую сервису
  `public.rwms_schema_history(version, checksum, applied_at, description)`;
- проверяет каждый local manifest до подключения;
- применяет pending releases в numeric order, по одной transaction на release;
- запускает `verify.sql` внутри применяющей transaction;
- повторяет read-only verification ранее применённых releases;
- отклоняет checksum drift до любой release mutation.

Запускайте оба слоя тестов:

```powershell
.\tools\migration\Test-MigrationTools.ps1
.\tools\migration\Test-SchemaReleaseIntegration.ps1
.\tools\migration\Test-SchemaReleaseMatrix.ps1
```

Integration test использует одноразовый network-isolated PostgreSQL container
и доказывает clean replay, ordered upgrade, repeat safety, drift rejection и
rollback при неуспешной verification. Опция runner
`-DockerContainer` существует только для этого изолированного verification
path; production application в CI/CD использует локально установленный `psql`
и управляемые оператором libpq secrets.

Matrix вызывает integration runner через каждый установленный PowerShell host
и явно передаёт executable, покрывая Windows PowerShell 5.1 и PowerShell 7,
когда доступны оба.
