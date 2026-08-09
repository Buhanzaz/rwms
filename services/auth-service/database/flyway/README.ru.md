# Принятие auth-схемы Flyway на версии 2

[English version](README.md)

`V2__auth_schema.sql` устанавливает новую пустую auth database. Для новой БД
никогда не запускайте `baseline`: команда `migrate` применяет V2 и создаёт
`flyway_schema_history`.

Существующую БД после F1C разрешено принять только после проверенного backup и
успешного preflight версии 2. Приложение сохраняет
`baselineOnMigrate=false`, поэтому не может незаметно принять непустую
неверсионированную схему.

Оператор запускает процесс с credentials, переданными вне репозитория:

```text
psql -X -v ON_ERROR_STOP=1 -f database/flyway/verify-version-2.sql
flyway -baselineVersion=2 -baselineDescription="Auth post-F1C schema" baseline
flyway migrate
flyway validate
```

Preflight требует точную текущую форму auth/OAuth, канонические warehouse
identifiers, исторические custom versions `0001` и `0002` и сохранённые
Liquibase-свидетельства. Любая ошибка блокирует baseline. Не используйте
`repair`, чтобы скрыть checksum drift, и не удаляйте `rwms_schema_history`,
`databasechangelog` или `databasechangeloglock`.
