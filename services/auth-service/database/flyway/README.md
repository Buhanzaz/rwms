# Auth Flyway adoption at version 2

`V2__auth_schema.sql` installs a new empty auth database. Never run `baseline`
for a new database; `migrate` applies V2 and creates `flyway_schema_history`.

An existing post-F1C database is adopted only after a verified backup and the
version-2 preflight succeed. The application keeps `baselineOnMigrate=false`,
so it cannot silently adopt a non-empty unversioned schema.

Run the operator flow with credentials supplied outside the repository:

```text
psql -X -v ON_ERROR_STOP=1 -f database/flyway/verify-version-2.sql
flyway -baselineVersion=2 -baselineDescription="Auth post-F1C schema" baseline
flyway migrate
flyway validate
```

The preflight requires the exact current auth/OAuth shape, canonical warehouse
identifiers, the historical custom versions `0001` and `0002`, and retained
Liquibase evidence. Any failure blocks the baseline. Do not use `repair` to
hide checksum drift and do not delete `rwms_schema_history`,
`databasechangelog`, or `databasechangeloglock`.
