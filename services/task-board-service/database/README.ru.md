# Releases базы task-board

[English version](README.md)

Flyway — единственный активный механизм schema/version/checksum. Новые БД
устанавливают кумулятивный
`src/main/resources/db/migration/V4__task_board_schema.sql`; существующая
проверенная БД явно baselined на версии 4 с помощью
`database/flyway/verify-version-4.sql`. Hibernate валидирует каждый профиль и
никогда не создаёт, не обновляет и не удаляет целевую схему.

`V0001__adopt-task-board-schema` принимает точную F0 domain schema из
четырнадцати таблиц. `V0002__add-integration-messaging` добавляет immutable
request fingerprint и принадлежащие сервису transactional outbox/inbox.
Исторический `V0003__enforce-case-insensitive-identities` после fail-closed
duplicate preflight добавляет release-only PostgreSQL functional unique indexes
для class codes, worker logins и warehouse queue codes. Hibernate
`create-drop` не выражает эти functional/partial indexes; их каноническая
проверка — release-schema validation и operator release test.

`V0004__add-worker-credential-operation` добавляет nullable durable fencing
metadata в `worker`: UUID операции, точный operation type и время начала. Все
три значения либо null, либо заполнены. Поддерживаются `CONFIGURE`, `RESET`,
`DISABLE`, `CLEAR` и `RECONCILE_DISABLE`. Существующие строки, включая legacy
`PENDING` credentials, не переписываются, metadata операции не синтезируется.
Поэтому legacy `PENDING` с полностью null metadata остаётся явным
свидетельством для ручной reconciliation, а не получает угаданные identity или
timestamp.

Исторические таблицы `databasechangelog*` никогда не создаются, не изменяются
и не удаляются.

Исторические custom runner и `rwms_schema_history` остаются immutable migration
evidence; они больше не являются активным runtime migration path.

`Test-TaskBoardSchemaRelease.ps1` остаётся историческим release-proof tool.
Flyway integration suite доказывает чистую установку V4, повтор, checksum
drift, отказ для непустой схемы, точный preflight, явный baseline, сохранность
данных и JPA validation. Условный restored-F0 test использует предоставленные
оператором переменные `TASK_BOARD_F0_RESTORED_*`; backup contents не попадают в
Git.

Retention periods outbox и inbox остаются `UNKNOWN`; контролируйте рост и не
очищайте таблицы без проверенного release и replay/audit policy.

Idempotency внешней task command использует явную canonical fingerprint schema
`task-board-create:v1`: нормализованные scalar values и route steps в
фиксированном порядке с length prefix, hashed как UTF-8 SHA-256. Формат не
зависит от Jackson configuration; изменение fields или encoding требует нового
schema prefix и решения о совместимости.
