# Driver Fixture Bridge

`driver-fixture-bridge` — локальный dev/test harness для AUD-048. Он делает уже
подтверждённый сгенерированный логистический маршрут видимым в существующем
DriverApp, регистрируя канонические водительские задания в task-board. Это не
runtime-сервис, не production-публикация и не альтернативный логистический
домен.

Bridge вызывает только существующие source-owned endpoints:

- `POST /api/internal/task-board/v1/tasks` для идемпотентной регистрации;
- `GET /api/internal/task-board/v1/tasks/{externalTaskId}` перед очисткой;
- `POST /api/internal/task-board/v1/tasks/{externalTaskId}/cancel-if-pre-start`
  для безопасной очистки.

Он никогда не пишет в базу logistics и не изменяет подтверждённый план.

## Граница безопасности

Команда завершается до HTTP, если не выполнено хотя бы одно условие:

- `--environment` равен строго `dev` или `test`;
- передан `--fixture-mode`;
- hostname task-board является loopback либо содержит явную DNS-метку
  `dev`/`test`;
- план имеет `status=CONFIRMED`;
- корень и каждый экспортированный request имеют
  `source_system=WAREHOUSE_WORKLOAD_GENERATOR`;
- каждая клиентская точка маршрута разрешается в экспортированную generated
  planning task;
- каждая выбранная смена разрешается в активного водителя `ASSIGNED_DRIVER` с
  реальным task-board `external_worker_id`.

По умолчанию действует dry-run. HTTP включается только флагом `--execute`.
Bearer token читается из `DRIVER_FIXTURE_BRIDGE_TOKEN` (или из переменной,
названной через `--token-env`); аргумента командной строки для token намеренно
нет, token никогда не печатается.

Production-похожие URL и environment `production` отклоняются. Для удалённого
dev/test сервиса используйте выделенный hostname с меткой `dev` или `test`; для
внутреннего имени сервиса без такой метки используйте локальный port-forward.

## Входной fixture

UTF-8 JSON имеет `schema_version=1` и объединяет текущие read-модели, не создавая
отдельную постоянную схему:

- `plan`: подтверждённый `RoutePlanRead`, включая упорядоченные cycles и stops;
- `drivers`, `shifts` и `requests`: соответствующие массивы warehouse workspace,
  по которому был рассчитан план;
- `driver_queue_definition_id`: существующая активная queue definition
  `LOGISTICS_DRIVER`, спроецированная для fixture-склада;
- `source_system`: строго `WAREHOUSE_WORKLOAD_GENERATOR`.

Читаются только поля, нужные для валидации и построения канонической задачи;
лишние поля текущих API projections игнорируются. Каждый request должен
сохранять вложенные `tasks`. `task_id` точки маршрута должен ссылаться на одну
из них. Записи водителей должны сохранять `external_worker_id` и
`rwms_assignment_mode`.

Bridge создаёт одну каноническую задачу на route cycle. Детерминированный
`externalTaskId` — UUIDv5 от source system, склада, подтверждённого плана и
cycle. Поэтому точный повтор возвращает ту же регистрацию и не создаёт дубль.
Route steps сортируются по авторитетному числовому stop sequence; адрес,
координаты, количество/габариты/масса груза и плановые времена сохраняются в
существующих полях task text/work snapshot. Исходные warehouse ID и дата плана
становятся `warehouseId` и `scheduledDate`, а task-board worker ID водителя —
аудиторией `ASSIGNED_DRIVER`.

## Команды

Dry-run печатает точные канонические registration requests и не выполняет HTTP:

```bash
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode \
  publish --fixture /path/to/confirmed-generated-route.json
```

Публикация после проверки dry-run:

```bash
export DRIVER_FIXTURE_BRIDGE_TOKEN='<dev/test logistics-service token>'
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode --execute \
  publish --fixture /path/to/confirmed-generated-route.json
```

Регистрация нескольких cycles намеренно выполняется последовательно. Если
соединение оборвалось после принятия части заданий, повторите ту же команду:
стабильные external IDs делают принятые cycles идемпотентными, а изменённый
immutable input task-board отклонит.

Безопасная очистка выводит те же IDs, читает текущие task versions и отменяет
только задания, исполнение которых ещё не началось:

```bash
python3 tools/driver-fixture-bridge/driver_fixture_bridge.py \
  --environment test \
  --base-url http://localhost:8080 \
  --fixture-mode --execute \
  cleanup --fixture /path/to/confirmed-generated-route.json
```

Без `--execute` очистка тоже является dry-run. Outcomes `STARTED`, `COMPLETED`
или version conflict никогда не переписываются локально; владельцем исполнения
остаётся task-board.

## Необходимые тестовые identities

В executing environment уже должны существовать:

- dev/test service token с authenticated client существующего logistics-service
  и точным scope `task-board.logistics`;
- активная logistics-driver queue definition целевого склада;
- активные task-board worker identities и DriverApp credentials для
  `external_worker_id` каждого выбранного водителя.

Bridge не создаёт queues, workers, credentials, shifts или production orders.
Отсутствующие identities являются явной ошибкой подготовки fixture.

## Проверка

```bash
python3 -m unittest discover -s tools/driver-fixture-bridge -p 'test_*.py' -v
```
