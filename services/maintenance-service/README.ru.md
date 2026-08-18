# Сервис обслуживания RWMS

[English version](README.md)

## Назначение и владение

`maintenance-service` владеет версиями каталога обслуживания, сметами, ремонтами, ремонтными
местами и мощностью, решениями о приёмке/списании и своей частью сверки публикации
инвентаризации. Только сервис является источником истины для этих переходов; другие сервисы не
записывают его PostgreSQL-таблицы и не восстанавливают состояние ремонта из Kafka.

Авторитетные HTTP- и event-контракты находятся в
[`contracts/openapi/maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
и [`contracts/events/maintenance-events.yaml`](../../contracts/events/maintenance-events.yaml).
[`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) — это
индекс: при расхождении правило необходимо проверить по текущим контрактам и коду сервиса.

## Публичная и внутренняя HTTP-граница

Публичные пользовательские операции версионированы под `/api/maintenance/v1/**`; они покрывают
каталог, смету, ремонт, настройки/ремонтные места и распоряжение имуществом. Изменяющие команды
используют определённые контрактом ключ идемпотентности и expected-version fields. Клиент обязан
обработать канонический конфликт `409`, а не отправлять изменившийся повтор.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/maintenance/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или
маршрут `/api/internal/**`.

Внутренние маршруты намеренно узкие:

- `/api/internal/maintenance/v1/inventory/**` обслуживает inventory-owned work с планами,
  источниками и сверкой публикации.
- `/api/internal/maintenance/v1/logistics/**` обслуживает оркестрацию смет возврата и
  ремонтных мест для logistics.

Внутренние вызовы используют service credentials, а не проброшенный пользовательский token.
Authorizer проверяет точные identity сервиса, audience и single-purpose scope для inventory и
logistics. HTTP security chain — stateless OAuth2/JWT; dev auth bypass ограничен профилем `dev` и
отклоняется production-валидатором.

## Изоляция складов и правило транзакций

Пользовательские операции авторизуются по запрошенному складу и уровню доступа. Maintenance хранит
состояние каталога, ремонта и распоряжения в собственной БД и никогда не выполняет join с БД другого
сервиса.

Удалённая истина доступна через `MaintenanceDependencyGateway` с client credentials для asset,
task-board, media, logistics и warehouse-service. Нельзя выполнять remote call, пока local
write-транзакция удерживает maintenance locks. Application flow фиксирует local preparation до
remote preflight/effects, затем продолжает local fenced state через durable recovery records.

## Явный выбор капитального ремонта

Команды сметы, первичного ремонта, inventory freeze и inventory publication могут передавать
необязательный `forceCapitalRepair`: отсутствие означает `false`, а явный JSON `null` отклоняется.
Выбор хранится независимо от флагов каталога и копируется в каждую ревизию сметы, ответ ремонта,
inventory snapshot и каждый новый Kafka-факт ESTIMATE/REPAIR v1. Для совместимости повторного
чтения отсутствие поля в историческом факте v1 принимается как `false`; явные не-boolean значения
остаются недопустимыми. Сложность ремонта становится CAPITAL, когда этот явный выбор истинен либо
любая текущая catalog WORK принудительно требует капремонт. Границы inventory freeze и сохранённого
snapshot отклоняют план, который одновременно запрашивает перемещение на ремонт, поэтому
maintenance не владеет двумя конкурирующими направлениями одного finding.
Единственным downstream implementation остаётся существующий логистический цикл капремонта,
отдельный active-capital список, приёмка и возврат в FREE; клиенты сами эти эффекты не создают.
Миграция [`V44__manual_capital_repair_selection.sql`](src/main/resources/db/migration/V44__manual_capital_repair_selection.sql)
заполняет существующие сметы, ревизии и ремонты значением `false`.

## Настройки мебели и конфликты забронированной бытовки

Существующий maintenance editor мебели показывает asset-owned nullable максимум на бытовку ниже
сопоставления с характеристикой бытовки. Maintenance хранит только durable intent связи catalog
node, ожидаемую asset version и последнее подтверждённое наблюдение; второй каталог оборудования
или остаток здесь не создаётся. Save завершает local preparation, синхронно выполняет существующую
private asset-команду ensure/update вне local write transaction и подтверждает возвращённые version
и maximum до успешного завершения mutation каталога. Catalog reads обогащают подтверждённые связи
актуальными asset-owned значениями. Миграция `V42__furniture_equipment_maximum_sync.sql` расширяет
существующий link intent. `V43__backfill_furniture_equipment_link_intents.sql` создаёт один `PENDING`
intent для каждого мебельного node UUID со старой локальной snapshot и выбирает active snapshot, а не
копию из draft или superseded каталога, чтобы существующий reconciler установил недостающую external
reference в asset-service без выдумывания локальных настроек или remap каталога. Источником истины
остаётся asset-service.

