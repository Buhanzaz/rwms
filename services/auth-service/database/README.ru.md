# Свидетельства миграции базы auth

[English version](README.md)

Flyway является активным источником схемы auth, версий миграций и checksums.
Кумулятивная миграция чистой установки —
`src/main/resources/db/migration/V2__auth_schema.sql`. Существующую БД после
F1C разрешено принять только после успешного
`flyway/verify-version-2.sql` и явного baseline оператором на версии `2`; см.
`flyway/README.md`. Автоматический baseline отключён.

`baseline/schema.sql`, неизменяемые каталоги в `releases/`, корневой runner
`Apply-SchemaReleases.ps1` и `rwms_schema_history` являются историческими
read-only свидетельствами. Они больше не образуют активный production-путь
миграции.

`V0001__adopt-auth-schema` намеренно является adoptive release: он создаёт
отсутствующие auth- и Spring Authorization Server JDBC-объекты, условно
добавляет точные текущие constraints и indexes и никогда не изменяет
существующие строки. Его монотонный `verify.sql` проверяет обязательные baseline
columns, types, lengths, nullability, defaults, определения именованных
constraints, cascade пользовательского доступа и обязательные indexes. Будущие
аддитивные releases могут добавлять columns и indexes.

Исторические таблицы `databasechangelog` и `databasechangeloglock` не создаются
и не изменяются чистой установкой и сохраняются без изменений при принятии F0
database. OAuth-таблицы намеренно не имеют foreign keys, что соответствует F0
schema и JDBC-контракту Spring Authorization Server 7.1.

Flyway schema suite доказывает кумулятивную установку V2, повтор без изменений,
отклонение checksum drift, отказ для непустой неверсионированной схемы, точный
preflight версии 2, явное принятие baseline с content digests и читаемость OAuth
JDBC. Исторические release tests сохраняют более ранние свидетельства clean /
adoption и canonicalization. Условный
`AuthF0RestoredDatabaseIntegrationTest` оператор запускает на одноразовом
restore, задав `AUTH_F0_RESTORED_JDBC_URL`, `AUTH_F0_RESTORED_USERNAME` и
`AUTH_F0_RESTORED_PASSWORD`; в обычном CI тест пропускается, поскольку backup
contents не попадают в Git.

Не редактируйте применённую Flyway migration, исторический release или
manifest. Добавляйте следующую versioned migration и используйте
expand/contract для разрушительных изменений. Flyway `repair` не заменяет
проверенный rollback.

`V0002__canonicalize-warehouse-identifiers` — проверенный F1C data release. Он
сопоставляет только case-insensitive raw `spb`/`msk` и точный UUID text. Порядок
locks фиксирован: `auth_subject`, затем `user_warehouse_access`; все проверки
invalid values, коллизий на пользователя и переполнения integer version
завершаются до изменения любой таблицы. Строки USER grant и WORKER subject
сохраняют IDs, увеличивают `version` и обновляют `updated_at`. Повторное
применение byte-stable. Значения с пробелами по краям и неизвестные opaque
values приводят к отказу без записей. Одобренный rollback — восстановление
backup, поскольку канонический UUID не доказывает историческое написание.
