# Принятие task-board-схемы Flyway на версии 4

[English version](README.md)

`V4__task_board_schema.sql` устанавливает новую пустую task-board database с
проверенной кумулятивной схемой после исторических releases `0001`–`0004`. Для
новой БД никогда не запускайте `baseline`: `migrate` применяет V4 и создаёт
`flyway_schema_history`.

Существующую БД после F2 разрешено принять только после проверенного backup и
успешного preflight версии 4. Приложение сохраняет
`baselineOnMigrate=false`, поэтому не может незаметно принять непустую
неверсионированную схему.

Оператор запускает процесс с credentials, переданными вне репозитория:

```text
psql -X -v ON_ERROR_STOP=1 -f database/flyway/verify-version-4.sql
flyway -baselineVersion=4 -baselineDescription="Task-board post-F2 schema" baseline
flyway migrate
flyway validate
```

Preflight требует точный текущий task-board catalog, совместимые с JPA enum
values, checksums всех четырёх исторических custom releases и сохранённые
Liquibase-свидетельства. Любая ошибка блокирует baseline. Не используйте
`repair`, чтобы скрыть checksum drift, и не удаляйте `rwms_schema_history`,
`databasechangelog`, `databasechangeloglock`, `task_board_outbox` или
`task_board_inbox`.

Flyway migration V5 намеренно отсутствует. Она относится к более позднему F4T.
