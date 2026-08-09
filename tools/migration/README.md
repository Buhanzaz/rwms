# F0 database migration safety tooling

[Русская версия](README.ru.md)

This directory captures read-only evidence from the existing Compose PostgreSQL
databases and proves that each custom-format dump restores into a disposable,
network-isolated PostgreSQL container.

The tools never read or persist `POSTGRES_PASSWORD`. Source containers are only
called with `pg_dump`, catalog queries, `printenv` for non-secret database/user
names, `docker cp`, and removal of temporary files under `/tmp`.

## Run

```powershell
.\tools\migration\Test-MigrationTools.ps1
.\tools\migration\Invoke-F0Snapshot.ps1
```

The snapshot also writes a detached manifest digest under the default operator
index `%LOCALAPPDATA%\RWMS\migration-backup-index`. Override it with
`-TrustAnchorRoot` only when the directory is outside and non-overlapping with
`.rwms-migration-backups`. Copy the anchor to an access-controlled,
append-only/WORM operator store. A digest kept only beside a backup cannot prove
that its manifest was not replaced.

Artifacts are written under `.rwms-migration-backups/<UTC timestamp>/`:

- `database.dump`: custom-format data and schema backup;
- `schema.sql`: schema-only evidence;
- `inventory.json`: canonical tables, columns, indexes, constraints and exact
  row counts;
- `jpa-compatibility-fixture.json`: schema evidence used when validating the
  future JPA mappings;
- `inventory.restored.json` and `schema.restored.sql`: disposable restore
  evidence;
- `verification.json`: comparison result;
- root `manifest.sha256.json`: SHA-256 and byte length for every artifact.

Each service directory also contains `cleanup.json`. Success requires observed
absence of every source `/tmp/rwms-f0-*` file and the disposable restore
container. A failed cleanup command or observation marks the run failed.

The script fails if a source inventory changes during capture, if a restore is
not exact, or if a disposable container cannot be removed. Backups contain
production-shaped data and must remain ignored, encrypted at rest, access
controlled, and outside Git.

Use `New-RollbackPlan.ps1` to validate a backup layout and print the mandatory
operator gates. `-ExpectedManifestSha256` is required and must come from the
detached operator index, not the backup subtree. It intentionally never
performs a destructive restore.

## Reviewed SQL releases

Each stateful service owns an ordered release root. Release directories are
immutable after they have been applied and use this exact layout:

```text
database/releases/
└── V0001__short-lowercase-slug/
    ├── apply.sql
    ├── verify.sql
    └── manifest.json
```

`apply.sql` and `verify.sql` contain PostgreSQL SQL only; psql meta-commands are
rejected. Neither file may manage the outer transaction. Create the reviewed
manifest after both files are final:

```powershell
Import-Module .\tools\migration\MigrationTools.psm1 -Force
Write-SchemaReleaseManifest `
  -ReleaseDirectory .\services\example-service\database\releases\V0001__baseline `
  -Description 'Create example service baseline'
```

The manifest records independent SHA-256 values for `apply.sql` and
`verify.sql`. Its own SHA-256 is stored in the service database. Do not
regenerate or edit an applied release; add the next numbered directory.
Release roots, directories, SQL files, and manifests must be ordinary
filesystem objects contained by their canonical root. Junctions, symlinks,
reparse points, and canonical path escapes are rejected on Windows and
case-sensitive systems.

`Apply-SchemaReleases.ps1` accepts no database password or connection-string
parameter. Supply the connection through standard libpq environment variables
or a protected `PGSERVICE`/`.pgpass` setup:

```powershell
$env:PGSERVICE = 'rwms-example-production'
.\tools\migration\Apply-SchemaReleases.ps1 `
  -ServiceName example-service `
  -ReleaseRoot .\services\example-service\database\releases
```

The runner:

- takes a session advisory lock keyed by `rwms-schema:<service>`;
- creates service-owned `public.rwms_schema_history(version, checksum,
  applied_at, description)`;
- validates every local manifest before connecting;
- applies pending releases in numeric order, one transaction per release;
- runs `verify.sql` inside the applying transaction;
- reruns verification read-only for previously applied releases;
- rejects checksum drift before any release mutation.

Run both test layers:

```powershell
.\tools\migration\Test-MigrationTools.ps1
.\tools\migration\Test-SchemaReleaseIntegration.ps1
.\tools\migration\Test-SchemaReleaseMatrix.ps1
```

The integration test uses a disposable network-isolated PostgreSQL container
and proves clean replay, ordered upgrade, repeat safety, drift rejection, and
rollback when verification fails. The runner's `-DockerContainer` option exists
only for this isolated verification path; CI/CD production application uses a
locally installed `psql` and operator-managed libpq secrets.
The matrix invokes the integration runner through each installed PowerShell
host and passes that executable explicitly, covering Windows PowerShell 5.1 and
PowerShell 7 when both are available.
