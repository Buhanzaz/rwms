# F0 rollback runbook

Rollback is an operator-controlled disaster-recovery action. No automated tool
in this directory overwrites an existing database.

## Preconditions

1. Stop all writers to the affected service and verify that they remain down.
2. Record the failed release, target database, incident/change ticket and
   approving operator.
3. Retrieve the expected manifest SHA-256 from the detached append-only operator
   index, then run `New-RollbackPlan.ps1 -ExpectedManifestSha256 <digest>`.
   Never trust a digest found only inside the backup subtree.
4. Restore `database.dump` into a disposable PostgreSQL container of the same
   image version. Run `inventory.sql` and compare its SHA-256 with the captured
   `inventory.json`.
5. Take a new custom dump and schema inventory of the failed target before any
   destructive action.

## Replacement template

Use the deployment platform's secret injection. Never paste credentials into a
script, terminal history, repository file or ticket.

```text
pg_restore --exit-on-error --clean --if-exists --no-owner --no-privileges \
  --dbname=<operator-supplied-target-connection> database.dump
```

For a database replacement, prefer creating a new database, restoring into it,
validating it, and atomically switching the service connection. Do not run
`--clean` against the only copy of a database unless the fresh pre-rollback
backup and explicit approval are both verified.

## Verification and reopen

1. Compare canonical inventory, exact row counts, constraints and indexes.
2. Run the service's JPA `validate` profile and authentication/domain smoke
   tests before reopening traffic.
3. Preserve `databasechangelog` and `databasechangeloglock`; they remain
   historical evidence even after Liquibase runtime is removed.
4. Reopen traffic gradually and monitor database errors, authorization failures
   and outbox lag.
5. Record hashes, operator, timestamps and outcome in durable project memory.

If verification differs, keep writers stopped and escalate. Do not attempt an
ad-hoc data repair during rollback.

## Reviewed SQL release failure

`Apply-SchemaReleases.ps1` applies each new release and its verification in one
transaction. A failing `apply.sql` or `verify.sql` leaves no history row and
rolls back that release. Previously committed releases remain intact.

- Never modify a failed or already applied release to repair production.
- If the transaction rolled back, correct the proposed release during review,
  regenerate its manifest, and rerun before it is accepted anywhere else.
- If a release committed and later behavior is wrong, use a new reviewed
  compensating release or restore the verified pre-migration backup.
- A checksum drift failure is an integrity incident. Stop the rollout and
  compare the committed release, deployment artifact, and
  `rwms_schema_history`; do not update the stored checksum manually.
- `rwms_schema_history` is service-owned evidence and must be included in every
  subsequent backup and restore comparison.

Before restoring a pre-release backup, stop writers and schema runners, take a
fresh backup of the failed target, and verify that no later service release
depends on the schema being removed. Resume only after JPA `validate`, release
`verify.sql`, service tests, and the canonical inventory comparison pass.
