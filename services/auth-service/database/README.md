# Auth database migration evidence

Flyway is the active auth schema migration, version and checksum authority.
The cumulative clean-install migration is
`src/main/resources/db/migration/V2__auth_schema.sql`. An existing post-F1C
database is adopted only after `flyway/verify-version-2.sql` succeeds and an
operator explicitly baselines it at version `2`; see `flyway/README.md`.
Automatic baseline adoption is disabled.

`baseline/schema.sql`, immutable directories under `releases/`, the
repository-level `Apply-SchemaReleases.ps1` runner and `rwms_schema_history`
are historical read-only evidence. They no longer form the active production
migration path.

`V0001__adopt-auth-schema` is deliberately adoptive: it creates missing auth and
Spring Authorization Server JDBC objects, conditionally adds the exact current
constraints and indexes, and never changes existing rows. Its monotonic
`verify.sql` checks required baseline columns, types, lengths, nullability,
defaults, named constraint definitions, the user-access cascade and required
indexes. It allows future additive releases to add columns and indexes.

Historical `databasechangelog` and `databasechangeloglock` tables are neither
created nor modified by the clean install and are retained unchanged when an F0
database is adopted. OAuth tables intentionally have no foreign keys, matching
the F0 schema and Spring Authorization Server 7.1 JDBC contract.

The Flyway schema suite proves cumulative V2 installation, repeat no-op,
checksum rejection, non-empty-schema refusal, exact version-2 preflight,
explicit baseline adoption with content digests, and OAuth JDBC readability.
Historical release tests retain the earlier clean/adoption and canonicalization
evidence. The conditional
`AuthF0RestoredDatabaseIntegrationTest` is run by the operator against a
disposable restore by setting `AUTH_F0_RESTORED_JDBC_URL`,
`AUTH_F0_RESTORED_USERNAME`, and `AUTH_F0_RESTORED_PASSWORD`; it is skipped in
ordinary CI because backup contents never enter Git.

Do not edit an applied Flyway migration, historical release, or historical
manifest. Add the next Flyway versioned migration and use expand/contract for
destructive changes. Flyway `repair` is not a substitute for reviewed rollback.

`V0002__canonicalize-warehouse-identifiers` is the reviewed F1C data release.
It maps only case-insensitive raw `spb`/`msk` and exact UUID text. Its fixed lock
order is `auth_subject` then `user_warehouse_access`; all invalid-value,
per-user collision and integer-version-overflow checks complete before either
table changes. USER grant and WORKER subject rows keep their IDs, increment
`version` and update `updated_at`. Repeat apply is byte-stable. Whitespace-wrapped
or unknown opaque values abort without writes. Backup restore is the approved
rollback because a canonical UUID does not prove which historical spelling was
used.