Когда asset-service отклоняет maintenance lease или fenced status command из-за активной order
reservation бытовки, `MaintenanceHttpTransport` пропускает только точный upstream `409` code
`BOOKED_UNIT_REPLACEMENT_REQUIRED`. Публичный maintenance Problem Details сохраняет этот code и
понятное сообщение о замене. Все прочие dependency codes, включая неизвестные upstream `409`,
остаются `MAINTENANCE_DEPENDENCY_UNAVAILABLE`; maintenance не создаёт и не принимает решение о
workflow замены бытовки.

После атомарной замены бытовки asset-service также отклоняет maintenance lease acquisition, пока
существующее перемещение мебели замены имеет live source hold. Maintenance не копирует эту
готовность и не создаёт другой тип задания; после выполнения существующего movement (либо когда
его hold больше не live) тот же maintenance acquisition можно повторить.

## Внутренняя структура приложения

`MaintenanceApplicationService` — стабильный compatibility facade над шестью collaborators. Он
сохраняет API controllers/private boundaries и transaction annotations, а работу делегирует
связным application owners:

| Семейство collaborators | Владеющая ответственность |
| --- | --- |
| `MaintenanceCatalogUseCases` и catalog model/support types | Чтения catalog versions, draft mutation, forking, validation и activation |
| `MaintenanceEstimateUseCases` и estimate/furniture/revision supports | Lifecycle сметы, lines, plans, furniture admission и immutable revisions |
| `MaintenanceRepairUseCases` и repair lifecycle/model/media/task-board supports | Создание ремонта, queueing, execution, acceptance и подготовка rework |
| `MaintenanceTransferUseCases` и `MaintenanceTransferSupport` | Maintenance continuation при transfer departure/arrival |
| `MaintenanceInboundUseCases` и `MaintenanceInboundFactProjectionUseCases` | Приём owner facts и обновление projections |
| `MaintenanceReconciliationUseCases` | Только claim dispatch, media-owner proof и failure recording |
| Asset-, task- и repair-lifecycle reconciliation use cases | Три независимые ветки remote effect/recovery |
| `InventoryMaintenanceService` | Стабильный фасад private inventory boundary над freeze, upsert и repair-snapshot projection |
| Collaborators freeze/upsert/validation/transaction для inventory maintenance | Admission замороженного плана, validation каталога/routing/media и изолированные local transactions |
| `InventoryPublicationReconciliationService` | Стабильный фасад completed inventory над preflight projection и durable apply |
| Collaborators source/pre-start/target/materialization для inventory publication | Source replay, replacement compensation, выбор target и materialization сметы/ремонта |
| `PropertyDispositionApplicationService` | Стабильный decision facade над creation, furniture materialization, review/recovery, reads и processing callbacks |
| Collaborators property-disposition boundary, persistence, actor, repair-chain и finalization | Механика transaction/lock/hash, event parity, validation repair chain и terminal repair effects |
| `HttpMaintenanceDependencyGateway` | Стабильный private transport facade над владельцами warehouse, asset, logistics, task-board и media |
| `MaintenanceHttpTransport` и owner HTTP clients | Exact-scope OAuth/HTTP/error policy, owner-local endpoints, DTO validation и fence checks |

`MaintenanceCommandSupport` централизует только исходную механику local transaction, lock, hash и
JSON-команд; event-payload/model supports остаются узкими mappers. Dependency graph use cases
ацикличен, ни один collaborator не ссылается обратно на facade, а каждая constructor/direct
dependency surface не превышает 15. Package-local workflow records не связывают collaborators с
публичным nested result type фасада; только facade адаптирует carrier обратно в неизменённый public
response.

Inventory maintenance boundary следует тому же правилу. У его фасада только
три collaborators; validation и allocation плана образуют отдельную границу,
freeze и upsert владеют отдельными командами, а snapshot projection доступна
только для чтения. Routing- и warehouse-preflight calls выполняются лишь после
rollback короткой local transaction и освобождения её locks.

Публикация completed inventory использует тот же remote-outside-lock pattern.
Pre-start replacement saga владеет compensation и successor state, а source,
target, estimate, repair и plan components остаются однонаправленными
зависимостями apply owner. Preflight является независимой read projection и не
может инициировать эффект публикации.

У property disposition пять однонаправленных application branches. Creation,
furniture materialization и review/recovery используют одинаковые узкие leaves
command, decision-persistence, actor и repair-chain; reads остаются только
projection, а processing callbacks владеют порядком mixed decision/repair
streams. Ни одна ветка не вызывает facade обратно и не выполняет remote
admission при удержании финального local decision lock.

