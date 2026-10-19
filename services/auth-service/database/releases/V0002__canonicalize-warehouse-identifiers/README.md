# V0002 warehouse identifier canonicalization

[Русская версия](README.ru.md)

This data release maps only the closed, reviewed aliases `spb` and `msk` to
their canonical Warehouse Service UUIDs. Existing exact UUID text is normalized
through PostgreSQL's UUID type. Whitespace-corrupted and all other opaque
identifiers abort; no value is inferred.

The release locks `auth_subject` and then `user_warehouse_access`, performs all
unmapped-value and per-user collision checks before either update, and aborts
the transaction on any ambiguity. Changed USER grant and WORKER subject rows
keep their primary key, increment their optimistic `version`, and receive a new
UTC `updated_at`. No row is merged or deleted.

The explicit mapping in `apply.sql`, the preserved row IDs and the release
checksum/history entry are the audit trail. A compensating rollback must use
the verified F0/F1 backup because reverse alias reconstruction is intentionally
ambiguous after canonicalization.
