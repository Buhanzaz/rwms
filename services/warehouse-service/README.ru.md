# Warehouse Service RWMS

[English version](README.md)

`warehouse-service` — единственный владелец идентичности склада, изменяемых
метаданных, lifecycle, effective-dated истории timezone, admission операций и
устойчивого доказательства использования склада. Другие домены хранят UUID
склада как opaque reference и не поддерживают конкурирующий registry.

## Зачем он нужен

Имя, адрес, lifecycle и timezone склада влияют почти на каждый домен RWMS.
Центральный владелец не позволяет сервисам по-разному трактовать один склад или
переписывать исторические business dates после изменения timezone.

| Задача | Ответственность warehouse-service | Ответственность consumer |
| --- | --- | --- |
| Стабильная identity | Выдать и разрешить UUID склада | Хранить только UUID и contract-defined snapshots |
| Directory metadata | Владеть normalized-unique именем, городом, адресом и order | Читать через public или least-privilege private contract |
| Lifecycle | Владеть `ACTIVE -> DRAINING -> INACTIVE` и точной версией | Запрашивать directional admission и освобождать локальные blockers |
| Timezone | Владеть текущей и effective-dated историей | Разрешать timezone на immutable instant операции |
| Первая операция | Идемпотентно записать operated-boundary mark | Фиксировать локальную работу и recoverable mark вместе |
| Integration fact | Хранить и публиковать versioned warehouse fact | Дедуплицировать и проверять version до обновления локальной projection |

Сервис не владеет кабинами, inventory sessions, repairs, tasks, logistics
documents, пользователями, warehouse grants или client-side выбором склада.

## Представительские склады и граф обслуживания

Представительский склад остаётся тем же aggregate `Warehouse` с независимым признаком
`representative`. По умолчанию признак равен `false` и не означает lifecycle-состояние, отсутствие
персонала, ограничение направления остатков или запрет обычной доставки. WGS84-поля `latitude` и
`longitude` принадлежат метаданным склада и задаются только парой; `address` может отсутствовать,
если координаты заполнены. Склад без координат остаётся валидным в directory, но его logistics-
projection помечает его как неготовый к маршрутизации, поэтому новый workspace карты для него не
материализуется.

Owner также хранит направленный many-to-many граф обслуживания. Одна активная связь означает, что
опорный склад может предоставлять выбранные возможности одному обслуживаемому представительскому
складу. Policy независимо разрешает водителей, автомобили, имущество, прямое исполнение,
межскладские перемещения и fallback на подрядчика, а также задаёт приоритет, дни недели,
разрешённые даты, исключения и необязательный дневной интервал. Связи склада с самим собой и
дубликаты опорного склада запрещены. Полная коллекция заменяется атомарно под aggregate-version
fence обслуживаемого склада; снять представительский признак при существующих связях нельзя.

Логистика читает тот же UUID склада, owner-held координаты, представительский признак и
отфильтрованные по дате опорные связи через private warehouse boundary. Она не создаёт вторую
identity склада или точки карты. Самостоятельный планировщик сверяет directory по UUID и версии
склада; изменение версии или координат инвалидирует изменяемые планы маршрутов до использования
новой точки. См. [`Warehouse`](src/main/java/dev/buhanzaz/rwms/warehouse/domain/Warehouse.java),
[`WarehouseSupportLinkService`](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseSupportLinkService.java),
[`LogisticsWarehouseController`](src/main/java/dev/buhanzaz/rwms/warehouse/api/LogisticsWarehouseController.java)
и
[`сверку directory`](../../logistics/backend/app/services/catalog.py) планировщика.

## Поток запроса и lifecycle

```text
public-команда SYSTEM_ADMIN
          |
          v
create/replace/start draining/schedule timezone/complete inactivation
          |
          v
warehouse aggregate + проверка expectedVersion/idempotency
          |
          +--> транзакция warehouse DB + domain event + outbox
          |
          +--> Kafka warehouse fact

operation owner --private admission--> warehouse-service
operation owner --durable operation mark/readiness confirmation--> warehouse-service
```

Lifecycle однонаправлен:

- `ACTIVE` принимает incoming и outgoing операции;
- `DRAINING` запрещает новые incoming операции, но позволяет завершить валидную
  исходящую работу;
- `INACTIVE` терминален и требует exact-version подтверждений готовности от
  asset, inventory, logistics, maintenance и task-board.

Timeout или неизвестное состояние зависимости не означают готовность. Каждый
owner сохраняет pending, ambiguous, quarantined и non-terminal work как blocker,
пока не докажет освобождение точной lifecycle version.

## HTTP-границы