Private HTTP gateway образует отдельный transport-only DAG. Пять owner clients
хранят свои endpoints и wire-validation vocabulary, а один общий transport
владеет только exact-scope client credentials, HTTP exchange и существующим
error policy. Owner clients не вызывают peers или application use cases и не
ссылаются обратно на gateway facade.

## Хранение, события и восстановление

Flyway-миграции в `src/main/resources/db/migration/` — единственный источник схемы. JPA использует
`ddl-auto=validate`; нельзя включать Hibernate schema mutation или добавлять межсервисные foreign
keys.

Зафиксированный факт агрегата записывается в local event stream, snapshot/checkpoint и
transactional outbox в одной PostgreSQL-транзакции. Доставка Kafka — at-least-once: relay повторно
проверяет envelope и отмечает outbox row только после broker acknowledgement; consumers выполняют
deduplication и хранят version-gap/recovery state; sanitized terminal failures проходят через
принадлежащий сервису DLT path, без копирования исходного тела сообщения в логи.

`MAINTENANCE_KAFKA_ENABLED` управляет Kafka-specific relay и consumer beans. Он может быть `false`
только в явном профиле `dev` или `test`; любой другой профиль отклоняет startup при выключенной
доставке. Канонические owner outputs имеют строгий порядок: catalog-version, estimate, repair,
property-disposition и sanitized DLT. Они совпадают с channel addresses в
[`maintenance-events` contract](../../contracts/events/maintenance-events.yaml) и совместно
используются startup validation и output binding initialization.

## Runtime-конфигурация

Порт HTTP по умолчанию — `8087`. Настройте maintenance database, `AUTH_ISSUER`, CORS origin, а
для реальных dependencies — token URI, client ID/secret и private base URLs из
`src/main/resources/application.yaml`. Нельзя коммитить реальные credentials.

No-op dependency boundary доступна только для отключённых `dev`/`test` конфигураций. Production
runtime обязан включить real dependencies; production validator проверяет dependency configuration и
отклоняет dev auth bypass. Сервис не переносит business aggregation в API gateway.

## Наблюдаемость и эксплуатация

Actuator предоставляет `health`, `info` и `prometheus`. Консольные логи используют ECS, а
tracing sampling задаётся `MAINTENANCE_TRACING_SAMPLING_PROBABILITY`. Расследуйте задержанную работу
по local outbox/inbox/recovery records и correlation ID до изменения domain data или повторения
effect.

Prometheus предоставляет low-cardinality gauges `rwms.maintenance.outbox.backlog`,
`rwms.maintenance.outbox.oldest.age.seconds` и `rwms.maintenance.outbox.terminal`. Они читают
pending/in-flight age и DLT/quarantine work без tags из событий, не дренируя и не переписывая rows.
Terminal count может сканировать сохранённые terminal rows, потому что в текущей схеме нет
partial index по terminal status; такой индекс остаётся отдельно рассматриваемым Flyway follow-up.

## Локальная разработка

Из корня репозитория:

```bash
bash ./gradlew :services:maintenance-service:bootRun --args='--spring.profiles.active=dev'
```

Используйте изолированную dependency-конфигурацию только для разработки или тестов. Service
integrations используют private URLs и client credentials, но не public gateway и не browser bearer
token.

## Production Kafka safety

У base profile нет Kafka-enable fallback. Вне явных `dev`/`test` startup требует точный порядок
пяти topics, явные не-loopback brokers формата `host:port`, выключенный topic auto-creation,
синхронную идемпотентную публикацию с `acks=all`, положительные bounded timeouts с maximum publish
wait короче outbox lease, а также beans outbox relay, sanitized-DLT relay и output binding.
Production profile имеет приоритет при объединении с local profile. Validator не дренирует backlog:
recovery остаётся явной reviewed operation.

## Правила безопасного изменения

- Меняйте OpenAPI или AsyncAPI contract и всех затронутых producers/consumers одновременно.
- Оставляйте aggregate transitions, expected-version fencing, idempotency и compensation в этом сервисе.
- Добавляйте неизменяемые service-local Flyway migrations и валидируйте затронутые JPA mappings.
- Сохраняйте outbox/inbox deduplication, порядок агрегата и operator-reviewed recovery.
- Тестируйте focused public/private contract, а также persistence и failure/retry path.

## Основные исходные материалы

- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceCatalogUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceEstimateUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReconciliationUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/MaintenanceEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceKafkaOutboxRelay.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceTransportTopics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/security/MaintenanceAuthorizer.java`
