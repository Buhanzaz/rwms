# F1C Auth Warehouse-Existence Correction

Date: 2026-07-13

F1C changed only `auth-service` so W1 can activate canonical Warehouse UUID
validation without another auth rebuild.

## Migration

- `V0002__canonicalize-warehouse-identifiers` maps only `spb` and `msk` to the
  approved UUIDs.
- The release preserves source row IDs and `created_at`, deletes or merges no
  grant, increments `version` and `updated_at` for changed rows, and aborts
  before mutation on alias/canonical collisions or unmapped non-UUID warehouse
  IDs. The reviewed mapping, schema history and checksum are the SQL audit
  evidence.
- Repeat apply and checksum drift rejection are verified. Rollback is the
  verified pre-migration backup restore; no compensating SQL is required or
  claimed.
- Existing `databasechangelog*` evidence remains untouched and unused.

## Runtime boundary

Warehouse validation is disabled by default. When enabled, auth validates all
distinct requested UUIDs before persistence using a private client-credentials
token with only `warehouse.read`. No lookup occurs during login, user reads, or
JWT minting.

## Verification

- auth: 59 tests, zero failures/errors, one conditional skip;
- focused V0002: 7 passes;
- SQL runner: clean apply, repeat, checksum rejection, transactional rollback of
  a deliberately failed verification, and JPA validation;
- the conditional restored-backup test remained the one skip and was not
  re-executed; the existing F0/F1 backup-restore artifact remains the approved
  rollback policy;
- gateway: 26 passes;
- task-board: 76 tests, zero failures/errors, one conditional skip;
- final database and security reviews: clean.

F1C implements no Warehouse Service, gateway warehouse route activation, panel
cutover, or Stage 2 capability.