Канонический контракт:
[`contracts/openapi/warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml).
Публичный gateway публикует `/api/warehouse/**` без изменения пути.

| Граница | Audience | Назначение |
| --- | --- | --- |
| `/api/warehouse/v1/warehouses/**` | Authenticated `USER`; writes требуют exact administrator policy | Global directory, create/replace, draining, inactivation и timezone scheduling |
| `/api/warehouse/v1/warehouses/{id}/support-links` | Authenticated warehouse manager с grants для всех endpoints | Чтение или атомарная замена полного графа обслуживания представительского склада |
| `/api/warehouse/v1/admin/outbox-events/**` | Reviewed administrator recovery | Повтор одного immutable terminal/quarantined outbox fact под review fence |
| `/api/internal/warehouse/v1/warehouses/{id}/existence` | Exact auth-service credential/scope | Узкая existence-проверка warehouse grants |
| `/api/internal/warehouse/v1/warehouses/asset/**` | Exact asset-service credential/scope | Asset-specific existence boundary |
| `/api/internal/warehouse/v1/warehouses/inventory/**` | Exact inventory-service credential/scope | Inventory metadata snapshot |
| `/api/internal/warehouse/v1/warehouses/logistics/**` | Exact logistics-service credential/scope | Logistics identity/directory view |
| `/api/internal/warehouse/v1/warehouses/logistics/{id}/support-links` | Exact logistics-service credential/scope | Активные опорные связи, допустимые в переданный момент, с координатами обоих endpoints |
| `/api/internal/warehouse/v1/warehouses/{id}/admission` | Contract-defined operation owner | Directional lifecycle admission |
| `/api/internal/warehouse/v1/warehouses/{id}/lifecycle-readiness` | Contract-defined operation owner | Exact-version readiness confirmation |
| `/api/internal/warehouse/v1/lifecycle/readiness-work` | Service credential | Reconciliation незавершённых подтверждений |
| `/api/internal/warehouse/v1/warehouses/{id}/time-zone` | Least-privilege service credential | Timezone на immutable instant |
| `/api/internal/warehouse/v1/warehouses/{id}/operation-marks` | Contract-defined owner | Идемпотентное доказательство первой операции |

Public directory намеренно является глобальным authenticated read. Warehouse
grants его не фильтруют; write- и domain access checks остаются отдельными.

Responses logistics identity и directory всегда содержат принадлежащее
warehouse-service поле `address`; оно nullable для складов, адрес которых ещё
не записан. Directory включает только активные склады в canonical порядке
warehouse-service и доступна исключительно точным credential и scope
logistics-service.

## Identity, concurrency и время

- Warehouse UUID — integration identity. Display name нормализуется для
  уникальности, но не используется как cross-service key.
- Create использует `Idempotency-Key`; mutable updates — contract-defined
  expected aggregate version и возвращают `409` при stale state.
- Неиспользованный склад может немедленно исправить timezone. После durable
  operation mark изменение добавляется с будущим `effectiveFrom` и никогда не
  переписывает прежние факты.
- Inactive warehouse остаётся доступным для исторических references.
- Lifecycle readiness привязан к точной warehouse version, поэтому позднее
  подтверждение не может деактивировать более новое состояние.
- Изменение координат, представительского признака и коллекции связей увеличивает ту же aggregate
  version; замена связей дополнительно увеличивает принадлежащую складу revision и не может
  конкурировать с lifecycle-изменениями endpoints.

## Persistence и события

Сервис владеет одной PostgreSQL БД. Flyway-миграции в
[`src/main/resources/db/migration`](src/main/resources/db/migration/) —
единственный schema authority; Hibernate использует `ddl-auto=validate` и не
изменяет схему.

V7 добавляет обратносуместимый non-null столбец `representative=false`. V8 добавляет пару координат,
warehouse-owned support revision, направленные строки связей и их weekday/date value tables с
ограничениями уникальности и направления. Отдельной таблицы представительского склада и
`parentWarehouseId` нет.

Aggregate transition, append-only domain history и outbox envelope фиксируются
локально. Kafka relay забирает упорядоченную запись с lease, проверяет immutable
envelope и checksum, синхронно публикует в `rwms.warehouse.warehouse.v1`, затем
ставит published. Broker failure оставляет durable retry; schema/checksum
failure переводится в quarantine.

Reviewed recovery API не редактирует сохранённое событие. Он проверяет, что это
первый unpublished fact агрегата и что review version, actor, reason, envelope,
checksum и schema валидны, прежде чем тот же relay повторит публикацию.

Вне явного профиля `dev` или `test` startup требует точный warehouse topic,
не-loopback Kafka brokers, выключенный topic auto-creation, синхронную
идемпотентную публикацию с `acks=all`, maximum publish wait короче outbox lease,
а также наличие relay и output-binding beans. Если production profile объединён
с local profile, действуют production safety rules.

## Безопасность и изоляция

- Сервис является JWT resource server и валидирует issuer и audience.
- Public writes требуют exact user, scope и global administrator rules из
  `WarehouseAuthorizer`.
- Private routes требуют `SERVICE` principal, allow-listed `client_id` и exact
  least-privilege scope канонического контракта.
- Development auth bypass доступен только в явном `dev` profile и запрещён для
  production profiles.
- CORS строится из явного panel-origin list; клиенты обращаются только через
  public gateway.

## Наблюдаемость и ошибки

Сервис предоставляет Actuator liveness/readiness, Prometheus, tracing, ECS
structured logs и `X-Correlation-Id`. Логи и Problem Details не должны раскрывать
credentials, internal URLs, payload или personal data.

Business failures явны: invalid input — `400`, invalid identity — `401/403`,
отсутствующий склад — `404`, stale lifecycle/version или idempotency conflict —
`409`. Kafka failure не откатывает уже committed warehouse transition; recovery
authority остаётся у outbox.

Prometheus предоставляет `rwms.warehouse.outbox.backlog`,
`rwms.warehouse.outbox.oldest.age.seconds` и
`rwms.warehouse.outbox.terminal`. Эти read-only database gauges нужно
использовать для оценки pending, in-flight, DLT и quarantined work перед любым
отдельно разрешённым backlog drain.

## Локальная разработка

Запустите PostgreSQL и Kafka:

```bash
docker compose --profile core up -d warehouse-db kafka
```

Затем сервис с dev profile:

```bash
bash ./gradlew :services:warehouse-service:bootRun --args='--spring.profiles.active=dev'
```

Dev defaults используют PostgreSQL `127.0.0.1:5435`, auth
`http://localhost:9000`, Kafka `localhost:9092` и порт `8083`. Это не production
configuration.

## Обязательная production-конфигурация

Задайте database URL/credentials, HTTPS auth issuer, audience, explicit CORS
origin, Kafka brokers и явный `WAREHOUSE_KAFKA_ENABLED=true`. У base profile нет
fallback для Kafka enablement. Topic auto-creation остаётся выключенным;
сохраняются synchronous `acks=all`, producer idempotence, bounded publish
timeouts и outbox lease длиннее максимального publish wait.

Нельзя публиковать interactive clients внутренние routes, database, Actuator
management surface или Kafka broker.

## Проверка

Из корня репозитория:

```bash
bash ./gradlew :services:warehouse-service:test
bash ./gradlew :services:warehouse-service:javadoc
bash ./gradlew :platform:architecture-tests:test
```

Lifecycle change требует success, stale-version, wrong-owner,
unavailable-dependency, idempotent retry и exact readiness-version coverage.
Persistence change требует clean/upgrade Flyway и JPA validation. Contract
change требует OpenAPI/event schema и consumer compatibility checks.

## Правила безопасного изменения

1. Оставляйте identity, lifecycle, timezone, admission и operation marks здесь.
2. Не добавляйте business blockers или repositories другого сервиса в эту БД;
   owners сообщают readiness через private contract.
3. Сохраняйте global-directory semantics без явного product decision и
   coordinated client change.
4. Сохраняйте historical timezone и inactive identity разрешимыми.
5. Фиксируйте aggregate change и outbox fact атомарно; сохраняйте aggregate
   order, lease fencing, reviewed recovery и sanitized payload policy.
6. При изменении boundary обновляйте OpenAPI/event schemas и всех активных
   consumers вместе.

## Основные исходные материалы

- [Канонический OpenAPI](../../contracts/openapi/warehouse-service.yaml)
- [Warehouse controller](src/main/java/dev/buhanzaz/rwms/warehouse/api/WarehouseController.java)
- [Private lifecycle boundary](src/main/java/dev/buhanzaz/rwms/warehouse/api/WarehouseLifecycleController.java)
- [Warehouse application service](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseService.java)
- [Timezone history service](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseTimeZoneHistoryService.java)
- [Outbox relay](src/main/java/dev/buhanzaz/rwms/warehouse/eventing/WarehouseKafkaOutboxRelay.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/warehouse/config/WarehouseProductionSafetyValidator.java)
- [Outbox metrics](src/main/java/dev/buhanzaz/rwms/warehouse/eventing/WarehouseEventingMetrics.java)
- [Lifecycle schema](src/main/resources/db/migration/V5__warehouse_lifecycle.sql)
- [Карта runtime flows](../../docs/project-knowledge/runtime-flows.md)
