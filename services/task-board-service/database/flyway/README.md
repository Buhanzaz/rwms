# Task-board Flyway adoption at version 4

[Русская версия](README.ru.md)

`V4__task_board_schema.sql` installs a new empty task-board database with the
reviewed cumulative schema after historical releases `0001` through `0004`.
Never run `baseline` for a new database: `migrate` applies V4 and creates
`flyway_schema_history`.

An existing post-F2 database is adopted only after a verified backup and the
version-4 preflight succeed. The application keeps `baselineOnMigrate=false`,
so it cannot silently adopt a non-empty unversioned schema.

Run the operator flow with credentials supplied outside the repository:

```text
psql -X -v ON_ERROR_STOP=1 -f database/flyway/verify-version-4.sql
flyway -baselineVersion=4 -baselineDescription="Task-board post-F2 schema" baseline
flyway migrate
flyway validate
```

The preflight requires the exact current task-board catalog, JPA-compatible
enum values, all four historical custom release checksums and retained
Liquibase evidence. Any failure blocks the baseline. Do not use `repair` to
hide checksum drift and do not delete `rwms_schema_history`,
`databasechangelog`, `databasechangeloglock`, `task_board_outbox` or
`task_board_inbox`.

Flyway migration V5 is intentionally absent. It belongs to the later F4T
event-sourcing gate.
