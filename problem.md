# RWMS — журнал проблем полного аудита

Статус: исходный read-only аудит завершён на указанном ниже срезе репозитория.
После отдельной команды пользователя начат этап исправлений. Карточки аудита
ниже сохраняются как исходные доказательства, а фактическое состояние каждого
исправления, тестов, миграций и публикации ведётся в «Журнале исправлений».
Последнее обновление: 2026-09-01. Все новые подтверждённые дефекты сначала
добавляются в этот файл и не удаляются после устранения.

Этот файл является непрерывным реестром проблем и исправлений. Формулировки
разделены на подтверждённые дефекты, архитектурные риски, результаты
исправлений и вопросы, которые пока нельзя считать доказанными.

## Журнал исправлений

Исходные карточки аудита ниже сохраняются как историческое доказательство.
Текущий статус исправления фиксируется здесь отдельно и обновляется только по
фактическому коду и выполненным проверкам.

### 2026-09-01 — опубликованы журнал диспетчера и точный перенос доставки

- Статус: `ОПУБЛИКОВАНО`; commit и push не выполнялись. Рабочий адрес:
  <https://77-90-158-90.sslip.io/logistics-panel/>.
- В настройках логистики появился read-only пункт **«Журнал»**. Он объединяет
  фактические системные notices и неизменяемые решения логиста по времени и
  предупреждает об усечённом сервером окне истории. Отдельный дублирующий
  «Системный журнал» из рабочего экрана дня убран; новые факты также остаются в
  существующем центре уведомлений, а незавершённые действия и предложения — в
  своих операционных очередях.
- Перенос нераспределённой RWMS-доставки больше не принимает ручное окно
  времени: логист выбирает дату, получает свежие рассчитанные владельцем заказа
  слоты и подтверждает один точный слот. Команда ограждена версиями заявки,
  исходного плана, заказа, сессии и слота, а безопасный повтор quarantine
  принимает только точные `hold_id` и generation с прежним idempotency key.
- Spring-владелец до удалённого `PREPARE` запрещает вторую незавершённую saga
  того же бронирования/заказа, включая `QUARANTINED`. Маршрут рассчитывается вне
  commit-транзакции; короткий owner commit повторно проверяет mutable fences и
  атомарно сохраняет immutable receipt. После `OWNER_COMMITTED` восстановление
  использует этот receipt и движется только вперёд.
- Полный standalone gate: `546 passed, 6 skipped` (шесть сценариев требуют
  отдельный `VALHALLA_SYNTHETIC_URL`), Ruff, strict mypy, clean/upgrade/check
  Alembic до `20260901_0033` и byte-identical OpenAPI. Frontend: `274/274`,
  typecheck, lint и production build; независимый Luna final gate — `6/6` плюс
  typecheck. Spring: `59/59` focused, `2/2` PostgreSQL V87 и `960/960` полный
  модульный прогон, без failures/errors/skips.
- На VPS logistics-service использует JAR SHA-256
  `252f24cbddbf58d1ac5015efaf89aa9990ef27091158e1d9ed0e326e33542bce`;
  Flyway успешно применил V87. Standalone backend работает на образе
  `97f8a01f…eb78bd5`, frontend — `5edc8740…e5930c`; публичный
  `index-GanfVSVF.js` совпал по SHA-256
  `ceec30464056badc2b39ee7f7572facb6dddd901a28d560d3f56be6ba3c3e426`.
  Оба контейнера healthy с `RestartCount=0`, Spring active с `NRestarts=0`;
  Alembic live-head — `20260901_0033`.
- Перед публикацией созданы и проверены через `pg_restore --list` три dump:
  standalone `23a2a45e…776d1`, Spring `a312f52f…f288`, task-board
  `30bdca29…f9cb`. Manifest точных source/artifact hashes сохранён в
  `/var/backups/rwms/dynamic-logistics-deploy-20260901T193609Z.manifest` с
  SHA-256 `dacc8b14…f238`; rollback-образы и прежний Spring JAR сохранены.
- Свежий Chromium через публичный Nginx получил новый JS/CSS с HTTP 200,
  штатно перешёл к `/auth/login` и показал «Вход — WMS Panel» без console
  warnings/errors. Авторизованное изменение реального заказа без
  пользовательской сессии намеренно не выполнялось.

### 2026-09-01 — финальная публикация исправлений standalone-логистики

- Статус: `ОПУБЛИКОВАНО И НЕЗАВИСИМО ПРОВЕРЕНО ДО ГРАНИЦЫ ВХОДА`; commit и
  push не выполнялись. Актуальный адрес:
  <https://77-90-158-90.sslip.io/logistics-panel/>.
- Frontend работает на образе `af038241…3035e96`, backend — на
  `7731b524…84a6815`; оба контейнера healthy, `RestartCount=0`. Публичный
  bundle `index-CrOlHI2j.js` имеет SHA-256
  `24823b77f4a05c2390224f2b253ee7a070a3e7809f7a59c2e4d168d24cfc5c13`.
  Перед заменой сохранён проверенный PostgreSQL dump
  `standalone-logistics-predeploy-20260901T1405Z.dump` с SHA-256
  `f491e74f7a5cf9025f0b15ce7c95fb694a9063d69989980d8bddae75b78bc8ed`
  и отдельные rollback-теги предыдущих frontend/backend образов.
- Закрыт пакет операторских дефектов: выбранный представительский склад больше
  не сбрасывается к опорному на карте; после reload восстанавливаются точный
  склад, его дата, viewport, меню, режим, инструмент/слои карты и фильтр смен.
  Сохраняются только presentation preferences, а не серверные планы или
  заявки. Смены получили фильтры «Все / Активные / Неактивные» и локальную
  проверку фактического пересечения водителя/машины до отправки конфликтующей
  команды.
- Смена даты стрелками и сохранение условий доставки/вывоза оставляют оператора
  в том же разделе. Кнопки дат растягиваются по высоте и переносят текст в
  узком сайдбаре. В операционном контуре удалены лишнее имя склада, пояснение
  constraint и видимая версия режима. Проверка слотов рассчитывается
  автоматически без отдельной команды «Проверить», называется «Поиск слота
  для нового заказа», не показывает backend enum и выводит причины по-русски;
  фраза «изохрона склада» удалена из текста стоимости.
- Исправлена причина зависания построения маршрута: подготовка routing matrix и
  проверка точных кандидатов получили отдельные ограниченные wall-clock фазы,
  а прерванный кандидат больше не считается проверенным решением.
- Исправлен сценарий 2 сентября: явное удаление тестовой нагрузки теперь может
  удалить неутверждённые смешанные ревизии плана, если они действительно
  ссылаются на generator-owned задания, при этом реальные RWMS/manual заявки
  не удаляются. Утверждённый план по-прежнему блокирует удаление, а
  автоматическая перегенерация сохраняет более строгую защиту.
- Текущий read-only снимок живой БД на 2 сентября: активный `GENERATED` план
  содержит 4 реальные доставки и 5 тестовых вывозов; архивная ревизия также
  смешанная. Штатная destructive-команда удаления не запускалась в обход
  авторизации: для её аудита требуется пользовательский JWT. После входа
  опубликованный endpoint удалит 5 generator-owned заявок и обе связанные
  неутверждённые ревизии, сохранив реальные доставки для повторного расчёта.
- Полный standalone backend gate: clean Alembic upgrade до `20260901_0031`,
  `alembic check` без новых операций, `527 passed, 6 skipped`, Ruff, strict
  mypy по 94 source-файлам и byte-identical OpenAPI. Независимый Luna UI gate:
  `107/107`, typecheck, lint и production build. Отдельный Luna Chromium открыл
  опубликованный URL, автоматически дошёл до штатного `/auth/login`, показал
  «Вход — WMS Panel», не показал старый dead-end «Сервис недоступен / Войдите
  в RWMS» и завершился с `0` console errors/warnings. Авторизованный
  data-mutating browser-flow без предоставленной пользовательской сессии не
  заявляется как выполненный.
- Ранее незавершённый Spring full-gate закрыт на том же замороженном срезе:
  `task-board-service` — `353` теста, `0` failures/errors, `1` skip;
  `logistics-service` — `920` тестов, `0` failures/errors/skips. В наборах
  выполнены Flyway clean/upgrade/validation, JPA `ddl-auto=validate`, OpenAPI
  parity и recovery/saga сценарии. Source manifest до и после совпал.
  Работающие JAR уже являются проверенными release-артефактами: manifest
  task-board совпал для `412/412` файлов, logistics — для `720/720`; активные
  systemd-юниты используют именно их, имеют `NRestarts=0`, поэтому бессмысленный
  повторный restart не выполнялся. После снятия тестовой нагрузки у logistics
  нет WARN/ERROR; task-board пишет только неошибочные Kafka rebalance WARN без
  exception и продолжает работать.

### 2026-09-01 — корректировка runtime-аутентификации standalone-логистики

- Статус: `ИСПРАВЛЕНО НА VPS И ПРОВЕРЕНО ДО ОПЕРАЦИОННОГО API`; commit и push
  не выполнялись.
- Предыдущая запись ниже ошибочно назвала SSO-блокер закрытым: свежий Chromium
  дошёл только до `/auth/login`, но не проверил callback, Bearer-запрос и
  загрузку данных. При реальном входе backend продолжал возвращать `401
  AUTHENTICATION_REQUIRED`, поэтому оператор видел «Сервис недоступен» и
  «Войдите в RWMS, чтобы открыть логистику».
- Фактическая причина подтверждена внутри контейнера: standalone backend
  получал валидный JWT, но `PyJWKClient` завершался timeout при обращении к
  недоступному с private bridge адресу
  `host.docker.internal:8088/auth/oauth2/jwks`. Любая ошибка получения ключа
  намеренно преобразовывалась в одинаковый безопасный `401`.
- Существующий Docker-only nginx bridge расширен точным JWKS-маршрутом
  `http://172.21.0.1:19002/oauth2/jwks`; VPS overlay теперь использует его по
  умолчанию. Публичный `AUTH_ISSUER` не менялся и по-прежнему точно совпадает с
  `iss`; токены, роли, scopes, warehouse grants, контракты и БД не изменялись.
- После `nginx -t`, reload и пересоздания только standalone backend тот же
  контейнерный verifier успешно проверил реальный подписанный token. Неподходящий
  service-token дошёл до principal policy и получил ожидаемый `403
  ACCESS_DENIED`, а не ложный `401`.
- Живой интерактивный `rwms-panel` сеанс после исправления получил `200` для
  `/warehouses`, `/warehouses/available`, workspace, planning-day, operations
  и `plans/ensure`; прежние запросы того же пользовательского потока до
  исправления возвращали `401`. Backend и frontend остались healthy с
  `RestartCount=0`.
- Регрессия: backend security `6/6` без DB-mutating integration-сценариев,
  Ruff и mypy прошли; независимый Luna UI gate — `5/5`, typecheck и ESLint.
  Свежий Chromium подтвердил обычный OIDC redirect, HTTP `200` для bundle/auth
  ресурсов, `302` authorize и отсутствие console errors. Проверка реального
  пользовательского API подтверждена отдельным live access/backend журналом,
  а не только login-экраном.

### 2026-09-01 — публикация динамической логистики и закрытие runtime-блокеров

- Статус: `ОПУБЛИКОВАНО НА VPS И ПРОВЕРЕНО`; commit и push не выполнялись.
- Standalone-диспетчер опубликован по
  <https://77-90-158-90.sslip.io/logistics-panel/> отдельно от основной панели
  `/logistics/**`. Backend работает на образе `e5485c2d…`, frontend — на
  `0147fc69…`; оба контейнера healthy, `RestartCount=0`, Alembic остаётся на
  additive head `0031`.
- Исправлен ложный блокер «Требуется вход в RWMS» в новой вкладке. При отсутствии
  session-scoped токена standalone теперь автоматически запускает обычный OIDC
  flow клиента `rwms-panel`; общая RWMS Auth-сессия завершает SSO через общий
  callback, без переноса access/refresh token в `localStorage`. Независимый
  свежий Chromium дошёл до `/auth/login`, не показал старый блокер, загрузил
  опубликованный `index-Cz09MckF.js` с HTTP 200 и не зафиксировал console errors.
- Spring logistics-service опубликован с JAR `ba743958…`, активен с
  `NRestarts=0`; Flyway подтвердил все 86 миграций и JPA поднялся на существующей
  схеме. Query времени к warehouse-service теперь сохраняет точный instant, но
  сериализует положительный offset как UTC `Z`, поэтому `+` больше не
  превращается downstream в пробел. Явный runtime-probe тем же
  `logistics-planner` клиентом вернул 4 склада и 1 support-link для входного
  времени `+03:00` без прежнего `409`.
- Ранее найденные runtime-дефекты этого выпуска также закрыты: PostgreSQL
  timestamp binding, недопустимый JPA `@Lock` на native query и отсутствие
  `travelZoneHours` в standalone-модели. После повторного старта standalone нет
  `support-links`, `travelZoneHours`, demand-ingestion или auto-planning
  traceback; health возвращает 200.
- Финальные проверки исправлений: standalone backend `102/102`, Ruff и strict
  mypy; OIDC UI `5/5`, typecheck, lint и production build; Spring dependency
  gate `44/44`. Дополнительный полный Spring rerun был прерван окружением на
  длительном Flyway-классе без итогового Gradle summary, поэтому не заявлен как
  успешный; предыдущий полный срез до точечной query-сериализации прошёл
  `920/920`, а новый двухточечный boundary покрыт указанными `44/44`.
- До замены сохранены PostgreSQL backups затронутых сервисов, предыдущий Spring
  JAR и точный static rootfs standalone frontend. Откат миграций не требуется:
  все изменения данных additive.

### 2026-09-01 — завершение AUD-046

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Успешная реальная `CONTRACTOR_HANDOFF`-команда теперь сохраняет точные UUID
  task-board и непрерывную позицию каждой заявки. После перезагрузки standalone
  dispatcher собирает только полностью доказанный ordered-маршрут и создаёт
  ссылку исключительно по явному действию **Скопировать маршрут**; generated
  workload, неполная или смешанная проекция ссылку не получают.
- Logistics-service владеет сроковой, отзывной и idempotency-fenced capability
  для одного активного подрядчика и 1–50 точных задач. Публичная Panel-страница
  не требует учётных данных подрядчика, но получает только разрешённые адрес,
  контакт, груз, инструкции и изображения. Она выполняет существующие
  task-board `START`/`COMPLETE`, причём начать можно только первый незавершённый
  этап всего маршрута, а не первый этап каждой отдельной задачи.
- Evidence загружается с устойчивым UUID, временем съёмки, SHA-256 и точным
  размером. Gateway ограничивает тип/размер до proxy и заменяет внутреннее
  утверждение длины; logistics повторяет boundary и проверяет фактические bytes;
  task-board владеет резервом/готовностью evidence, media-service — приватными
  bytes и вариантами. Повторы сходятся на тех же identities, а expired,
  revoked, reassigned и cross-worker ссылки не раскрывают существование данных.
- Persisted изменения: logistics Flyway
  `V81__contractor_route_shares.sql` и standalone Alembic
  `20260901_0030_contractor_route_task_identities.py`. Отдельная сущность
  маршрута, машина, смена или цикл подрядчика не создавались.
- Проверки: task-board `26/26`; media focused `17/17`, API/contract `201/201`,
  architecture `3/3` и build; logistics-service `40/40`; gateway `11/11`;
  standalone backend `45/45` плюс Ruff, strict mypy, clean Alembic
  upgrade/check и OpenAPI; независимый Luna standalone UI `20/20`, typecheck и
  scoped ESLint; независимый Luna public Panel `12/12` и typecheck. Frozen
  hashes и scoped diff checks совпали; task-owned процессы очищены.
- Технически подтверждённых исправлений больше не осталось. `AUD-014`,
  `AUD-015` и `AUD-017` остановлены на согласованной границе: для них нужны
  продуктовые/эксплуатационные решения пользователя. Runtime, VPS, БД, APK,
  commit, push и публикация не выполнялись.

### 2026-08-31 — завершение AUD-011 / AUD-038

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-011`: asset-service теперь владеет durable intent создания бытовки с
  точным ordered SHA-256 manifest фотографий, удерживает бытовку недоступной до
  READY-медиа и поддерживает version/idempotency-fenced complete/abandon.
  Panel создаёт intent до загрузки, использует серверные folder/command IDs,
  проверяет cabin/warehouse/manifest, ждёт READY и не объявляет успех при
  partial failure. Незавершённое создание видно после повторного открытия;
  продолжение требует повторно выбрать точные исходные bytes, а явное
  прекращение сохраняет бытовку и уже загруженные фото.
- Проверки `AUD-011`: server-side focused/contract gate `54/54` и отдельный
  Flyway/JPA/OpenAPI/security gate `7/7`; Panel focused Vitest `50/50` и
  typecheck. Отдельный read-only Luna gate повторно подтвердил `50/50`,
  typecheck и неизменность 11 frozen-файлов/обоих контрактов. Безопасного
  готового browser target для этого flow в WIP не найдено, поэтому strongest
  доступной UI-проверкой были component interaction tests.
- `AUD-038`: logistics-service владеет version-fenced cancellation и атомарной
  заменой delivery slot подтверждённого booking, освобождает связанные
  ресурсы через durable recovery и возвращает клиенту фактический статус и
  nullable стоимость отмены. CustomerApp использует booking-scoped search,
  process-durable idempotency для effect-команд, показывает русские состояния
  и не изображает отсутствующую стоимость как `0 ₽`. Неудачный перенос не
  меняет старый слот; успешный сразу закрывает старый dialog/offers до
  authoritative refresh.
- Проверки `AUD-038`: backend focused `48/48` и Flyway/JPA/OpenAPI/security
  `7/7`; CustomerApp focused `30/30` плюс `:app:compileDebugKotlin`. Отдельный
  регресс покрывает очистку stale reschedule dialog после успешного ответа и
  неизменность исходного state до подтверждённого результата.
- После закрытия этих карточек технически открытым остаётся `AUD-046`;
  `AUD-014`, `AUD-015` и `AUD-017` требуют отдельного продуктового или
  эксплуатационного решения пользователя. Runtime, VPS, APK, commit, push и
  публикация не выполнялись.

### 2026-08-31 — завершение AUD-036

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Logistics-service перед публикацией смены без side effects сопоставляет точный
  межскладской перегон только с transfer в состояниях
  `CONFIRMED`/`RESERVED`/`READY`, у которого совпадают источник, назначение,
  назначенный водитель, автомобиль и плановые instant отправления/прибытия.
  Owner добавляет парные `TRANSFER_LOAD`/`TRANSFER_UNLOAD` с каноническим
  `sourceTransferId`; planner не выбирает груз и не меняет остатки.
- Canonical contracts и task-board V42 сохраняют effective cabin capacity и
  transfer identity. Task-board проверяет endpoints, уникальность/баланс пар и
  загрузку на каждом участке. Transfer только с мебелью сохраняет реальные
  load/unload actions с нулевой дельтой количества бытовок.
- Для точного назначенного transfer-задания переход task-board в `CURRENT`
  запускает существующий version-fenced departure. После completion evidence
  durable relay сначала фиксирует source-side обложку бытовки, затем выполняет
  фактический arrival и не завершает локальное задание раньше transfer owner.
  Retry использует стабильные производные command keys. Shared pool без
  канонически назначенного водителя не угадывает фактического исполнителя и
  сохраняет прежний task-board-only flow.
- DriverApp показывает операции в общей последовательности маршрута и точную
  известную вместимость, не создавая отдельный mobile API или вторую offline
  state machine.
- Проверки: logistics focused `87/87`, Flyway `49/49`, transfer saga `11/11`;
  task-board shift `12/12` плюс Flyway/state/contract `34/34`; planner `5/5` и
  Ruff; DriverApp network/presentation `65/65`. Все указанные проверки без
  failures/errors/skips.
- После закрытия AUD-036 остаётся 6 подтверждённых открытых карточек:
  `AUD-011`, `AUD-014`, `AUD-015`, `AUD-017`, `AUD-038`, `AUD-046`.
  `AUD-014`, `AUD-015`, `AUD-017` требуют отдельного решения пользователя.
  Runtime, VPS, commit, push и публикация не выполнялись.

### 2026-08-31 — пакет AUD-062 / AUD-063

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-062`: logistics-service теперь владеет versioned историей резервов и
  оперативного базирования автомобиля. Автомобиль рейса и автомобиль,
  остающийся на destination, хранятся как разные роли `TRIP_ONLY`, `TEMPORARY`
  или `PERMANENT`; per-vehicle advisory locks, optimistic version и DB
  constraints запрещают пересечение и два активных базирования. Создание
  черновика не меняет каталог: `PLANNED` становится `IN_TRANSIT` только при
  departure, а destination становится оперативным складом лишь после
  фактического arrival. История предыдущего базирования закрывается атомарно.
- Private planning endpoint возвращает chain-closed live snapshot без
  terminal rows. Standalone planner накладывает его на workspace, shift и
  local/support candidates, но не перезаписывает home warehouse автомобиля;
  конфликтующий или неполный snapshot закрывает планирование ошибкой.
- Transfer без бытовок больше не застревает в подтверждённом черновике:
  resource/furniture-only plan имеет version/idempotency-fenced команды whole
  transfer `depart` и `arrive`, использует тот же workflow и не создаёт
  фиктивную physical cabin line. ManagerApp показывает эти действия только для
  подходящего состояния и доступа.
- `AUD-063`: Panel использует канонический бессрочный профиль наёмного водителя
  без устаревших дат; дата относится к отдельной dispatch-команде. Подробный
  результат сохранён ниже в самостоятельной записи `AUD-063`.
- Проверки `AUD-062`: принудительный Spring gate — `33/33`; canonical contract
  — `26/26`; planner policy/client — `10/10`, clean-DB integration — `15/15`
  (расширенный focused gate — `20/20`); независимый ManagerApp gate — `38/38`.
  Во всех указанных gate — 0 failures/errors/skips; scoped `git diff --check`
  прошёл, task-owned Gradle/Kotlin/container процессы очищены.
- Runtime, VPS, БД, commit, публикация и deploy не выполнялись. Защищённые
  параллельные изменения Panel и CustomerApp не затрагивались.
- После этого пакета остаётся 7 подтверждённых открытых карточек:
  `AUD-011`, `AUD-014`, `AUD-015`, `AUD-017`, `AUD-036`, `AUD-038`, `AUD-046`.
  `AUD-014`, `AUD-015` и `AUD-017` требуют отдельного продуктового или
  эксплуатационного решения пользователя и не будут реализованы по
  предположению.

### 2026-08-31 — пакет AUD-015 / AUD-016 / AUD-036

- Статус: `AUD-016 ИСПРАВЛЕНО В WIP; AUD-015/AUD-036 СУЖЕНЫ И ЧАСТИЧНО
  ИСПРАВЛЕНЫ; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Применённый план теперь публикует одну versioned ordered operation sequence:
  физический старт, входящий межскладской перегон, сохранённые складские и
  клиентские stops, обратный перегон, точные aware arrival/departure, load chain
  и полный пробег с positioning. Logistics повторно проверяет support link и
  публикует shift snapshot только после успешного apply/replay всех назначений
  этого водителя/даты. Task-board V41 хранит операции как неизменяемых детей
  replaceable-until-frozen plan и возвращает их в `TodayDriverShift`.
- DriverApp показывает planned ETA/загрузку до старта, через лист «Маршрут» при
  активных заданиях и при закрытии. Время переводится по валидному IANA timezone
  склада смены; при плохой metadata сохраняется offset timestamp, а Moscow
  глобально не подставляется. Это applied-plan ETA, не live/actual ETA.
- Остаток `AUD-015`: будущий shared-trip preview до claim всё ещё date-only;
  dynamic/actual ETA и stale-policy не имеют владельца. Остаток `AUD-036`:
  отдельный interwarehouse transfer cargo пока не объединяется с операциями
  этой смены и не получает общий actual load/unload execution stream; snapshot
  покрывает positioning и planner customer/depot stops без мутации inventory.
- `AUD-016`: все четыре Android release graph теперь fail-closed без полного
  внешнего signing properties/keystore. Все четыре download surface проверяют
  channel, package/version, immutable URL, source provenance, APK SHA-256 и
  единственный signer по pinned policy. CustomerApp/WorkerApp имеют production
  pins; ManagerApp/DriverApp остаются только `INTERNAL_TEST`, а пустой production
  allowlist блокирует публикацию до provision проверенного сертификата.
- Проверки route-блока: Python `50 passed` + `20` DB-env skips, Ruff и strict
  mypy; logistics-service `54/54`; task-board route/Flyway `28/28` и event-store
  `8/8`, затем root combined clean/frozen/Flyway gate `36/36`; DriverApp contract/UI
  `13/13` и финальный presentation gate `12/12`. Полная debug-сборка DriverApp
  прошла (`259` Gradle tasks); локальный APK имеет package
  `dev.buhanzaz.rwms.driver.debug`, version `0.1.20-debug`, один debug signer и
  SHA-256 `53fe57937f60b5fe3504f5531dc9db8f1bda8bdbfdf320d504441d733f6daf35`.
- Проверки release trust: `20/20` policy tests, `4/4` real published APK
  validations, debug graph `4/4`, ожидаемый fail-closed без signing `8/8` для
  APK/bundle и valid external signing graph `4/4`.
- Runtime, VPS, database update, commit, публикация и deploy не выполнялись.
  Новый DriverApp APK не подменял опубликованный manifest; защищённые Panel и
  CustomerApp UI-изменения другого диалога не затрагивались.
- После этого пакета остаётся 8 подтверждённых открытых карточек:
  `AUD-011`, `AUD-014`, `AUD-015`, `AUD-017`, `AUD-036`, `AUD-038`, `AUD-046`,
  `AUD-062`.

### 2026-08-31 — пакет AUD-025 / AUD-055

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-025`: обычное покрытие и цена по-прежнему принадлежат лестнице изохрон,
  а `SPECIAL_PRICE`, `FORBIDDEN` и `NO_TRAILER` восстановлены как отдельные
  warehouse-owned versioned MultiPolygon-политики. Standalone planner хранит и
  редактирует их через warehouse-scoped CRUD, включает в детерминированную
  capacity revision, применяет к mutable demand и активным hold, но не
  переписывает подтверждённую операционную историю. Spring consumer атомарно
  заменяет bounded projections и использует их при search, hold и confirm.
- Несовместимое сохранённое задание на две бытовки внутри `NO_TRAILER` не
  делится скрыто и не теряется: planner возвращает структурированную причину
  `NO_TRAILER_POLICY_INCOMPATIBLE_PART`. Подтверждённая нагрузка остаётся
  занятой capacity даже после последующего изменения полигона.
- Alembic 0029 и Flyway V76 создают новые policy projections пустыми. Они не
  могут восстановить геометрию, ранее физически удалённую migrations 0020/V74;
  возврат старых полигонов возможен только из внешней резервной копии.
- `AUD-055`: cancel/remove-unit теперь сначала фиксируют durable intent, затем
  выполняют release units и furniture как отдельные идемпотентные шаги со
  стабильными ключами и receipts вне локальной транзакции. Финальный переход
  заказа и публичный receipt коммитятся атомарно. Потерянный ответ подхватывает
  bounded `SKIP LOCKED` recovery с DB-time lease, backoff и quarantine; открытая
  команда запрещает конфликтующую мутацию того же заказа. Exact replay также
  возвращает уже завершённый результат, если параллельный запрос успел
  завершить команду между первым lookup и подготовкой intent.
- Standalone gate: `33/33`, Ruff, strict mypy по 83 файлам, чистый upgrade
  0028→0029, downgrade/re-upgrade и `alembic check`. OpenAPI gate: `24 passed`,
  11 PostGIS tests пропущены только в export-окружении без `TEST_DATABASE_URL`;
  две генерации совпали byte-for-byte, существующие операции не изменены.
- Spring gate: `58/58` contract/domain tests и `3/3` clean/upgrade Flyway + JPA
  validation до V77; после финального replay-race review повторно прошли
  `25/25` recovery/replacement/furniture tests. Независимый Luna UI gate:
  `10/10`, targeted lint, typecheck и production build. Полный frontend lint блокирует только
  предшествующая несвязанная ошибка импорта в `tests/planning-ui.test.tsx`;
  сам этот test отдельно прошёл `19/19`.
- Runtime, VPS, БД, commit и публикация не выполнялись. Параллельные изменения
  Panel, CustomerApp и Android не затрагивались.
- После этого пакета остаётся 9 подтверждённых открытых карточек:
  `AUD-011`, `AUD-014`, `AUD-015`, `AUD-016`, `AUD-017`, `AUD-036`, `AUD-038`,
  `AUD-046`, `AUD-062`.

### 2026-08-31 — пакет AUD-018 / AUD-029 / AUD-047

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-018`: return/shipment/transfer list endpoints сохранили совместимый
  JSON-array body, получили bounded `page`/`size` и pagination headers; одна
  страница document lines читается одним batch query вместо N+1.
- `AUD-029`: support link и календарь больше не создают клиентскую capacity.
  Представительский `DURING_DAY` доступен только при подтверждённой локальной
  route-capacity; внешний ресурс остаётся topology до появления durable
  reserved-capacity token.
- `AUD-047`: presentation booking и receipt-bearing checkout используют
  persisted due schedule, короткие PostgreSQL-time leases, bounded
  `SKIP LOCKED` claims, backoff и quarantine после восьмой ошибки. Remote effect
  выполняется вне claim transaction, stale worker fenced, а fixed-name gauges
  показывают backlog, oldest age и quarantine.
- Flyway V75 обратно совместимо добавляет recovery columns, constraints и
  индексы; legacy presentation rows с восемью попытками сразу quarantined.
- Независимый финальный gate прошёл `146/146` в 13 suites: contract/API,
  pagination projection, representative policy/search/estimate/hold, оба
  recovery workflow, concurrency, метрики, clean Flyway V1—V75 и JPA validate.
  `git diff --check` прошёл; Gradle/Kotlin daemon после проверки не осталось.
- Runtime, VPS, БД, commit и публикация не выполнялись. Параллельные изменения
  Panel, CustomerApp, Android и standalone planner не затрагивались.
- После этого пакета остаётся 11 подтверждённых открытых карточек:
  `AUD-011`, `AUD-014`, `AUD-015`, `AUD-016`, `AUD-017`, `AUD-025`, `AUD-036`,
  `AUD-038`, `AUD-046`, `AUD-055`, `AUD-062`.

### 2026-08-31 — пакет AUD-058

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Warehouse-owner теперь отклоняет точную координатную пару `0,0` при создании,
  полной замене и persistence normalization. Пустая пара остаётся допустимой,
  а точка на одной нулевой оси остаётся валидной, если вторая координата
  ненулевая.
- Старые строки автоматически не переписываются: CustomerApp их не показывает
  и не принимает, Java directory публикует `routingReady=false`, а standalone
  reconciliation снимает локальную готовность или использует существующий
  геокодер только при получении реальной точки. Нулевой ответ геокодера также
  не создаёт workspace.
- Panel отклоняет `0,0` до отправки с понятным русским сообщением. JSON shape,
  владельцы, JPA mapping, Flyway/Alembic schema и CustomerApp-код не менялись.
- Независимые gate: warehouse-service `24/24`, logistics-service `45/45`,
  standalone `3/3` плюс Ruff и strict mypy, Panel `10/10` плюс typecheck. Все
  scoped `git diff --check` прошли; runtime, VPS, БД, commit и публикация не
  выполнялись.
- После закрытия `AUD-058` остаётся 14 подтверждённых открытых проблем.

### 2026-08-31 — пакет AUD-061

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Домашний склад профиля и `warehouse_id` DriverApp JWT остаются неизменными и
  используются для доказательства личности, а не как выбираемый оперативный
  склад. Перед созданием новой смены task-board выводит текущий склад из
  server-owned истории назначений.
- Только временное назначение `ACTIVE` или завершённое постоянное назначение
  может создать смену на складе назначения. `PLANNED` и `IN_TRANSIT` не делают
  водителя прибывшим и не раскрывают destination shift.
- Дата смены и warehouse metadata берутся по IANA timezone оперативного склада.
  Все команды дополнительно проверяют точного водителя и его домашний claim, но
  фотографии и прочие эффекты сохраняются в складе замороженной смены.
- Уже созданная незакрытая смена восстанавливается по точному `driverId +
  shiftId` после окончания назначения, поэтому перезапуск приложения или
  задержка закрытия не блокируют state machine.
- Контракт, JPA-модель, Flyway и Android-код не менялись. Независимый focused
  gate task-board прошёл `7/7`; scoped `git diff --check` прошёл. Runtime, VPS,
  APK, БД, commit и публикация не выполнялись.
- После закрытия `AUD-061` остаётся 15 подтверждённых открытых проблем. Ближайшее
  независимое P1-ограничение representative flow — ложное успешное перемещение
  автомобиля без owning aggregate (`AUD-062`); его нельзя честно завершить без
  решения о владельце operational vehicle placement.

### 2026-08-31 — пакет AUD-020 / AUD-035 / AUD-049 / AUD-059 / AUD-060

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-020`: planning request теперь содержит не только список бытовок, но и
  точную пару `unitId + inventorySourceWarehouseId` для каждого физического
  экземпляра. Эта карта входит в `sourceRevision`, переносится в assignment и
  повторно проверяется владельцем заказа при резервировании; смешанный или
  подменённый источник отклоняется до складского эффекта.
- `AUD-035`: cross-warehouse shift сохраняет отдельно обслуживаемый склад,
  фактический склад старта маршрута и использованную support-link. Standalone
  planner учитывает позиционирование ресурса, а canonical apply проверяет
  допустимость связи, не подменяя домашний склад водителя.
- `AUD-049`: анонимная регистрация до BCrypt и создания пользователя расходует
  durable PostgreSQL budget одновременно по источнику и глобально. Gateway
  отбрасывает подставленный forwarding chain и передаёт непосредственный TCP
  peer; превышение возвращает русский Problem Details, стабильный code,
  `429` и `Retry-After`. Ошибка хранилища закрывает регистрацию, а не отключает
  защиту. Ограничение доверенного внешнего ingress остаётся дополнительным
  уровнем защиты.
- `AUD-059`: DriverApp получает remote-warehouse task только при точном
  назначении этому активному водителю; общий пул другого склада не раскрывается.
  Detail/action/evidence определяют физический warehouse задания на сервере,
  а завершение смены учитывает точные задания водителя за дату независимо от
  склада очереди. Домашний склад и JWT scope не меняются.
- `AUD-060`: shift plan больше не публикуется до проверки assignment. В
  task-board отправляется только состав, подтверждённый владельцем заказов;
  полностью отклонённый план не создаёт пустую замороженную смену, а повторное
  применение остаётся идемпотентным.
- Проверено независимо: standalone backend — Ruff, strict mypy и `73/73`;
  logistics-service — `52/52`; task-board — `90/90`; auth-service — `31/31`;
  api-gateway-service — `28/28`. Canonical contract hash и scoped
  `git diff --check` прошли.
- Runtime, VPS, БД, APK и опубликованные артефакты не обновлялись. Изменения
  CustomerApp и Panel из параллельного WIP не затрагивались.
- После этого пакета по последнему подтверждённому счётчику остаётся 16
  открытых проблем; отдельно сохраняются продуктовые/организационные пробелы и
  инструментально заблокированная JPA-часть `AUD-058`.
- Известное ограничение: DriverApp REST revision видит remote assignment, но
  SSE task-board пока остаётся home-warehouse invalidation-каналом. Поэтому
  remote изменение гарантированно подхватывается REST refresh/polling, но не
  обязано немедленно прислать отдельное SSE-событие в домашний канал.
- Основные доказательства:
  `contracts/openapi/logistics-service.yaml`,
  `logistics/backend/app/integrations/rwms_sync.py`,
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java`,
  `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java`,
  `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/CustomerRegistrationThrottle.java`.

### 2026-08-31 — пакет AUD-012 / AUD-023 / AUD-028 / AUD-048

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-012`: передача реальной доставки подрядчику стала durable saga.
  Неизменяемая команда и локальные резервы фиксируются до RWMS-вызова,
  удалённый HTTP выполняется без локальной транзакции и row locks, а успех,
  отказ и повтор сохраняются короткими отдельными транзакциями. Потерянный
  ответ повторяется с тем же UUID/idempotency key. Смешанный
  `applied + rejected` не выдаётся за успех и не освобождает уже частично
  применённый груз: команда остаётся `PENDING` для сверки, планы и резервы
  сохраняются, в БД попадают только bounded reason codes.
- `AUD-023`: task-board, Panel, ManagerApp и standalone logistics больше не
  выводят оперативную дату из UTC, `Europe/Moscow` или timezone устройства.
  Fallback-дата задания, видимость «сегодня», подтверждение отгрузки,
  просрочка, дата нового перемещения и дата workspace определяются по IANA
  timezone конкретного выбранного/исходного склада. Standalone не запускает
  workspace, autoplan или day-status до инициализации правильной даты после
  переключения склада.
- `AUD-028`: customer slot/estimate path больше не закрывает весь день после
  30 активных точек. До 32 точек сохраняется один запрос Valhalla; от 33 до
  128 точек точная направленная truck-матрица собирается из прямоугольных
  блоков не более `32 x 32` и кэшируется как единый результат. Более крупная
  нагрузка возвращает явный `CUSTOMER_DELIVERY_WORKLOAD_LIMIT`, а не ложное
  отсутствие слотов.
- `AUD-048`: добавлен только dev/test fixture bridge, который из уже
  подтверждённого полностью generator-owned плана создаёт детерминированные
  canonical task-board задания для DriverApp. По умолчанию это dry-run;
  production URL/environment, смешанные RWMS/manual данные и неподтверждённый
  план отклоняются до HTTP. Cleanup version-fenced и не отменяет начатое
  исполнение.
- Проверено: contractor backend — `16/16`, Ruff и strict mypy; Alembic
  `0028 -> 0027 -> 0028` и `alembic check`; customer routing — `22/22`;
  task-board — `2/2`; ManagerApp — `1/1`; fixture bridge — `7/7` и
  `py_compile`. Независимый Luna gate подтвердил Panel `40/40`, standalone
  logistics `19/19` и оба TypeScript typecheck; frozen UI hashes до и после
  совпали. Scoped `git diff --check` прошёл.
- Runtime, БД VPS, APK и опубликованные артефакты не обновлялись. Параллельный
  WIP CustomerApp и клиентского представления Panel сохранён и не
  переформатировался.
- После этого пакета остаётся 21 подтверждённая открытая проблема; отдельно
  сохраняются организационные/продуктовые пробелы и инструментально
  заблокированная JPA-часть `AUD-058`.
- Основные доказательства:
  `logistics/backend/app/services/contractor_assignment.py`,
  `logistics/backend/migrations/versions/20260831_0028_contractor_handoff_commands.py`,
  `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java`,
  `logistics/frontend/src/app/App.tsx`,
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/routing/ValhallaCustomerTravelTimeClient.java`,
  `tools/driver-fixture-bridge/driver_fixture_bridge.py`.

### 2026-08-31 — пакет AUD-009 / AUD-010

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-009`: task-board больше не обещает и не принимает
  `Last-Event-ID` для WorkerApp/DriverApp. SSE остаётся in-memory
  invalidation-сигналом; reconnect открывает новую подписку и запускает
  authoritative REST refresh. Локальные event IDs сохраняются только для
  deduplication/audit, устаревшие cursor-only DAO-запросы и CORS allow-header
  удалены, Room schema не менялась.
- `AUD-010`: отдельный private media-read после проверки подписанного
  presentation membership читает точную закреплённую SMALL/LARGE generation
  через retained cabin/warehouse/media association и pinned MinIO object
  version. Ранее созданное представление переживает generation advance и soft
  delete; обычная галерея и создание нового представления по-прежнему требуют
  active folder, `READY` и current generation. Несовпадения tuple дают
  одинаковый opaque `404`, публичный media-доступ не расширен.
- Независимо подтверждено: task-board contract/controller `2/2` и CORS
  integration `1/1`; WorkerApp и DriverApp — по два focused reconnect/request
  теста и compile обоих core-database модулей на JDK 17. Media — API `3/3`,
  contract `1/1`, PostgreSQL persistence `2/2` без skip; интеграция покрывает
  смену generation, soft delete и неверные cabin/warehouse/media/generation/
  variant. Media binary собран с `-buildvcs=false`; обычный VCS stamp не может
  быть получен из смешанного uncommitted WIP. Миграций не потребовалось.
- Первый независимый Android-запуск на JDK 26 остановился в Android `jlink` до
  тестов и был честно заменён зелёным запуском на поддерживаемом JDK 17. Первая
  Go-попытка оборвалась на reset внешнего module proxy; повтор с тем же cache
  прошёл. Runtime, APK, БД VPS и опубликованные артефакты не обновлялись.
- После этого пакета остаётся 25 подтверждённых открытых проблем. Параллельный
  WIP CustomerApp и клиентского представления в `panel/` прочитан как
  защищённый вход, не изменялся и не переформатировался этим пакетом.
- Основные доказательства:
  `contracts/openapi/task-board-service.yaml`,
  `worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerRealtimeCoordinator.kt`,
  `driver-app/core-sync/src/main/java/dev/buhanzaz/rwms/driver/core/sync/DriverRealtimeCoordinator.kt`,
  `contracts/openapi/media-service.yaml`,
  `services/media-service/internal/persistence/cabin_photo_library.go`,
  `services/media-service/internal/persistence/cabin_photo_library_integration_test.go`.

### 2026-08-31 — пакет AUD-019 / AUD-022 / AUD-026 / AUD-027 / AUD-052 / AUD-054 / AUD-057

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-019`: planner больше не строит одну полную матрицу `N×N`. Он запрашивает
  точные направленные truck-подматрицы не более чем по 32 точки для
  детерминированных временных и перекрывающихся пространственных групп,
  ограничивает все provider/evaluator awaits единым monotonic deadline и при
  timeout возвращает только полностью проверенный best-known план.
- `AUD-022`: create-команды каталога требуют actor-scoped `Idempotency-Key` и
  сохраняют durable receipt; изменения и удаления защищены `expectedVersion`.
  Пересечения смен и параллельное создание общего пула водителей сериализуются
  PostgreSQL advisory locks. Миграция `20260831_0027` добавляет версии,
  receipts и DB-инварианты, не удаляя допустимые legacy-пулы.
- `AUD-026`: `GET .../workspace` стал чистым ограниченным чтением точной даты.
  Импорт 31-дневного горизонта перенесён в серверный bounded worker с
  межпроцессным PostgreSQL fence; directory/import фиксируются до внешнего
  разрешения сети и отдельной транзакции автопланирования.
- `AUD-027`: workspace использует keyset pagination (`request_limit`, UUID
  cursor и total), frontend защищён от поздней страницы старого поколения, а
  карта рендерит заявки кластеризованным GeoJSON-источником без DOM marker на
  каждую строку.
- `AUD-052` / `AUD-054`: план группы сравнивает все разрешённые календарём
  опорные склады и собственные ресурсы каждого участника. Смена хранит
  физический depot, заявка — `serviceWarehouseId`; локальный представитель,
  внешний support и корневой водитель начинают и заканчивают маршрут в своих
  реальных точках, а overlapping resource variants остаются взаимоисключающими.
- `AUD-057`: период смены может пересекать границу месяца в пределах 31 дня;
  `end < start` означает окончание на следующие сутки, `end == start`
  запрещено, а перерыв проверяется против фактической overnight-длительности.
- Подтверждено: clean Alembic upgrade до `0027` и `alembic check`; полный
  backend — `420 passed / 6 skipped` (шесть tagged-graph тестов требуют внешний
  `VALHALLA_SYNTHETIC_URL`); независимый planner gate — `81/81`, Ruff и strict
  mypy (`77 source files`); независимый frontend gate — `191/191`, typecheck,
  lint и production build. Runtime, БД VPS и опубликованные артефакты не
  обновлялись.
- После этого пакета в исходном реестре остаётся 27 подтверждённых открытых
  проблем; `AUD-051` по-прежнему снят как ложная гипотеза.
- Основные доказательства:
  `logistics/backend/app/planner/heuristic.py`,
  `logistics/backend/app/services/demand_ingestion_worker.py`,
  `logistics/backend/app/services/planner_runtime.py`,
  `logistics/backend/app/services/catalog_command_idempotency.py`,
  `logistics/backend/migrations/versions/20260831_0027_catalog_command_fences.py`,
  `logistics/frontend/src/app/App.tsx`,
  `logistics/frontend/src/map/MapCanvas.tsx`.

### 2026-08-31 — AUD-013

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Адресная заявка RWMS без координат теперь разрешается существующим серверным
  геокодером во всех трёх путях: явная синхронизация, явный refresh и
  автоматическое обновление workspace. Координаты feed имеют приоритет и не
  вызывают provider; производная точка не подмешивается в исходный RWMS
  payload.
- Ошибка геокодера остаётся failure конкретного заказа: корректные соседние
  записи сохраняются, а присутствующая ошибочная source identity не считается
  исчезнувшей и не переводится в `CANCELLED`.
- Подтверждено: 7 новых тестов прошли; полный затронутый набор дал `44 passed,
  3 failed`. Все три падения относятся к существующему plan-lifecycle WIP и
  воспроизводятся теми же тестами на снимке до AUD-013. Ruff прошёл; strict
  mypy — `75 source files` без ошибок. Runtime не обновлялся, публикация не
  заявляется.
- Основные доказательства:
  `logistics/backend/app/integrations/rwms_sync.py`,
  `logistics/backend/app/services/catalog.py`,
  `logistics/backend/app/api/rwms.py`,
  `logistics/backend/app/api/catalog.py`,
  `logistics/backend/tests/test_rwms_sync.py`.

### 2026-08-31 — пакет AUD-001 / AUD-030

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-001`: все операторские маршруты standalone-планировщика, включая поток
  событий планирования, теперь требуют Bearer-токен существующей OIDC-сессии
  `rwms-panel`. FastAPI проверяет RS256/JWKS, issuer, audience, expiry,
  `principal_type=USER`, client, scope `rwms.read` и warehouse grants. Без
  аутентификации остаются только health и сгенерированная документация API.
- `AUD-030`: публичные DTO больше не принимают `changed_by`/`confirmed_by`;
  автор ручного изменения, подтверждения, закрытия дня и передачи подрядчику
  выводится из подписанного subject на backend. Миграция `20260831_0023`
  расширяет поле автора закрытия дня под проверенную identity без изменения
  прежней семантики записей.
- Production overlay требует явный `AUTH_ISSUER`, совпадающий с `iss`
  подписанного токена, и использует доступный backend JWKS endpoint. Это
  предотвращает тихий запуск VPS с локальным issuer, несовместимым с реальной
  auth-service.
- Подтверждено: полный backend gate — `363 passed / 6 skipped`, Alembic upgrade
  и check, Ruff, strict mypy; независимый полный UI gate Luna — `166/166`,
  typecheck, lint и production build. Дополнительно focused security/API suites
  прошли `51/51` и `91/91`. Runtime не обновлялся, authenticated production
  smoke не выполнялся и публикация не заявляется.
- Основные доказательства:
  `logistics/backend/app/security.py`,
  `logistics/backend/app/api/authorization.py`,
  `logistics/backend/app/api/router.py`,
  `logistics/frontend/src/api/client.ts`,
  `logistics/frontend/src/auth/AuthenticatedLogisticsApp.tsx`,
  `logistics/backend/migrations/versions/20260831_0023_authenticated_audit_actor.py`.

### 2026-08-31 — AUD-003

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Каталог точного выбора водителя standalone-планировщика теперь возвращает
  только canonical identities с `employmentType=STAFF`.
- Application service повторяет проверку на create/update, поэтому подрядчика
  нельзя провести в обычный optimizer прямым вызовом, обходящим UI/API-фильтр.
  Профиль `CONTRACTOR` остаётся доступен только через отдельный
  `CONTRACTOR_HANDOFF`, без выдуманной машины, смены или цикла.
- Подтверждено независимым focused gate: `11 passed`; Ruff — без замечаний;
  strict mypy — без ошибок в двух изменённых source-файлах. Runtime не
  обновлялся и публикация не заявляется.
- Основные доказательства:
  `logistics/backend/app/api/catalog.py`,
  `logistics/backend/app/services/catalog.py`,
  `logistics/backend/tests/test_driver_employment_boundary.py`.

### 2026-08-31 — пакет AUD-004 / AUD-050

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-004`: CustomerApp сохраняет ключи идемпотентности принятия варианта и
  отчёта о проблеме в ограниченном DataStore-журнале. Не завершённая операция
  получает тот же ключ после смерти процесса; запись удаляется только после
  авторитетного успеха или подтверждённой reconciliation.
- `AUD-050`: рассчитанная стоимость форматируется в пользовательском виде
  `28 500 ₽`, а отсутствующая стоимость показывается как `Не рассчитана`, без
  ложного `0 ₽`.
- Подтверждено: `20` focused CustomerApp-тестов прошли. Runtime и APK не
  обновлялись, публикация не заявляется.
- Основные доказательства:
  `client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerWorkflowStore.kt`,
  `client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerRepository.kt`,
  `client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/CustomerMoneyFormatter.kt`.

### 2026-08-31 — пакет AUD-007 / AUD-024 / AUD-044

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUD-007`: planner-тесты вычисляют даты относительно локального IANA-timezone
  корневого склада, а не относительно UTC/Moscow полуночи test runner.
- `AUD-024`: task-board test profile ограничивает параллелизм, heap, Spring
  context cache и Hikari pool, поэтому полный suite больше не исчерпывает
  ресурсы общей среды.
- `AUD-044`: Vitest использует ограниченное число workers и явный timeout,
  устраняя ложные timeout-failures полного panel gate.
- Подтверждено: planner `11 passed`; task-board focused `2 passed` и полный
  suite `329 tests, 0 failures/errors, 1 skipped`; panel focused `48 passed` и
  полный suite `221 files / 1334 tests`, typecheck прошёл. Runtime не
  обновлялся и публикация не заявляется.

### 2026-08-31 — пакет AUD-031 / AUD-032 / AUD-033 / AUD-039 / AUD-040 / AUD-041 / AUD-042 / AUD-043

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- В БД теперь существует ровно одна неархивная ревизия плана на
  `(warehouse_id, date)`, а новая ревизия хранит `supersedes_plan_id`.
  Обновление спроса и повторный расчёт архивируют только изменяемую голову и
  сохраняют утверждённый план, историю и аудит.
- Подтверждение плана атомарно блокирует заявки, фиксирует выбранную дату для
  гибкого спроса и архивирует конкурирующие черновики; заявку, на которую
  ссылается план, нельзя удалить или изменить после подтверждения.
- Клиент больше не задаёт произвольный lifecycle status заявки. Применение в
  RWMS разрешено только для `CONFIRMED`; закрытие дня не публикует
  неутверждённый план автоматически.
- `AUD-032`: закрытие дня больше не может скрыть частичный reject назначений за
  транспортным HTTP 200, потому что оно вообще не выполняет assignment apply.
  Отдельная команда публикации возвращает полный `applied/rejected` outcome и
  безопасно повторяется по plan/version idempotency key.
- Генератор тестовой нагрузки заранее отклоняет реальный, смешанный или
  утверждённый план и может заменять только полностью generator-owned
  тестовые данные.
- Миграция `20260831_0024` добавляет lineage, архивирует безопасные дубликаты,
  прекращает upgrade при нескольких подтверждённых головах и устанавливает
  частичный unique index.
- Подтверждено: `64` focused backend-теста прошли; Ruff, strict mypy, clean
  Alembic upgrade, downgrade до `0023`, повторный upgrade до `0024`, schema
  check и OpenAPI generation прошли. Независимый UI gate Luna: `27/27`,
  typecheck, scoped lint и production build прошли. Runtime не обновлялся и
  публикация не заявляется.
- Основные доказательства:
  `logistics/backend/migrations/versions/20260831_0024_route_plan_revision_head.py`,
  `logistics/backend/app/services/plans.py`,
  `logistics/backend/app/services/catalog.py`,
  `logistics/backend/app/services/workload_generator.py`,
  `logistics/backend/app/integrations/rwms_sync.py`,
  `logistics/backend/tests/test_plan_lifecycle_invariants.py`.

### 2026-08-31 — AUD-051

- Статус: `ОТКЛОНЕНО ПОСЛЕ ПОВТОРНОЙ ПРОВЕРКИ; FALSE POSITIVE`.
- Первичный объединённый вывод нескольких пересекающихся диапазонов `sed`
  визуально повторил строку `plan.status = DRAFT`. Повторное чтение файла и
  точечный `rg` подтвердили, что в функции существует ровно одно присваивание.
  Исходный код не изменялся. Запись сохранена, чтобы не выдавать ошибку
  инструмента просмотра за подтверждённый дефект проекта.

### 2026-08-31 — AUD-034

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Customer routing cache теперь включает явную версию Valhalla graph data,
  truck profile, endpoint, упорядоченные точки, дату и 15-минутный bucket.
  Положительный TTL ограничен 24 часами; cache хранит максимум 512 LRU-записей
  и не очищает все горячие данные при заполнении.
- Одинаковые конкурентные misses разделяют один in-flight запрос, а один JDK
  `HttpClient` переиспользуется в течение lifecycle компонента и закрывается
  вместе с ним. Truck-only и fail-closed routing не изменены.
- Подтверждено: focused gate `43/43`, Spring-context regression `1/1`, полный
  logistics-service gate `731/731`; task-owned процессы после проверки
  отсутствуют. Runtime не обновлялся; перед публикацией требуется задать
  `LOGISTICS_CUSTOMER_ROUTING_DATA_VERSION` и менять её вместе с tiles или
  restrictions.
- Основные доказательства:
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/routing/ValhallaCustomerTravelTimeClient.java`,
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/config/CustomerDeliveryProperties.java`,
  `services/logistics-service/src/test/java/dev/buhanzaz/rwms/logistics/customer/routing/ValhallaCustomerTravelTimeClientTest.java`.

### 2026-08-31 — AUD-008

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Календарный тест выбирает 11–12 числа фактического отображаемого месяца и
  сравнивает ISO-значения через production formatter, а навигационный тест
  вычисляет следующий день в timezone выбранного склада и проверяет запрос
  именно этой даты вместо статичного 31 августа.
- Подтверждено независимым UI gate Luna: оба затронутых файла прошли,
  суммарно `27/27`; typecheck, scoped lint и production build также прошли.
  Runtime не обновлялся и публикация не заявляется.
- Основные доказательства:
  `logistics/frontend/tests/editor-flows.test.tsx`,
  `logistics/frontend/tests/ui.test.tsx`.

### 2026-08-31 — AUD-005

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- DriverApp вычисляет календарный день логистики из сохранённого server-time
  anchor и canonical IANA timezone склада из `shift/today`. Карусель, rich-trip
  preview и claim используют одну дату и переходят на новый день в складскую
  полночь.
- Невалидный timezone, несовпадающий warehouse ID, истёкший offline lease или
  reset monotonic clock дают `null` и требуют синхронизации; fallback на
  timezone/часы Android отсутствует.
- Подтверждено: `104/104` focused DriverApp-теста прошли, `git diff --check`
  прошёл, task-owned Gradle/Kotlin процессы очищены. APK и runtime не
  обновлялись, публикация не заявляется.
- Основные доказательства:
  `driver-app/core-sync/src/main/java/dev/buhanzaz/rwms/driver/core/sync/DriverWarehouseClock.kt`,
  `driver-app/feature-tasks/src/main/java/dev/buhanzaz/rwms/driver/feature/tasks/LogisticsScreen.kt`,
  `driver-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/driver/feature/taskdetail/TaskDetailViewModel.kt`.

### 2026-08-31 — AUD-045

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Первый успешный workspace response для точного корня планирования и состава
  группы создаёт baseline без уведомлений. Только новый RWMS request ID,
  появившийся в последующем response той же группы, создаёт одно actionable
  уведомление; переход к другой группе устанавливает новый baseline.
- Подтверждено независимым UI gate Luna: `14/14` тестов одного файла,
  typecheck, scoped lint и production build прошли. Runtime не обновлялся и
  публикация не заявляется.
- Основные доказательства:
  `logistics/frontend/src/app/App.tsx`,
  `logistics/frontend/tests/ui.test.tsx`.

### 2026-08-31 — AUD-006, WorkerApp

- Статус: `ЧАСТИЧНО ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- WorkerApp сохраняет исходный Problem Details только для внутренних решений о
  retry/terminal/reconciliation, но в локальную проекцию и пользовательские
  исходы записывает единое безопасное русское сообщение. HTTP-коды, английский
  backend detail, JSON, SQL/exception-текст и имена внутренних сервисов больше
  не попадают в интерфейс WorkerApp.
- Известные коды аутентификации, конфликта версии/lease, недоступности сервиса и
  запрета действия получают конкретное действие для пользователя; неизвестная
  ошибка получает безопасный retry/terminal fallback без сокрытия технической
  причины из логов.
- Подтверждено: `core-network` — `7/7`, `core-sync` — `4/4`; обе JDK 17
  проверки прошли, task-owned Gradle/Kotlin processes очищены. Общий
  `AUD-006` остаётся открытым до проверки Panel, standalone logistics,
  CustomerApp, DriverApp и ManagerApp.
- Основные доказательства:
  `worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/GatewayFailure.kt`,
  `worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt`.

### 2026-08-31 — AUD-006, DriverApp

- Статус: `ЧАСТИЧНО ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- DriverApp сохраняет технический Problem Details только для внутренних
  решений о retry/reconciliation и журналирования. Пользовательский outcome
  теперь всегда содержит безопасное русское объяснение и действие без HTTP,
  JSON, английского backend detail или имени внутреннего сервиса.
- Во время расширенной проверки найден устаревший тест, который сам требовал
  утечку строки `Media session unavailable`. Исправлен только его ожидаемый
  пользовательский текст; production-поведение не ослаблялось.
- Подтверждено независимой проверкой: `core-network` — `43/43`, `core-sync` —
  `38/38`, отдельно `GatewayFailureTest` — `8/8` и
  `DriverSyncFailurePolicyTest` — `4/4`; защищённый pre-existing diff сохранён,
  task-owned Gradle/Kotlin processes отсутствуют.
- Общий `AUD-006` остаётся открытым до завершения проверки всех web/Android
  клиентов.
- Основные доказательства:
  `driver-app/core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/GatewayFailure.kt`,
  `driver-app/core-sync/src/main/java/dev/buhanzaz/rwms/driver/core/sync/DriverSyncCoordinator.kt`,
  `driver-app/core-sync/src/test/java/dev/buhanzaz/rwms/driver/core/sync/BlockedMediaFeedReconciliationRobolectricTest.kt`.

### 2026-08-31 — AUD-063

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Panel теперь создаёт канонический бессрочный профиль подрядчика только с
  identity, именем, телефоном и комментарием. Устаревшие
  `availableFrom`/`availableUntil` удалены из request, response parser и формы;
  дата выбранного рейса по-прежнему принадлежит отдельной dispatch-команде.
- Strict parser и contract test используют точный canonical response и
  отклоняют возврат старых полей, поэтому расхождение больше не маскируется
  расширенной test fixture. Созданный профиль сразу становится доступен для
  выбора водителем рейса.
- Независимый panel gate: focused Vitest — `10/10`, `npm run typecheck` и
  полный `npm run lint` — exit `0`, scoped `git diff --check` — exit `0`.
- Основные доказательства:
  `panel/src/features/logistics/warehouse-transfers/api/logistics-driver-resources-api.ts`,
  `panel/src/features/logistics/warehouse-transfers/transfer-plan-dialog.tsx`,
  `contracts/openapi/task-board-service.yaml`.

### 2026-08-31 — AUD-064

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Один инвариант `break < продолжительность смены` теперь действует в форме,
  create/partial update application service, Pydantic capacity DTO, canonical
  OpenAPI, SQLAlchemy mapping и DB CHECK. Миграция `20260831_0025` сначала
  прекращает upgrade при несовместимых существующих строках, не исправляя их
  молча.
- Capacity mutation после локального commit сохраняет generation и durable
  состояние `PENDING/FAILED/PUBLISHED`. Сбой RWMS больше не превращает уже
  выполненное сохранение настроек в ложную ошибку; leased worker автоматически
  повторяет только актуальные поколения с bounded backoff и `SKIP LOCKED`, а
  запоздалый успех старого поколения не скрывает более новое pending-состояние.
- В БД хранится только безопасный bounded error code, а UI сообщает, что
  локальные настройки сохранены и слоты обновляются автоматически. Повторное
  нажатие «Сохранить» не предлагается как recovery-механизм.
- Миграция `20260831_0026` добавляет publication cursor, статус, attempts,
  next-attempt/lease и partial due-index; downgrade/upgrade cycle после
  исправления naming-convention дефекта прошёл в обе стороны.
- Подтверждено: standalone UI — `23/23`, typecheck и lint; canonical Spring
  contract — `24/24`; финальный backend gate после migration cycle — `20/20`,
  Ruff на `app/tests/migrations`, strict mypy на 75 source-файлах и scoped
  `git diff --check` прошли. Независимые проверяющие исходники не меняли.
- Основные доказательства:
  `logistics/backend/app/services/capacity_publication_state.py`,
  `logistics/backend/app/services/capacity_publication_worker.py`,
  `logistics/backend/migrations/versions/20260831_0025_shift_break_capacity.py`,
  `logistics/backend/migrations/versions/20260831_0026_capacity_publication_state.py`,
  `logistics/frontend/src/app/App.tsx`,
  `contracts/openapi/logistics-service.yaml`.

### 2026-08-31 — AUD-053

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Неперсистентные delay/unavailability команды теперь возвращают отдельный
  `RoutePlanRead` preview с пересчитанными циклами, остановками, сегментами,
  validation и metrics. Сохранённый plan/version/audit при `persist=false` не
  меняется.
- Независимая compose-проверка двух regression-сценариев прошла; Ruff — без
  замечаний. Во время gate исправлен только тестовый доступ к истёкшему ORM
  объекту после `expire_all`, production-логика не менялась.
- Основные доказательства:
  `logistics/backend/app/services/planner_runtime.py`,
  `logistics/backend/tests/test_plan_commands.py`.

### 2026-08-31 — AUD-021

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Spring Cloud Function definition `logisticsInbound` теперь включается только
  вместе с соответствующим Kafka consumer binding. При выключенном consumer
  приложение не объявляет отсутствующую function definition и не маскирует
  конфигурационную ошибку runtime warning-ом.
- Подтверждено: focused logistics-service gate — `48/48`, включая отдельный
  configuration regression test; task-owned процессов после проверки нет.
- Основные доказательства:
  `services/logistics-service/src/main/resources/application.yaml`,
  `services/logistics-service/src/test/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsKafkaFunctionDefinitionConfigurationTest.java`.

### 2026-08-31 — AUD-002

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Успешный полный feed склада и включительного диапазона дат теперь завершает
  lifecycle исчезнувших `READY`/`UNASSIGNED` RWMS-проекций: заявка и её задачи
  получают `CANCELLED`, но source payload, допустимые даты, task IDs и ссылки
  истории сохраняются. Архивируются только изменяемые головы планов;
  подтверждённый план и request lifecycle после planning boundary остаются
  неизменными.
- Presence собирается до валидации строк, поэтому присутствующая, но локально
  отклонённая строка не считается исчезнувшей. Повторный пустой feed
  идемпотентен; возвращение того же `orderId` восстанавливает сохранённые строки
  в `READY` без дубля.
- Независимый compose gate: `test_rwms_sync.py` — `33/33`; Ruff и strict mypy
  двух source-файлов прошли; scoped diff check чист. Runtime не обновлялся.
- Остаточный риск сохранён в реестре: feed пока не имеет generation cursor, так
  что два успешных параллельных snapshot сериализуются по строкам, но порядок
  commit, а не `generatedAt`, определяет последний результат. Изменение cargo
  topology после reappearance также может упереться в существующий history
  fence `REQUEST_TASKS_ALREADY_PLANNED`.
- Основные доказательства:
  `logistics/backend/app/integrations/rwms_sync.py`,
  `logistics/backend/app/services/catalog.py`,
  `logistics/backend/tests/test_rwms_sync.py`.

### 2026-08-31 — AUD-037

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `AUTO` больше не включает реальный RWMS-вывоз в batch и поэтому продолжает
  передавать допустимую доставку смешанного дня. `MANUAL` и прямая передача
  отклоняют неподдержанный вывоз кодом
  `RWMS_CONTRACTOR_PICKUP_UNSUPPORTED` до каталога подрядчиков, инвалидации
  плана, локальной записи или внешнего вызова. Generated/local pickup остаётся
  поддержанным.
- UI заранее скрывает неподдержанный вывоз из ручного выбора, отключает обе
  команды, если других задач нет, и показывает русское объяснение о внутреннем
  маршруте.
- Независимый gate: backend `11/11`, Ruff и strict mypy на 75 source-файлах;
  frontend `6/6`, typecheck и lint. Runtime не обновлялся.
- Основные доказательства:
  `logistics/backend/app/services/contractor_assignment.py`,
  `logistics/frontend/src/features/contractors/ContractorDriversPanel.tsx`,
  соответствующие backend/frontend regression tests.

### 2026-08-31 — AUD-006, ManagerApp / CustomerApp / Panel

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Общие gateway-boundaries этих клиентов сохраняют сырой Problem Details только
  как diagnostic/control-flow факт, а пользователь получает безопасный русский
  текст по code/status. Panel не показывает malformed body, HTTP status,
  exception/SQL/JSON/internal-service detail.
- Независимые проверки: ManagerApp `4/4`, CustomerApp `13/13`, Panel error
  boundary `9/9`, panel typecheck и lint.
- Оставшиеся direct adapters Panel переведены на общий безопасный `ApiError`;
  network, malformed JSON/SSE/media protocol и abort сохраняют сырой текст
  только в `diagnosticMessage`. Независимый финальный web gate: `62/62`,
  typecheck, scoped/full lint и diff-check.
- DriverApp/WorkerApp больше не выводят произвольный `Throwable.message` в
  shift/camera/photo/task-detail flows. Пакетная камера сохраняет точные
  `saved/selected` counts, а cancellation распространяется как cancellation,
  не превращаясь в UI error. Финальные module suites: Worker camera `28/28`,
  Worker task detail `45/45`, Driver task detail `53/53`.
- Основные доказательства:
  `app/src/main/java/dev/buhanzaz/rwms/manager/network/Backend.kt`,
  `client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerNetwork.kt`,
  `panel/src/lib/api-client.ts`, `panel/src/lib/user-facing-error.ts`.

### 2026-08-31 — AUD-065

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Во время AUD-037 обнаружен race в UI regression: тест искал кнопку подрядчика
  синхронно, хотя каталог загружается асинхронно. Это давало ложный failure в
  зависимости от скорости среды. Ожидание заменено на доступный async query без
  ослабления product assertions.
- Независимый focused Vitest AUD-037 прошёл `6/6`; typecheck и lint прошли.
- Доказательство:
  `logistics/frontend/tests/contractor-assignment.test.tsx`.

### 2026-08-31 — блокер JPA-части AUD-058

- Статус: `НЕ ИСПРАВЛЕНО; ИНСТРУМЕНТАЛЬНЫЙ БЛОКЕР ЗАФИКСИРОВАН`.
- Исправление семантической готовности координат `0,0` затрагивает JPA-агрегат
  Warehouse. Обязательный проектный workflow `spring-data-jpa` требует
  подключённый Amplicode Spring MCP до изменения JPA-сущности.
- В текущей среде MCP отсутствует; обязательный `amplicode-install` выполнил
  штатный `detect-ides.sh`, который вернул пустой список: на VPS не найдено ни
  IntelliJ IDEA, ни GigaIDE, куда плагин можно установить стандартным JetBrains
  CLI. Поэтому JPA-код, Flyway и связанные UI/consumer-paths AUD-058 намеренно
  не менялись.
- Для снятия блокера нужен поддерживаемый IDE на машине пользователя, установка
  Amplicode, нажатие `Настроить Spring Agent` в открытом проекте и перезапуск
  MCP-клиента. До этого карточка AUD-058 остаётся открытой.

### 2026-08-31 — AUD-056

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- В `setDesiredEquipment` удалён только второй подряд идентичный вызов
  `requireVersion`; version fence по-прежнему выполняется один раз после
  блокировки и проверки редактируемости заказа, до внешней команды имущества.
- Проверены точный успешный workflow комплектации/удаления/отмены и отклонение
  бытовки из другого заказа до asset mutation: `OrderApiIntegrationTest` —
  `2/2`, Gradle BUILD SUCCESSFUL. `git diff --check` прошёл, task-owned Gradle
  processes отсутствуют. Контракт, persistence и пользовательское поведение не
  изменились; документационно видимого эффекта нет.
- Доказательство:
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderReservationService.java`.

### 2026-08-31 — AUD-066

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Независимая read-only проверка direct Panel adapters прошла `51/51`,
  typecheck и оба lint-gate, но выявила два точечных пробела regression:
  отсутствует прямой сценарий `REQUEST_ABORTED`, а malformed JSON публичной
  галереи проверяет только наличие `diagnosticMessage`, не доказывая, что сырой
  response body не стал пользовательским `message`.
- Проверка malformed body усилена и проходит. Новый прямой abort regression
  закономерно упал: браузерный `DOMException` не распознаётся текущим
  `diagnosticMessage()`. Это уже не пробел тестов, а подтверждённый дефект
  реализации, вынесенный в AUD-067.
- После исправления AUD-067 финальный независимый gate прошёл `62/62`; тесты
  прямо запрещают raw body в пользовательском `message` и проверяют реальный и
  cross-realm `AbortError`.

### 2026-08-31 — AUD-067

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- `apiErrorFromRequestFailure()` правильно классифицирует `AbortError` как
  `REQUEST_ABORTED`, но теряет исходное диагностическое сообщение реального
  browser/jsdom `DOMException`: вместо него записывается
  `Unknown transport failure`.
- Прямой regression: `assistant-api.test.ts` — 27 passed, 1 failed; ожидаемое
  `diagnosticMessage = "assistant request aborted by user"`, фактическое —
  `Unknown transport failure`. Пользовательский русский текст остаётся
  безопасным, но telemetry теряет причину отмены.
- Root cause: общий helper принимает сообщение только у `Error` и `string`,
  тогда как `DOMException` в текущем browser/jsdom realm не проходит
  `instanceof Error`.
- Подготовлен отдельный pre-edit snapshot общей API-обвязки:
  `/tmp/rwms-aud066-domexception`; исправление ограничено извлечением безопасной
  строковой `message` у DOMException/error-like значения без вывода её в UI.
- Итоговый helper читает `name/message` через guarded `Reflect`, поэтому
  работает между realms и безопасно переживает throwing getter. Frozen hashes
  до и после независимой проверки совпали; Vitest `62/62`, typecheck и оба
  lint-gate прошли.

## Текущие блокеры готовности mixed WIP

Эти пункты не подменяют карточки product-дефектов ниже. Они фиксируют точное
состояние уже существующего незакоммиченного WIP, чтобы красные проверки не
потерялись и не были ошибочно объявлены зелёными.

### WIP-GATE-001 — standalone logistics frontend lint

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Удалены две лишние regex-escape-последовательности и ненужный type assertion
  без изменения поведения error mapper.
- Независимая Luna-проверка итоговых файлов: `npm run lint` — exit `0`;
  focused Vitest — 3 файла, `34/34`; `npm run typecheck` — exit `0`.
- Проверяющий агент не менял файлы, frozen diff до и после совпал.

### WIP-GATE-002 — неперсистентный preview симуляции

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Test fixture сохраняет scalar `plan_id` до `expire_all()` и больше не
  обращается к истёкшему async ORM-объекту вне `greenlet_spawn`.
- Независимая Luna-проверка в compose: два focused regression-сценария passed,
  `10 deselected`, Ruff — `All checks passed!`.
- Проверяющий агент не менял production/test sources и не оставил процессов.

### WIP-GATE-003 — DriverApp safe-error WIP

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- После исправления одного устаревшего expectation весь `core-network` прошёл
  `43/43`, весь `core-sync` — `38/38`; focused safe-error suites прошли
  `8/8` и `4/4`.
- Финальная сверка с защищённым снимком подтвердила сохранность исходного WIP;
  новых файлов вне назначенного scope и оставшихся task-owned процессов нет.

### WIP-GATE-004 — panel lint после интеграции общего error mapper

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- В новом общем panel error mapper обнаружена лишняя regex escape-последовательность,
  из-за которой полный lint падал, хотя contractor focused tests и typecheck
  проходили. Escape удалён без изменения распознаваемого набора символов.
- Независимый gate: полный panel lint — exit `0`, contractor Vitest — `10/10`,
  typecheck и scoped `git diff --check` — exit `0`.

### WIP-GATE-005 — downgrade новых Alembic constraints

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО ЛОКАЛЬНЫМ MIGRATION CYCLE`.
- При первом downgrade миграций `0025`/`0026` обнаружено повторное применение
  SQLAlchemy naming convention к уже вычисленным именам constraint/index.
  Имена обёрнуты в `op.f(...)`; циклы `0026 -> 0025 -> 0024` и
  `0024 -> 0025 -> 0026` после исправления проходят.

## Шкала

- `P0` — критическая потеря данных, полный обход защиты или система не работает.
- `P1` — серьёзная бизнес-ошибка либо высокий риск некорректных операций.
- `P2` — заметная функциональная/надёжностная ошибка.
- `P3` — локальный UX/edge-case/поддерживаемость.
- `SYSTEMIC PROBLEM` — причина находится в границе/модели, а не в одной строке UI.
- `QUICK WIN` — ограниченное исправление без смены владельца домена.

## Подтверждённые критические проблемы

### AUD-001 — публичный FastAPI-планировщик не требует аутентификации

- Severity: `P0`
- Класс: `SYSTEMIC PROBLEM`, Security, Authorization, IDOR, DoS.
- Приложение: standalone logistics planner (`logistics/`).
- Где:
  - `logistics/backend/app/main.py:46-70` — FastAPI подключает CORS и весь API,
    но не подключает authentication/authorization middleware или dependency;
  - `logistics/backend/app/api/dependencies.py:1-71` — зависимости БД,
    маршрутизатора и сервисного RWMS-клиента, но нет пользовательской identity;
  - `logistics/backend/app/api/router.py:7-13` — публично смонтированы catalog,
    plans, rwms, routing, slots и geocoding;
  - `logistics/deploy/nginx-public-path.conf:7-19` — внешний
    `/logistics-simulator/api/**` напрямую проксируется в FastAPI без
    `auth_request`;
  - `logistics/backend/app/api/catalog.py:489-925` и
    `logistics/backend/app/api/plans.py:68-382` содержат изменяющие операции;
  - `logistics/backend/app/api/rwms.py:123-132` позволяет применять план в RWMS.
- Фактическая проверка: без cookie и Bearer-token внешние запросы
  `GET https://77-90-158-90.sslip.io/logistics-simulator/api/health` и
  `GET .../api/warehouses` вернули HTTP 200. Изменяющие запросы намеренно не
  выполнялись.
- Как воспроизвести:
  1. Открыть публичный URL в чистой сессии без входа.
  2. Выполнить `GET /logistics-simulator/api/warehouses`.
  3. Получить справочник складов и UUID без 401/403.
  4. По коду те же незащищённые маршруты позволяют создавать/удалять ресурсы,
     строить/подтверждать планы и запускать RWMS apply.
- Сейчас: CORS ограничивает браузерные origins, но CORS не является механизмом
  аутентификации; прямой HTTP-клиент не ограничен.
- Должно быть: все операторские reads/commands защищены интерактивной identity,
  RBAC и warehouse grants; сервисная OAuth identity FastAPI не должна
  превращать анонимного посетителя в доверенный planning client.
- Root cause: standalone deployable опубликован как внутренний инструмент, но
  ingress не добавил защиту, а само приложение спроектировано без principal.
- Последствия: анонимное перечисление операционных данных, изменение и удаление
  планов/ресурсов, потенциальное назначение заданий в каноническом RWMS,
  расход CPU/Valhalla/geocoder и отказ в обслуживании.

### AUD-002 — исчезнувшие из полного RWMS-feed заявки остаются активными в планировщике

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, projection consistency.
- Где:
  - `contracts/openapi/logistics-service.yaml:775-792` описывает still-unplanned
    demand snapshot;
  - `logistics/backend/app/integrations/rwms_sync.py:70-163` только upsert-ит
    строки, пришедшие в `feed.requests`;
  - локальная модель хранит `LogisticsRequest.status=READY` в
    `logistics/backend/app/models/domain.py:598-671`;
  - противоречие также честно зафиксировано в
    `docs/project-knowledge/open-questions.md` в разделе
    `Retiring Planner Requests Missing From A Full RWMS Feed`.
- Как воспроизвести:
  1. Синхронизировать ещё не распределённый заказ в planner.
  2. Назначить или отменить его в каноническом logistics-service так, чтобы он
     исчез из следующего still-unplanned feed.
  3. Повторно синхронизировать диапазон дат.
  4. Локальная READY-заявка не переводится в inactive/cancelled и продолжает
     участвовать в расчёте.
- Сейчас: отсутствие строки в успешном полном refresh не обрабатывается.
- Должно быть: владелец контракта должен передавать lifecycle/tombstone либо
  явно определить omission как authoritative inactive transition с сохранением
  истории и инвалидированием планов.
- Root cause: контракт не определяет безопасную семантику удаления, а consumer
  реализовал только upsert.
- Последствия: устаревшие доставки в новых планах, ложная загрузка, повторные
  попытки назначения; canonical apply позднее отвергнет часть операций, но
  локальная проекция автоматически не исправится.

### AUD-003 — подрядчика можно завести в обычный контур оптимизации штатных водителей

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, domain boundary/type erasure.
- Где:
  - `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/LogisticsDriverDirectoryService.java:55-60`
    возвращает и `STAFF`, и `CONTRACTOR`;
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java:155-205`
    корректно сохраняет `employmentType`;
  - `logistics/backend/app/api/catalog.py:504-519` при выдаче available drivers
    выбрасывает `employmentType`, оставляя только `worker_id/display_name`;
  - `logistics/backend/app/services/catalog.py:471-519` проверяет совпадение
    identity, но не требует `STAFF`;
  - `logistics/frontend/src/components/EntityDialogs.tsx:261-270` показывает все
    identities как обычного «Водителя RWMS»;
  - `logistics/backend/app/planner/heuristic.py:181-193` фильтрует только active
    shift/vehicle и не знает employment type.
- Как воспроизвести:
  1. Создать активный профиль наёмного водителя в task-board.
  2. Открыть диалог добавления обычного водителя planner.
  3. Выбрать подрядчика из неразличимого списка.
  4. Создать ему обычные vehicle/shift; он станет кандидатом циклов.
- Сейчас: отдельная семантика contractor handoff теряется на FastAPI DTO boundary.
- Должно быть: STAFF участвует во внутренней оптимизации; CONTRACTOR получает
  отдельное assignment/handoff без требования машины, цикла и внутреннего
  маршрута.
- Root cause: сужающий mapper удаляет discriminating field.
- Последствия: система начинает выдумывать неизвестную машину/вместимость/смену
  подрядчика и строить недостоверный план.

## Подтверждённые функциональные и надёжностные ошибки

### AUD-004 — CustomerApp теряет idempotency key после смерти процесса

- Severity: `P2` (для problem report возможны реальные дубликаты).
- Класс: Reliability, mobile retry.
- Где:
  - `client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerRepository.kt:29`
    — `pendingIdempotencyKeys` хранится только в памяти;
  - тот же файл `:446-452` генерирует UUID заново после нового процесса;
  - `CustomerWorkflowStore.kt:57-82` устойчиво хранит ключ inquiry-create, но не
    ключи acceptance/problem-report;
  - `services/logistics-service/src/main/resources/db/migration/V61__customer_terms_capacity_shifts_and_reception.sql`
    дедуплицирует problem reports только по `(customer_subject_id,
    idempotency_key)`.
- Как воспроизвести:
  1. Отправить acceptance либо problem report.
  2. Потерять ответ и завершить процесс приложения.
  3. Повторить действие после запуска.
  4. Новый процесс отправит новый ключ.
- Сейчас: acceptance обычно встретит domain conflict/уникальное ограничение;
  problem report может создать вторую запись.
- Должно быть: pending effect и стабильный idempotency key переживают process
  death до authoritative outcome/reconciliation.
- Root cause: ключ устойчив только в lifetime репозитория.
- Последствия: дубли обращений, непонятный 409 после успешной приёмки, повторные
  фото/операторские действия.

### AUD-005 — DriverApp определяет «сегодня» по часовому поясу телефона, а не склада

- Severity: `P2`
- Класс: Timezone correctness.
- Где:
  - `driver-app/feature-tasks/src/main/java/dev/buhanzaz/rwms/driver/feature/tasks/LogisticsScreen.kt:64`;
  - `driver-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/driver/feature/taskdetail/TaskDetailScreen.kt:126,132`;
  - `driver-app/feature-task-detail/src/main/java/dev/buhanzaz/rwms/driver/feature/taskdetail/TaskDetailViewModel.kt:323,361`;
  - серверная проверка использует timezone склада в
    `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/FutureDriverTaskClaimService.java:106-109`.
- Как воспроизвести:
  1. Установить на устройстве timezone, отличающийся от timezone склада.
  2. Открыть приложение около полуночи склада.
  3. Сравнить выбранную/помеченную дату и серверную возможность claim.
- Сейчас: UI использует `LocalDate.now()` устройства; server fail-closed
  использует локальную дату склада.
- Должно быть: отображение и команды опираются на выбранный/назначенный склад и
  согласованный server clock.
- Root cause: warehouse timezone не доведён до presentation clock.
- Последствия: водитель видит не тот рабочий день или действие, которое затем
  корректно, но неожиданно, отвергается сервером.

### AUD-006 — технические/английские Problem Details напрямую попадают пользователю

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P2`
- Класс: Error handling, UX, information exposure.
- Где:
  - `driver-app/core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/GatewayFailure.kt:43-57`
    использует сырой `problem.detail ?: problem.title`;
  - view models показывают `Throwable.message`, включая
    `driver-app/feature-shift/src/main/java/dev/buhanzaz/rwms/driver/feature/shift/DriverShiftViewModel.kt:122,138,401`,
    а camera/photo screens тем же способом могут показать platform/I/O
    exception;
  - `client-app/app/src/main/java/dev/buhanzaz/rwms/client/data/CustomerNetwork.kt:81-100`
    также предпочитает сырой detail;
  - общий panel client берёт `detail/message/error/title` из произвольного
    Problem Details как готовый пользовательский текст, после чего десятки
    страниц напрямую выводят `error.message`:
    `panel/src/lib/api-client.ts:18-35,58` и, например,
    `panel/src/features/logistics/driver-board/driver-board-page.tsx:1088-1091,1730-1733`;
  - WorkerApp создаёт исключение прямо из `problem.detail ?: problem.title`, а
    sync outcomes и UI повторно используют этот текст:
    `worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/GatewayFailure.kt:54-57`
    и
    `worker-app/core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt:761-774`;
  - FastAPI сам возвращает английские detail, например
    `logistics/backend/app/main.py:29-43`,
    `logistics/backend/app/integrations/rwms_sync.py:87-140`,
    `logistics/backend/app/services/catalog.py:451-456`.
- Как воспроизвести:
  1. Вызвать отклонённую доменную операцию либо transport 400/409.
  2. Backend возвращает английский/internal detail.
  3. Mobile/UI показывает detail как пользовательский текст.
- Сейчас: нет единого клиентского каталога `domain code -> русский текст +
  действие`, а backend detail смешивает операторский и диагностический смысл.
- Должно быть: стабильный error code, локализованный UX mapper, correlation ID;
  raw detail остаётся только в telemetry/log.
- Последствия: непонятные ошибки, раскрытие названий внутренних сервисов и
  ограничений, невозможность корректно подсказать пользователю выход.
- Исправление: все затронутые gateway/transport boundaries Panel,
  ManagerApp, CustomerApp, DriverApp и WorkerApp теперь используют безопасные
  code/status-based сообщения; raw detail остаётся только диагностикой.
  Повторный scan разрешает только заведомо безопасные domain presentations и
  telemetry, но не произвольный `Throwable.message` в пользовательском state.

### AUD-007 — backend-тест очистки planner projection зависит от UTC/Moscow полуночи

- Severity: `P2` для CI/release reliability; продуктовый путь в этом тесте не
  доказан сломанным.
- Где: `logistics/backend/tests/test_planning_group.py:380,435` и
  `logistics/backend/app/api/catalog.py:366`.
- Фактическая проверка: `make test` 2026-08-31 завершился с 352 passed, 2 failed,
  6 skipped. Обе параметризации одного теста ожидали удаление stale draft.
- Root cause: fixture берёт naive `datetime.now().date()` в UTC Docker, endpoint
  формирует 31-дневный интервал по warehouse `Europe/Moscow`. Между 00:00 UTC и
  03:00 MSK даты расходятся, fixture выпадает из интервала.
- Последствия: ночные ложные красные сборки, снижение доверия к gate.

### AUD-008 — два теста интерфейса planner зависят от текущей даты/таймаута

- Severity: `P2` для CI reliability.
- Где:
  - `logistics/frontend/tests/editor-flows.test.tsx` — hotel date range test
    timeout 5000 ms;
  - `logistics/frontend/tests/ui.test.tsx:280` — ожидался 31 августа, приложение
    после инициализации warehouse-local date показало 1 сентября.
- Фактическая проверка: Vitest — 24/26 files passed, 153/155 tests passed.
  Из-за `&&` после Vitest не запускались typecheck/lint/build в этом gate.
- Root cause второго падения: статическая fixture-date 2026-08-30 конфликтует с
  фактической текущей локальной датой склада 2026-08-31. Причина timeout требует
  дополнительной локализации.
- Последствия: nondeterministic CI и отсутствие последующих проверок при
  short-circuit.

## Подтверждённые архитектурные риски и пробелы

### AUD-009 — контракт SSE обещает Last-Event-ID, но producer не имеет replay cursor

- Severity: `P2`
- Класс: API contract/recovery mismatch.
- Доказательство: canonical OpenAPI объявляет reconnect cursor, тогда как
  task-board отдаёт in-memory invalidation stream без durable replay; клиенты
  компенсируют это периодическим authoritative pull. Противоречие зафиксировано
  в `docs/project-knowledge/open-questions.md`.
- Риск: после разрыва клиент может ждать следующего invalidation/периодического
  pull, хотя контракт создаёт ложное ожидание точного replay.
- Target: либо durable monotonic cursor/replay, либо честное изменение контракта
  на invalidation-only SSE с обязательной перезагрузкой projection.

### AUD-010 — «неистекающая» cabin presentation может потерять изображения

- Severity: `P2`
- Класс: cross-service contract contradiction.
- Доказательство: logistics сохраняет `{mediaId,generation}`, но media private
  read разрешает только текущую READY generation; после generation advance или
  soft-delete frozen presentation возвращает 404. Подробная подтверждённая
  цепочка указана в `docs/project-knowledge/open-questions.md`.
- Риск: уже отправленная клиенту ссылка перестаёт показывать фото, несмотря на
  заявленную неизменяемость/неистекаемость.

### AUD-011 — создание бытовки с фото является недолговечной browser saga

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, cross-service consistency.
- Где:
  - `panel/src/features/rental-items/rental-item-create-dialog.tsx`;
  - `panel/src/features/rental-items/api/asset-rental-items-api.ts`;
  - `panel/src/features/media/api/http-media-client.ts`;
  - зафиксировано в `docs/project-knowledge/open-questions.md`.
- Сценарий: asset create проходит, затем загрузка одного/нескольких фото падает
  или вкладка закрывается. Бытовка уже существует, а намерение загрузить
  выбранные файлы нигде на сервере не сохранено.
- Сейчас: обе отдельные команды idempotent, но оркестрация живёт в памяти UI.
- Должно быть: один владелец durable intent/saga с resume/abandon semantics.
- Последствия: неполные карточки, ручной поиск и дозагрузка, невозможность
  автоматически доказать, какие фото пользователь собирался прикрепить.

### AUD-012 — contractor handoff держит локальные блокировки во время удалённой команды

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P2`
- Класс: `SYSTEMIC PROBLEM`, transaction/availability.
- Где: `logistics/backend/app/services/contractor_assignment.py`.
- Сейчас: локальные planner rows блокируются/инвалидируются в одной orchestration
  path, а canonical HTTP call выполняется до локального commit.
- Положительная защита: стабильный idempotency key уменьшает риск двойного
  external effect.
- Риск: медленный/недоступный logistics-service удлиняет DB locks, создаёт
  contention и сложное неопределённое состояние при потере ответа.
- Target: durable command/outbox или явно оформленная saga с короткими
  транзакциями и reconciliation.

### AUD-013 — адресные заказы без координат исключаются из планирования

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P2`; исходная реализация fail-closed исключала валидный заказ из
  планирования, хотя в проекте уже есть серверный geocoder.
- Исходное место: `logistics/backend/app/integrations/rwms_sync.py` возвращало
  `COORDINATES_REQUIRED` до вызова существующего
  `YandexGeocodingClient.forward`; автоматические workspace/refresh paths также
  не передавали этот resolver в order sync.
- Последствия до исправления: валидный адрес без lat/lon не попадал в план;
  логист должен был исправлять данные вне этого flow.
- Решение владельца продукта подтверждено исходным заданием: координаты feed
  остаются источником истины, а при их отсутствии адрес должен разрешаться
  существующим механизмом геокодирования. Ошибка provider должна оставаться
  явной per-order failure и не отменять импорт корректных соседних строк.
- Реализовано: явная синхронизация, явный refresh и автоматическое обновление
  workspace передают существующий серверный geocoder. Полная пара координат
  feed имеет приоритет и не вызывает provider; производная точка используется
  только в оперативной проекции, а исходный RWMS payload остаётся неизменным.
  Ошибка разрешения изолирована в одной строке, корректные соседние строки
  сохраняются, а присутствующая ошибочная строка не считается исчезнувшей и
  не переводится в `CANCELLED`.
- Проверено 31 августа 2026: 7 новых целевых тестов — `7 passed`; полный
  затронутый набор — `44 passed, 3 failed`, причём все три падения воспроизведены
  без AUD-013 на сохранённом pre-edit snapshot и относятся к уже существующему
  WIP жизненного цикла plan head. Ruff прошёл; strict mypy — `75 source files`
  без ошибок. Коммит, публикация и runtime update не выполнялись.

### AUD-014 — customer slot policy частично ограничена фиксированным горизонтом

- Статус: `ОСТАНОВЛЕНО ДО ПРОДУКТОВОГО РЕШЕНИЯ`.
- Severity: `P2` как продуктовый/contract gap.
- Фактическое состояние на 1 сентября 2026: точный CustomerApp slot search и
  presentation guidance используют общий configurable warehouse-local диапазон
  `earliestDeliveryDays..bookingHorizonDays` (defaults `today+2..today+14`).
  Точный flow проверяет адрес и все дни диапазона. Public presentation без
  адреса возвращает максимум четыре самые ранние даты с предварительно
  доказанной ёмкостью, используя склад как provisional destination; это
  намеренно не является обещанием точного слота.
- Открытый вопрос теперь не в хардкоде `today+5`, а в продуктовой семантике:
  оставлять четыре non-binding даты для первичного согласования или требовать
  адрес и показывать полный набор точных доступных дат. Также нужно утвердить
  срок offer/hold и fallback при недоступности planner/capacity.
- Риск: без явной формулировки клиент может принять предварительную дату за
  гарантированный слот либо не увидеть более поздний выполнимый день из
  14-дневного диапазона.

### AUD-015 — нет подтверждённого владельца точного ETA для DriverApp

- Статус: `ЧАСТИЧНО ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ОСТАТОК ОТКРЫТ`.
- Severity: `P2` как продуктовый пробел.
- Applied plan теперь содержит task-board-owned ordered operation snapshot с
  aware planned arrival/departure, а DriverApp показывает planned ETA в
  timezone склада смены. Эта часть больше не является пробелом.
- Остаётся: preview будущей общей ходки до claim по-прежнему не несёт ETA;
  actual/dynamic ETA, delay propagation, SLA и stale-estimate policy не имеют
  утверждённого владельца. Это зафиксировано в
  `docs/project-knowledge/open-questions.md` без выдуманного live fallback.

### AUD-016 — production signing/release trust для Android

- Статус: `ИСПРАВЛЕНО НА УРОВНЕ РЕПОЗИТОРИЯ И ПРОВЕРЕНО В WIP; НЕ
  ОПУБЛИКОВАНО`.
- Severity: `P1` перед production rollout.
- Release artifact graph каждого Android-приложения требует внешний полный
  signing properties и читаемый keystore. Каждая download surface проверяет
  channel/package/version/source/hash и ровно один signer по отдельному pinned
  policy. Debug и production каналы не могут разделять сертификат.
- CustomerApp и WorkerApp имеют закреплённые production signer; ManagerApp и
  DriverApp намеренно fail-closed для production, пока внешний владелец ключа
  не предоставит и не утвердит production certificate hash. Организационные
  key custody/rotation/recovery и retention остаются внешним открытым решением,
  но больше не позволяют repository tooling молча принять произвольный APK.

### AUD-017 — отсутствует подтверждённая общая retention/archive policy

- Severity: `P2` сейчас, `P1` при масштабе/регуляторных требованиях.
- Затрагивает: заказы, планы, outbox/inbox, audit, фото/варианты, location/evidence.
- Риск: неограниченный рост БД/MinIO, деградация запросов и неопределённость
  удаления персональных данных; либо опасное ручное удаление без legal hold.
- Не подтверждено, что политика отсутствует вне репозитория — требуется
  организационная проверка.

### AUD-018 — списки логистических документов не имеют пагинации и создают N+1

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1` при заявленном масштабе, `P2` на текущем объёме.
- Класс: `SYSTEMIC PROBLEM`, API/DB performance.
- Исправление: public return/shipment/transfer reads сохранили совместимый
  JSON-array body, получили bounded `page`/`size` (50 по умолчанию, максимум
  100) и фиксированные `X-RWMS-*` pagination headers. Projection читает одну
  страницу headers и все её lines одним batch query вместо N+1.
- Проверка: независимый общий logistics gate прошёл `146/146`, включая
  contract, controller/API и projection regression tests.
- Где:
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/api/LogisticsController.java:70-74,168-172,289-293`
    возвращает целый `List` возвратов, отгрузок или перемещений склада;
  - `.../repository/LogisticsDocumentRepository.java:54-55` выполняет
    неограниченный запрос по складу и типу;
  - `.../service/LogisticsDocumentReadProjection.java:96-102` материализует
    каждый документ через `view`;
  - тот же класс `:35-60` отдельно загружает строки каждого документа.
- Как воспроизвести: накопить большую историю документов одного типа, затем
  открыть соответствующий список склада и снять SQL trace.
- До исправления: один HTTP-запрос читал всю историю и выполнял ещё по одному запросу
  строк на каждый документ.
- Должно быть: cursor/page contract, ограниченный projection query и batch/
  join-fetch только нужной страницы.
- Root cause: endpoint проектировался как небольшой справочник, хотя данные
  являются неограниченно растущей операционной историей.
- Последствия: рост latency и heap, длинные DB-транзакции чтения, тяжёлые ответы;
  при тысячах документов — деградация всего logistics-service.

### AUD-019 — planner строит квадратную матрицу всех заявок дня

- Severity: `P1` при целевом масштабе 10 000 задач/сутки.
- Класс: `SYSTEMIC PROBLEM`, algorithmic scalability.
- Где:
  - `logistics/backend/app/planner/heuristic.py:195-203` строит матрицу из
    склада и всех задач;
  - `:282-323` повторно создаёт наборы кандидатов после каждого назначения;
  - `:1707-1775` строит bounded-группы, что ограничивает соседей, но не размер
    исходной квадратной матрицы;
  - `logistics/backend/app/services/planner_runtime.py:1445-1461` использует
    геометрический prefilter для Valhalla, однако матрица всё равно остаётся
    `N x N` в памяти;
  - `logistics/backend/app/routing/valhalla.py:162-202,336-356` при прямом
    matrix-provider формирует один полный `sources_to_targets` запрос.
- Как воспроизвести: дать planner 10 000 точек одного дня. Только матрица
  содержит около 100 млн ячеек до учёта повторных candidate iterations и exact
  rerouting лучших кандидатов.
- Сейчас: `max_optimization_seconds * 50_000` превращается в детерминированный
  evaluation budget (`heuristic.py:231-236`), а не в реальный wall-clock limit.
- Должно быть: spatial/temporal partitioning, sparse nearest-neighbor graph,
  батчирование матриц, настоящий внешний watchdog/cancellation и нагрузочный
  SLO без потери детерминизма результата.
- Последствия: память/CPU/Valhalla payload растут квадратично; один плотный день
  способен занять worker и вызвать timeout/DoS.

### AUD-020 — planner теряет склад-источник бытовки регионального заказа

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: canonical planning contract, standalone adapter и
  logistics-service owner теперь передают, ревизуют и проверяют точную пару
  физической бытовки и склада-источника до reservation/apply.

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, cross-warehouse domain/contract gap.
- Где:
  - каноническая reservation-модель уже различает источник:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java:652-663`;
  - отгрузка хранит и валидирует `inventorySourceWarehouseId`:
    `.../order/api/OrderApiModels.java:160-198` и
    `.../service/LogisticsRentalOrderShipmentCoordinator.java:140-180,410-419`;
  - но `PlanningIntegrationApiModels.PlanningRequestResponse:81-99` экспортирует
    только `unitIds`, без склада каждого резерва/выбранного источника;
  - Pydantic `RwmsPlanningRequest` в
    `logistics/backend/app/schemas/domain.py:850-900` также не имеет источника;
  - локальная `LogisticsRequest` в
    `logistics/backend/app/models/domain.py:598-689` хранит только один
    `warehouse_id` (склад обслуживания);
  - planner apply DTO содержит `serviceWarehouseId`, но не inventory source:
    `logistics/backend/app/schemas/domain.py:1218-1245`;
  - `RentalOrderPlanningIntegrationService.java:261-272` создаёт shipment через
    совместимый шестипараметрический constructor, где источник равен `null`, а
    coordinator затем по умолчанию выбирает склад заказа.
- Как воспроизвести:
  1. Создать заказ представительского склада.
  2. Зарезервировать для него физическую бытовку разрешённого опорного склада.
  3. Получить planning feed и построить/apply план от основной группы.
  4. Feed не сообщает planner фактический источник; apply не может вернуть его.
  5. Создание shipment либо отклонит бытовку как находящуюся не на складе
     обслуживания, либо создаст неверную семантику маршрута.
- Сейчас: planner объединяет заявки группы, но моделирует их относительно одного
  depot корневого склада; альтернативы «заехать на региональный склад за
  местной бытовкой» и «доставить бытовку опорного склада напрямую» не являются
  полноценными кандидатами.
- Должно быть: immutable service warehouse, отдельный inventory source на
  planning item/assignment, route origin/support-link identity и проверка
  физической reservation-location при apply.
- Последствия: неверная трасса и складская операция, невозможность автоматом
  реализовать ключевые cross-warehouse сценарии, поздние 409 при публикации.

### AUD-021 — выключенный Kafka consumer оставляет активную function definition

- Severity: `P3`
- Класс: configuration/operability, `QUICK WIN`.
- Где:
  - `services/logistics-service/src/main/resources/application.yaml:52-69`
    всегда задаёт `spring.cloud.function.definition=logisticsInbound`;
  - bean существует только при `rwms.platform.kafka.enabled=true` в
    `.../eventing/inbound/LogisticsKafkaInboundConsumers.java:12-25`;
  - dev profile по умолчанию выключает Kafka:
    `application-dev.yaml:21-24`.
- Фактическая проверка runtime: при старте demo-сервиса зарегистрировано
  предупреждение `You have defined function definition that does not exist:
  logisticsInbound`; сервис после этого успешно стартовал.
- Сейчас: безопасный offline/dev режим работает, но startup log выглядит как
  ошибка wiring и маскирует реальные проблемы конфигурации.
- Должно быть: definition активируется тем же условием/profile, что и bean, либо
  disabled-профиль явно исключает binding.
- Последствия: operational noise и риск пропустить реальную потерю inbound
  consumer в окружении, где Kafka ожидалась включённой.

### AUD-022 — повторные и конкурентные команды planner catalog не защищены от дублей

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, concurrency, idempotency, TOCTOU.
- Где:
  - изменяющие POST/PATCH catalog endpoints не принимают `Idempotency-Key` или
    `expectedVersion`: `logistics/backend/app/api/catalog.py:523-835`;
  - `create_request` сразу инвалидирует планы и вставляет новую запись:
    `logistics/backend/app/services/catalog.py:883-902`;
  - unique key заявки состоит из `(warehouse_id, source_system, external_id)`,
    но у ручных/генерируемых заявок последние два поля nullable:
    `logistics/backend/app/models/domain.py:598-689`;
  - driver uniqueness использует nullable `external_worker_id`, поэтому
    PostgreSQL допускает несколько строк `WAREHOUSE_DRIVERS` одного склада:
    `logistics/backend/app/models/domain.py:336-360`;
  - проверка пересечения смен выполняет обычный SELECT, затем отдельный INSERT,
    без row/advisory lock и без exclusion constraint:
    `logistics/backend/app/services/catalog.py:698-745` и
    `logistics/backend/app/models/domain.py:558-590`.
- Как воспроизвести:
  1. Дважды отправить один POST создания ручной заявки после потери первого
     ответа либо параллельно из двух вкладок.
  2. Оба запроса получают разные UUID и могут создать две одинаковые задачи.
  3. Аналогично параллельно создать две пересекающиеся смены одного водителя:
     оба transaction snapshot могут не увидеть ещё незафиксированную строку и
     пройти `_ensure_shift_available`.
- Сейчас: database constraint защищает external feed с заполненными source ID,
  но не ручные creates, nullable warehouse-wide driver и интервал смены.
- Должно быть: стабильный operation key/receipt для retryable creates,
  optimistic fence для edits и атомарный DB constraint либо advisory/resource
  lock для пересекающихся интервалов.
- Root cause: optimistic/идемпотентные механизмы хорошо реализованы в Spring
  owner-сервисах, но standalone catalog остался CRUD-моделью без command fence.
- Последствия: двойные доставки, двойной расход capacity, конфликтующие смены и
  lost update при нескольких диспетчерах; поздний планировщик не может отличить
  повтор команды от двух настоящих заявок.

### AUD-023 — рабочая дата вычисляется в UTC/Moscow/device timezone вместо timezone склада

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1` для multi-timezone rollout, `P2` в текущем московском регионе.
- Класс: `SYSTEMIC PROBLEM`, time ownership.
- Где:
  - task-board имеет доступ к warehouse metadata/timezone, но fallback даты и
    проверка «видно сегодня» жёстко используют `Europe/Moscow`:
    `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java:49,638-642,707-712`;
  - panel считает today через UTC ISO при подтверждении отгрузки и определении
    просроченных задач:
    `panel/src/features/logistics/logistics-shipments-page.tsx:631-642` и
    `panel/src/features/logistics/logistics-order-tasks-page.tsx:163-184`;
  - ManagerApp по умолчанию ставит device-local date и сравнивает её с
    `LocalDate.now()` устройства:
    `app/src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt:109,215`
    и
    `app/src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerTransferCoordinator.kt:176-179`;
  - DriverApp-часть той же проблемы отдельно описана в `AUD-005`.
  - standalone logistics UI инициализирует `planningDate` через UTC
    `new Date().toISOString().slice(0, 10)` в
    `logistics/frontend/src/app/App.tsx:138`, а warehouse-local дату ставит
    только последующим React effect (`:254-260`). В том же render после загрузки
    workspace уже включается изменяющий `ensureAutomaticPlan`
    (`:348-363`). Около границы суток первый запрос может начать генерацию плана
    для UTC-даты до переключения state на дату склада.
- Как воспроизвести:
  1. Выбрать склад `Asia/Novosibirsk` около полуночи склада, когда UTC,
     Europe/Moscow и warehouse-local date различаются.
  2. Создать внешнюю task без явной `scheduledDate`/deadline либо открыть
     подтверждение/перемещение в panel/ManagerApp.
  3. Разные части системы классифицируют операцию как разные календарные дни.
- Сейчас: warehouse-service хранит валидированный IANA timezone и эффективную
  историю, но этот источник истины не доведён до всех presentation/application
  clocks.
- Должно быть: бизнес-дата всегда вычисляется server-side для конкретного
  warehouse; UI получает её или использует warehouse timezone только для
  представления. Optional scheduledDate не должна неявно означать Moscow.
- Последствия: неверная дата задания, раннее/позднее уведомление, ложное
  предупреждение «не сегодня», отказ корректной команды около полуночи и
  расхождение между manager, driver и task-board. В standalone planner
  дополнительно возможен лишний draft/remote sync не того календарного дня ещё
  до того, как заголовок покажет правильную дату склада.

### AUD-024 — полный test gate task-board нестабилен из-за исчерпания ресурсов

- Severity: `P2`
- Класс: `SYSTEMIC PROBLEM`, test infrastructure/release reliability.
- Где:
  - `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/PostgresIntegrationTestSupport.java:8-21`
    разделяет один PostgreSQL Testcontainer между большим числом Spring contexts;
  - test profile не задаёт малый общий Hikari pool/max connection budget;
  - поздний
    `WorkerCredentialOperationCoordinatorIntegrationTest` не получил даже
    первое соединение: PostgreSQL вернул `FATAL: sorry, too many clients already`.
- Фактическая проверка: `:services:task-board-service:test` — 281 tests,
  277 passed, 2 failed, 2 skipped; Gradle test executor также сообщил
  `Java heap space`. Оба failures относятся к connection acquisition, а не к
  проверяемой credential-операции.
- Сейчас: отдельные tests могут проходить, но полный gate на общей машине
  накапливает Spring contexts/pools до лимита БД и heap.
- Должно быть: bounded datasource pools, контролируемое переиспользование/
  закрытие contexts, согласованные fork/heap limits и стабильный full-suite gate.
- Root cause: Testcontainers lifecycle общий, а совокупный lifecycle множества
  application contexts и connection pools не ограничен единым ресурсным
  бюджетом. Утечка production coordinator по этому результату не доказана.
- Последствия: ложные красные сборки, невозможность надёжно принять релиз и
  маскировка настоящих regression failures шумом инфраструктуры тестов.

### AUD-025 — нормализация изохронов удалила запрещённые, no-trailer и special-price зоны

- Статус исправления: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В ТЕКУЩЕМ WIP`.
  Исходный destructive факт ниже сохраняется как историческое доказательство;
  production replacement реализован миграциями 0029/V76, policy CRUD,
  capacity contract, planner/slot classification и UI. Удалённая ранее
  геометрия автоматически не восстанавливается без внешней резервной копии.
- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, business safety/data loss.
- Где:
  - standalone migration сначала ввела типы `FORBIDDEN`, `NO_TRAILER` и
    `SPECIAL_PRICE`:
    `logistics/backend/migrations/versions/20260829_0018_isochrone_tariffs_and_zone_policies.py:55-73`;
  - следующая migration удаляет `zones` вместе с classification fields и не
    переносит исключительные политики:
    `logistics/backend/migrations/versions/20260830_0020_normalize_isochrone_tariffs.py:65-91`;
  - Spring migration `V69` создала projection запретов, но `V74` удаляет и
    `customer_warehouse_capacity_restriction_zone`, и special-price projection:
    `services/logistics-service/src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql:52-71`;
  - текущий capacity contract передаёт только jobs, shifts и hourly tariffs:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/api/PlanningIntegrationApiModels.java:145-199`;
  - текущий `CustomerDeliverySlotService` проверяет Valhalla road route и
    hourly isochrone, но не warehouse-owned forbidden/no-trailer/special-price
    geometry:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java:77-152,320-448`.
- Как воспроизвести:
  1. До normalize migration создать полигон `FORBIDDEN`, `NO_TRAILER` или
     `SPECIAL_PRICE`.
  2. Применить актуальные migrations.
  3. Геометрия и текущая policy-таблица исчезают без replacement/archive.
  4. Запросить клиентские слоты для адреса внутри прежней зоны: решение
     основывается только на дороге, capacity и часовом тарифе.
- Сейчас: Valhalla продолжает учитывать ограничения дорожного графа, а
  пользователь подтверждает доступ автопоезда к объекту, но это не заменяет
  управляемый бизнесом запрет полигона, локальный запрет прицепа или спеццену.
- Должно быть: обычная дальность/цена определяется максимальной изохроной, а
  отдельные исключительные политики остаются warehouse-owned, versioned и
  атомарно участвуют в search/hold/planning.
- Root cause: удаление legacy delivery-price zones было расширено на все zone
  kinds, хотя исключительные политики имеют другую бизнес-семантику.
- Последствия: заказ в запрещённый район, построение маршрута с прицепом туда,
  где он запрещён локальным правилом, потеря специальной цены; migration также
  безвозвратно удаляет настроенную геометрию из runtime DB.

### AUD-026 — polling рабочего места каждые 15 секунд запускает изменяющую полную синхронизацию

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, API semantics, load amplification.
- Где:
  - UI запрашивает workspace каждые 15 секунд:
    `logistics/frontend/src/app/App.tsx:245-250`;
  - клиент по умолчанию не добавляет `refresh_rwms=false`:
    `logistics/frontend/src/api/client.ts:660-664,821-832`;
  - GET `/warehouses/{id}/workspace` при каждом таком запросе обновляет
    directory, последовательно синхронизирует 31 день каждого склада группы,
    инвалидирует mutable plans и генерирует missing drafts на все 31 даты:
    `logistics/backend/app/api/catalog.py:354-435`;
  - после добавления RBAC этот же endpoint требует только `VIEW`/`rwms.read`,
    хотя запускает перечисленные writes и удаление mutable планов
    (`catalog.py:395-435`, `api/authorization.py:32-40`). Поэтому read-only
    пользователь всё ещё способен инициировать изменяющую orchestration.
- Как воспроизвести:
  1. Открыть одно рабочее место planner и оставить вкладку активной.
  2. Наблюдать GET workspace раз в 15 секунд.
  3. Каждый GET выполняет межсервисные reads и потенциальные DB writes/plan
     generation, даже если диспетчер ничего не менял.
  4. Открыть пять диспетчерских вкладок — нагрузка умножается.
- Сейчас: семантически read-only GET является orchestration command и зависит
  от доступности нескольких сервисов; retry браузера повторяет command, а
  permission model ошибочно классифицирует его как безопасное чтение.
- Должно быть: bounded authoritative read; синхронизация — отдельный
  идемпотентный/background ingestion flow с revision/cursor, а UI получает
  projection и targeted invalidations.
- Последствия: thundering herd, лишние блокировки и plan invalidation,
  повторная генерация, нагрузка на RWMS/БД/CPU и нестабильный UI при частичном
  отказе зависимости.

### AUD-027 — workspace API передаёт всю историю заявок и UI создаёт DOM marker на каждую точку дня

- Severity: `P1` при целевом масштабе, `P2` сейчас.
- Класс: `SYSTEMIC PROBLEM`, API/frontend performance.
- Где:
  - backend читает все заявки каждого member без date/status/page filter:
    `logistics/backend/app/api/catalog.py:439-485` и
    `logistics/backend/app/services/catalog.py:1060-1073`;
  - response одновременно включает все routing-ready warehouses и полные
    resources/catalogs;
  - frontend затем фильтрует историю по выбранной дате:
    `logistics/frontend/src/features/planning/PlanningDayRequests.tsx:160-170`;
  - карта удаляет и создаёт отдельный MapLibre DOM `Marker` для каждой видимой
    заявки при изменении набора:
    `logistics/frontend/src/map/MapCanvas.tsx:804-867`;
  - весь unbounded workspace повторно загружается по `AUD-026` каждые 15 секунд.
- Как воспроизвести: накопить несколько лет истории и тысячи точек на один день,
  открыть workspace и измерить payload, heap и время marker effect.
- Сейчас: eager `selectinload` устраняет N+1 для request children, но не
  ограничивает число parent rows или DOM nodes.
- Должно быть: date/status cursor API, отдельные компактные day/map projections,
  viewport/server filtering и GeoJSON source clustering вместо тысяч DOM
  marker instances.
- Последствия: линейный рост ответа с историей, GC/render freezes и повторный
  сетевой/DOM churn каждые 15 секунд.

### AUD-028 — после 30 активных точек CustomerApp перестаёт предлагать любые новые слоты

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1`
- Класс: algorithmic capacity ceiling/product outage.
- Где:
  - `CustomerDeliverySlotService.MAX_MATRIX_POINTS = 32`:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java:61`;
  - matrix содержит depot, все existing/generated jobs и нового кандидата;
  - если `points.size() > 32`, `routeContext` возвращает пустой набор:
    тот же файл `:320-389`;
  - search затем молча не формирует offer для каждой даты: `:77-152`.
- Как воспроизвести:
  1. На дату иметь суммарно 31 подтверждённую/held/generated точку доставки.
  2. Клиент с бытовкой запрашивает ещё один адрес.
  3. Получается 33 matrix points (depot + 31 + candidate), и дата исчезает из
     предложений независимо от числа смен и реальной выполнимости.
- Сейчас: технический лимит размера одного Valhalla matrix превращён в жёсткий
  бизнес-лимит всего склада/дня без понятной ошибки или partitioning.
- Должно быть: bounded decomposition/candidate insertion against route
  projections либо отдельная capacity model; при внутреннем limit — явное
  диагностируемое состояние, а не «слотов нет».
- Последствия: занятый, но нормально масштабированный склад перестаёт принимать
  все новые заказы уже примерно после 30 адресов в сутки.

### AUD-029 — support link создаёт неподкреплённую мощность представительского склада

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, overselling/resource reservation.
- Исправление: support link и его календарь больше не считаются подтверждённой
  клиентской мощностью. И обычный, и представительский склад fail-closed без
  фактически подтверждённой локальной route-capacity; представительский склад
  по-прежнему предлагает только `DURING_DAY`. External support остаётся
  topology планировщика до появления durable reserved-capacity token.
- Проверка: независимый общий logistics gate прошёл `146/146`, включая policy,
  search, estimate, hold recheck и отсутствие обращения к support gateway.
- Где:
  - при отсутствии локальной capacity policy проверяет лишь активную входящую
    связь, флаги, календарь и пересечение часов:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/RepresentativeDeliverySlotPolicy.java:29-91`;
  - она не проверяет конкретного водителя, машину, смену, время доезда,
    вместимость, бытовку или уже обещанную внешнюю capacity;
  - search/hold записывают для такого fallback `capacityRemaining=0`, но всё
    равно создают/удерживают offer:
    `CustomerDeliverySlotService.java:128-152,242-275`;
  - day/warehouse lock в `CustomerDeliverySlotHoldStore.java:48-123` защищает
    fingerprint локальной нагрузки, но не резервирует внешний ресурс и не
    ограничивает число support-backed holds;
  - API не имеет `requiresConfirmation`; сущность ставит
    `roadRouteConfirmed=true`: `CustomerDeliverySlot.java:200-213`;
  - CustomerApp показывает для любого такого offer
    «Маршрут подтверждён · вариантов: 0»:
    `client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/DeliveryFlowScreens.kt:590-610`.
- Как воспроизвести:
  1. У представительского склада нет локальных смен.
  2. Создать одну разрешённую support link без реально свободного водителя.
  3. Несколько клиентов одновременно выбирают этот день.
  4. Все получают и могут hold-ить full-day offer; ни один внешний ресурс не
     резервируется и его физическая выполнимость не рассчитана.
- До исправления: подпись «Точное время подтвердит логист» честно говорила о неточном
  времени, но соседняя подпись утверждала подтверждённый маршрут, а дата и
  мощность не отделены как tentative.
- Должно быть: либо атомарно подтверждённый cross-warehouse/contractor capacity
  token, либо явный `requiresConfirmation`/request-only flow без обещания
  маршрута и без неограниченного overselling.
- Последствия: представительский склад может принять больше заказов, чем
  способен обслужить; логист узнает об этом после checkout и вынужден вручную
  переносить клиентов.

### AUD-030 — журнал ручных изменений доверяет имени, присланному самим клиентом

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, audit integrity, authorization.
- Где:
  - `logistics/backend/app/schemas/domain.py:1579-1602` принимает
    `confirmed_by` и `changed_by` прямо из request body и по умолчанию ставит
    строку `local-admin`;
  - `logistics/backend/app/api/plans.py:163-176` без server-side principal
    передаёт `payload.confirmed_by` в команду подтверждения;
  - `logistics/backend/app/services/plans.py:434-466` сохраняет присланное
    `changed_by` в `manual_change_audits`;
  - `logistics/backend/app/services/plans.py:470-555` записывает присланное
    `confirmed_by` как автора разрешения пустого перегона;
  - `logistics/backend/app/services/planning_days.py:106-133` аналогично
    использует фиктивный `local-admin` для закрытия дня;
  - `logistics/backend/app/models/domain.py:1118-1136` хранит строку автора как
    будто это доверенный audit fact.
- Как воспроизвести:
  1. Вызвать manual-change или confirm endpoint.
  2. Передать `changed_by: "Генеральный директор"` либо произвольное
     `confirmed_by`.
  3. Увидеть эту строку в неизменяемом журнале без связи с authenticated
     subject.
- Сейчас: автор операции является пользовательским вводом либо константой, а
  не идентификатором установленной сервером identity.
- Должно быть: subject/user ID, display-name snapshot и warehouse scope должны
  извлекаться из проверенного principal; клиент может прислать причину, но не
  автора.
- Root cause: FastAPI boundary не имеет authentication context (AUD-001), а
  transport DTO одновременно используется как audit context.
- Последствия: журнал нельзя использовать для расследований, ответственности
  или доказательства ручного разрешения; злоумышленник и обычный клиент могут
  подделать автора бизнес-решения.

### AUD-031 — reoptimize оставляет исходный план активным и допускает несколько подтверждённых планов на один день

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, planning versioning, concurrency.
- Где:
  - `logistics/backend/app/services/planner_runtime.py:444-465` блокирует
    исходный plan, строит новый, но не архивирует и не связывает замену с
    источником;
  - `logistics/backend/app/services/planner_runtime.py:1555-1634` всегда
    вставляет новый `RoutePlan`;
  - `logistics/backend/app/models/domain.py:782-808` и Flyway-схема имеют лишь
    обычный индекс `(warehouse_id, date)`, но не invariant «один published
    plan»;
  - `logistics/backend/app/services/plans.py:470-557` при confirm блокирует и
    проверяет только выбранный plan ID, не проверяя другой CONFIRMED plan того
    же склада/даты;
  - `logistics/backend/app/integrations/rwms_sync.py:576-620` позволяет
    независимо применить каждый точный plan/version в RWMS с разными
    idempotency keys.
- Как воспроизвести:
  1. Создать валидный plan A.
  2. Вызвать `/plans/{A}/reoptimize`; появится plan B, а A останется
     неархивированным.
  3. Подтвердить A и B по их собственным актуальным версиям.
  4. Оба станут `CONFIRMED`; оба можно отдельно подготовить к RWMS apply.
- Сейчас: optimistic locking защищает отдельную версию от lost update, но не
  защищает бизнес-инвариант публикации на уровне warehouse/date.
- Должно быть: reoptimization должна создавать явно связанную draft-revision,
  а атомарная публикация — архивировать/замещать предыдущую либо отклонять
  вторую публикацию серверным и DB-level ограничением.
- Root cause: `RoutePlan` моделируется как независимый документ, но активность
  и публикация не имеют отдельного slot/revision aggregate с единственным
  current pointer.
- Последствия: два диспетчера могут опубликовать несовместимые назначения,
  дважды передать один спрос в RWMS и видеть разные «актуальные» планы в
  зависимости от `updated_at`.

### AUD-032 — закрытие дня сообщает успех, даже если RWMS отклонил назначения финального плана

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, integration consistency, false success.
- Где:
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java:237-315`
    намеренно возвращает HTTP 200 с двумя списками: `applied` и `rejected`;
  - `contracts/openapi/logistics-service.yaml:825-853` закрепляет частичный
    результат как нормальный ответ, а не HTTP-ошибку;
  - `logistics/backend/app/integrations/rwms.py:244-259` корректно парсит и
    сохраняет оба списка в `RwmsApplyResult`;
  - `logistics/backend/app/api/plans.py:109-117` при закрытии дня игнорирует
    возвращённый `RwmsApplyResult` целиком;
  - `logistics/frontend/src/app/App.tsx:1121-1137` после HTTP 200 показывает
    «Приём доставок закрыт; финальный план пересчитан» без статуса публикации.
- Как воспроизвести:
  1. Оставить в финальном плане RWMS-заказ, который успел изменить version либо
     уже получил отгрузку.
  2. Закрыть день.
  3. logistics-service вернёт `rejected=[ORDER_VERSION_CONFLICT]` или
     `ORDER_UNIT_ALREADY_PLANNED` с HTTP 200.
  4. Planner отбросит результат, endpoint и UI сообщат успех.
- Сейчас: день необратимо закрыт, но часть или все задания могли не попасть в
  канонический RWMS, и оператор этого не видит.
- Должно быть: close result должен содержать durable publication outcome;
  полный/частичный reject требует явного статуса «нужна сверка», списка
  проблемных заказов и безопасного retry/reconciliation flow.
- Root cause: orchestration boundary трактует транспортный HTTP success как
  бизнес-успех и не обрабатывает доменный partial result.
- Последствия: водитель не получает ожидаемое задание, планировщик показывает
  закрытый успешный день, а восстановление требует ручной диагностики двух
  систем.

### AUD-033 — одна гибкая заявка может одновременно находиться в планах нескольких дат

- Severity: `P1`
- Класс: `SYSTEMIC PROBLEM`, cross-date reservation, impossible state.
- Где:
  - `logistics/backend/app/services/planner_runtime.py:142-151` считает заявку
    без `scheduled_date` доступной на каждой дате из `date_options`;
  - `logistics/backend/app/services/auto_planning.py:51-171` строит независимый
    active plan для каждой даты диапазона;
  - `logistics/backend/app/services/planner_runtime.py:754-789` загружает ту же
    READY-заявку и те же `PlanningTask` во все подходящие даты;
  - `logistics/backend/app/models/domain.py:782-829` не имеет cross-plan
    reservation/unique assignment для task;
  - `logistics/backend/app/services/plans.py:470-557` confirmation не фиксирует
    выбранную дату и не переводит request/task из READY;
  - канонический logistics-service защитит физические бытовки и order version
    при apply (`RentalOrderPlanningIntegrationService.java:318-387`), но это
    произойдёт только после того, как один из локальных планов уже будет
    опубликован.
- Как воспроизвести:
  1. Импортировать заказ с двумя клиентскими `dateOptions` и без локального
     `scheduled_date`.
  2. Иметь полные ресурсы/окна на обе даты.
  3. Автогенерация создаст планы D1 и D2 с одним и тем же task ID.
  4. Подтвердить оба плана: локального запрета нет.
  5. Первый RWMS apply пройдёт, второй поздно получит domain rejection; из-за
     AUD-032 оператор при закрытии дня может его не увидеть, а из-за AUD-002
     stale READY-строка не обязательно исчезнет после refresh.
- Сейчас: `date_options` используются одновременно и как варианты выбора, и
  как разрешение включить заказ во множество параллельных operational plans.
- Должно быть: варианты могут участвовать в side-effect-free сравнении, но
  выбор draft/published date должен атомарно владеть заявкой и исключать её из
  конкурирующих планов; отмена должна освобождать этот выбор.
- Root cause: нет отдельной cross-date allocation/reservation state machine;
  план является контейнером маршрутов, но не владельцем эксклюзивного
  назначения спроса.
- Последствия: двойное отображение одной доставки, конфликтующие обещания по
  дням, поздние ошибки публикации и некорректные capacity/slot расчёты.

### AUD-034 — customer routing cache не инвалидируется при обновлении дорожного графа

- Severity: `P2` сейчас, `P1` при dynamic routing/оперативных ограничениях.
- Класс: routing correctness/performance, `SYSTEMIC PROBLEM`.
- Где:
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/routing/ValhallaCustomerTravelTimeClient.java:31-68`
    держит process-local cache до 512 матриц без TTL;
  - cache key содержит точки, дату, 15-минутный bucket, vehicle profile и
    только строку URL Valhalla (`:56-62,174-180`), но не версию tiles/graph;
  - при заполнении выполняется общий `cache.clear()` (`:67`);
  - на каждом cache miss создаётся новый `HttpClient` (`:113-115`), поэтому
    соединения между расчётами не переиспользуются;
  - standalone planner, в отличие от этого пути, включает routing/provider
    version в cache identity:
    `logistics/backend/app/services/planner_runtime.py:924-934` и
    `logistics/backend/app/routing/valhalla.py:887`.
- Как воспроизвести:
  1. Рассчитать клиентские слоты для одного набора точек/машины/date bucket.
  2. Обновить Valhalla tiles либо дорожные restrictions, не меняя URL.
  3. Повторить запрос в том же процессе: вернётся старая матрица.
- Сейчас: старая дорога может жить до случайного заполнения cache; после
  достижения 512 записей все горячие ключи сбрасываются одновременно.
- Должно быть: один переиспользуемый HTTP client, bounded cache с TTL и/или
  routing-data version в ключе, адресная инвалидация при смене graph/profile.
- Root cause: поле `routingConfigurationVersion` фактически получает URL, а
  lifecycle дорожных данных не входит в контракт customer slot calculation.
- Последствия: устаревшие слоты/ETA после изменения дорог и ограничения,
  connection churn, скачок нагрузки на Valhalla после полного cache clear.

### AUD-035 — cross-warehouse план нельзя опубликовать в RWMS/DriverApp

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: shift plan хранит отдельные `routeOriginWarehouseId` и
  `supportWarehouseLinkId`; canonical owner валидирует связь, а planner
  публикует физически выполнимое позиционирование без warehouse mismatch.

- Severity: `P1`
- Класс: подтверждённый end-to-end contract bug, ключевой product flow.
- Где:
  - support candidate сохраняет исходный persisted shift опорного склада:
    `logistics/backend/app/services/planner_runtime.py:947-999`;
  - cycle хранит FK именно на этот shift:
    `logistics/backend/app/services/planner_runtime.py:1635-1654`;
  - `DriverShift.warehouse_id` неизменно принадлежит опорному складу:
    `logistics/backend/app/models/domain.py:558-598`;
  - при построении outbound DriverApp snapshot
    `logistics/backend/app/integrations/rwms_sync.py:470-492` требует
    `shift.warehouse_id == plan.warehouse_id` и иначе выбрасывает
    `DRIVER_SHIFT_WAREHOUSE_CONFLICT`;
  - для representative day plan `plan.warehouse_id` — обслуживаемый склад, а
    support shift — склад-источник; это ровно допустимая cross-warehouse
    комбинация, которую проверка запрещает.
- Как воспроизвести:
  1. Открыть представительский склад без локального водителя, связанный с
     опорным складом.
  2. Построить план: planner корректно добавит support shift после времени
     прибытия и покажет `CROSS_WAREHOUSE_SERVICE`.
  3. Подтвердить/закрыть день с RWMS delivery.
  4. `build_assignments_command -> _build_driver_shift_plans ->
     _driver_shift_plan_snapshot` завершится 422
     `DRIVER_SHIFT_WAREHOUSE_CONFLICT` до канонического apply.
- Сейчас: cross-warehouse маршрут существует в UI/локальной БД, но не может
  стать сменным заданием водителя через поддерживаемый publish path.
- Должно быть: контракт отдельно передаёт route origin/resource warehouse и
  service warehouse, а canonical owner атомарно подтверждает допустимую
  support link/operational availability; не надо подменять склад shift.
- Root cause: legacy invariant «shift и plan всегда одного склада» остался в
  adapter после появления `CROSS_WAREHOUSE_SERVICE`.
- Последствия: представительский склад выглядит запланированным, но водитель
  не получает исполнимое задание; закрытие дня падает технической domain
  ошибкой, ключевой межскладской сценарий обрывается на последней границе.

### AUD-036 — межскладской перегон хранится как метрика, а не как исполнимые шаги маршрута

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1` для cross-warehouse исполнения.
- Класс: domain/contract gap, `SYSTEMIC PROBLEM`.
- Где:
  - support shift синтетически начинается уже около момента доступности на
    обслуживаемом складе; inbound и return только вычитаются из доступного
    интервала: `logistics/backend/app/services/planner_runtime.py:947-999`;
  - persisted `RouteStop`/`RouteSegment` создаются лишь из `core_cycle.stops`
    и `.legs` (`:1701-1762`), а positioning geometry/time сохраняются только
    в JSON metrics/explanation (`:1772-1867`);
  - outbound assignment contract содержит заказ, дату, водителя и unit IDs, но
    не stop sequence, planned arrival/departure, route origin или warehouse
    load/unload actions:
    `contracts/openapi/logistics-service.yaml:3832-3887`;
  - `PlanningDriverShiftPlan.routeDistanceMeters` описан как сумма только
    planner route-cycle meters (`:3911-3935`), а adapter действительно суммирует
    `cycle.total_distance_meters`, не support positioning:
    `logistics/backend/app/integrations/rwms_sync.py:399-465`.
- Как воспроизвести:
  1. Построить `CROSS_WAREHOUSE_SERVICE` из опорного склада в
     представительский.
  2. В UI видны линия и время перегона из metrics.
  3. Проверить сохранённые stops/segments и outbound RWMS command: в них нет
     действий «прибыть на опорный склад → загрузить → ехать на региональный
     склад → выгрузить», а shift distance не включает дорогу туда/обратно.
- На исходном срезе: карта умеет нарисовать служебную линию, но canonical task flow и
  offline DriverApp не получают эту линию как ordered executable operations.
- Должно быть: одна физическая ходка с versioned ordered operations и leg-load
  transitions; positioning, transfer cargo и клиентские действия должны
  публиковаться одним исполнимым snapshot, сохраняя отдельных владельцев
  складских переходов.
- Root cause: расширение planner/UI остановилось на projection metrics и не
  расширило canonical planning/driver-task contract.
- Последствия: даже после исправления AUD-035 водитель не увидит точный порядок
  межскладских действий; planned-vs-actual, ETA, POD и пробег смены будут
  неполными, offline-клиент не сможет безопасно валидировать порядок загрузки и
  выгрузки.
- Исправленная часть: exact positioning departure/arrival/return и метры теперь
  включены в один published shift snapshot вместе с planner depot/customer
  stops. Logistics, task-board V41 и DriverApp сохраняют/валидируют/показывают
  ordered operations, ETA и load chain; plan нельзя заменить после freeze.
- Закрытие остатка: owner-side enrichment добавляет точный подтверждённый и
  зарезервированный transfer cargo как парные warehouse load/unload operations
  в ту же смену, task-board фиксирует source transfer identity и capacity, а
  DriverApp показывает общий порядок. Exact assigned transfer task связывает
  `CURRENT` с departure и completion evidence с factual arrival через
  version-fenced durable relay; до этих переходов inventory не меняется.

### AUD-037 — UI/auto-dispatch принимает вывоз для наёмника, но реальный RWMS-вывоз запрещён

- Severity: `P1` для заявленного fallback-сценария, иначе `P2` contract/UX.
- Класс: подтверждённый use-case mismatch.
- Где:
  - eligibility явно разрешает `DELIVERY` и `PICKUP` и обещает пользователю
    «доставку или вывоз»:
    `logistics/backend/app/services/contractor_assignment.py:342-377`;
  - AUTO selection также выбирает оба типа (`:313-329`);
  - batch может поэтому включить реальный RWMS pickup (`:574-610`);
  - transport mapping затем безусловно отвергает такой pickup с
    `RWMS_CONTRACTOR_PICKUP_UNSUPPORTED` (`:635-647`).
- Как воспроизвести:
  1. На выбранный день оставить нераспределённые реальную доставку и реальный
     вывоз RWMS.
  2. Для активного наёмника нажать автоматическое распределение.
  3. AUTO выберет обе заявки, затем весь batch завершится 422 на вывозе; не
     будет передана даже допустимая доставка.
- Сейчас: тестовая/generated pickup может быть локально помечена как handoff,
  но каноническая реальная pickup-команда отсутствует; UI этого заранее не
  различает.
- Должно быть: либо canonical pickup handoff реализован end-to-end, либо
  недоступная операция исключается из AUTO и явно disabled с объяснением до
  команды; смешанный batch не должен терять допустимые элементы молча.
- Root cause: общий selector типов опережает capabilities канонического
  assignment adapter.
- Последствия: именно при полной загрузке штатных водителей fallback, ради
  которого добавлен наёмник, ломается на смешанном дне доставки/вывозы.

### AUD-038 — CustomerApp не имеет supported flow отмены или изменения подтверждённого заказа

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P2` сейчас, `P1` по операционной нагрузке при росте.
- Класс: подтверждённый product/API gap.
- Где:
  - public customer boundary перечисляет profile, inquiry/cart mutations,
    slot search/hold, checkout, booking list и post-arrival acceptance/problem,
    но не cancel/reschedule:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/api/CustomerController.java:43-276`;
  - `client-app/app/src/main` не содержит команды отмены booking/order;
  - `CustomerDeliverySlotService.release` и `CustomerCheckoutStore` освобождают
    только ещё не подтверждённый slot при внутреннем terminal rejection, а не
    клиентскую отмену подтверждённого заказа.
- Как воспроизвести:
  1. Клиент успешно оформляет checkout и видит booking в `/bookings`.
  2. До публикации/отгрузки хочет изменить дату или отменить заказ.
  3. В CustomerApp и public API нет операции; требуется ручное обращение к
     менеджеру по другому каналу.
- Сейчас: customer flow заканчивается просмотром booking до post-delivery
  acceptance; cancellation state machine клиенту не доступна.
- Должно быть: явная policy допустимого cancellation/reschedule с
  `expectedVersion`/idempotency, компенсацией slot/cabin/equipment holds,
  инвалидацией планов и понятным статусом/стоимостью отмены.
- Последствия: ручная нагрузка на менеджеров, поздние отмены остаются в
  логистике до внешнего вмешательства, клиент может считать, что закрытие
  приложения отменило ещё исполняемый заказ.

### AUD-039 — lifecycle заявки planner является редактируемым enum, а не state machine

- Severity: `P1`
- Класс: impossible states/domain ownership, `SYSTEMIC PROBLEM`.
- Где:
  - create и PATCH DTO принимают любой `RequestStatus`, включая
    `IN_PROGRESS`, `COMPLETED`, `CANCELLED` и `UNASSIGNED`:
    `logistics/backend/app/schemas/domain.py:659-730`;
  - `update_request` применяет status обычным `setattr` без проверки перехода
    и без синхронного перехода дочерних tasks:
    `logistics/backend/app/services/catalog.py:1078-1173`;
  - `RWMS_SOURCE_FIELDS` не включает status (`:67-77`), поэтому даже
    authoritative RWMS projection можно локально перевести в любой статус;
  - planner лишь фильтрует `request.status == READY`, но не доказывает
    происхождение состояния:
    `logistics/backend/app/services/planner_runtime.py:1079`.
- Как воспроизвести:
  1. Создать manual request сразу со статусом `COMPLETED`, не выполняя рейс; или
     PATCH READY-заявку в `IN_PROGRESS` без plan/driver.
  2. Для RWMS feed строки PATCH status в `CANCELLED`: защита source fields не
     сработает.
  3. Request сменит статус, а его `PlanningTask.status` может остаться READY.
- Сейчас: возможны комбинации «request COMPLETED, tasks READY», «IN_PROGRESS
  без route», возврат из terminal state в READY и локальная отмена
  authoritative заказа без события владельца.
- Должно быть: команды намерения и централизованная transition table;
  authoritative lifecycle меняется только из owner feed/event, aggregate
  request/tasks переходит атомарно, terminal transitions idempotent.
- Root cause: transport CRUD model используется как domain command model.
- Последствия: заявки исчезают из планирования или возвращаются в него без
  реального бизнес-события; аудит, capacity и состояние RWMS расходятся.

### AUD-040 — новая/изменённая заявка физически удаляет подтверждённый план дня и его аудит

- Severity: `P1` (потеря operational/audit state; до `P0`, если этот план
  считается обязательным журналом исполнения).
- Класс: подтверждённый destructive lifecycle bug.
- Где:
  - общий invalidation helper выполняет безусловный SQL DELETE всех plans
    склада/дат, не фильтруя `DRAFT/GENERATED/VALIDATED`:
    `logistics/backend/app/services/catalog.py:105-118`;
  - `create_request` вызывает его до вставки любой новой заявки с date options:
    `:883-902`;
  - тот же helper вызывают RWMS upsert, обычный update/schedule и другие
    request mutations (`:948,997,1157,1182,1217,1342-1454`);
  - `RoutePlan` каскадно владеет cycles, unassigned tasks, manual change audit и
    notification logs:
    `logistics/backend/app/models/domain.py:782-829`; route stops/segments также
    удаляются каскадом ниже по графу.
- Как воспроизвести:
  1. Построить и подтвердить план склада на дату D; при необходимости
     опубликовать assignments в RWMS.
  2. Создать ещё одну manual заявку с `date_options=[D]` либо получить новую
     ревизию RWMS-заказа на D.
  3. `create_request/upsert` удалит строку CONFIRMED plan вместе с маршрутами,
     объяснениями и audit rows; канонические shipment/driver tasks, уже
     созданные удалённым apply, останутся.
- Сейчас: «инвалидация» реализована как уничтожение любого плана, хотя
  confirmed/published является историческим фактом.
- Должно быть: mutable draft можно заменить/архивировать; published plan
  immutable, новая заявка создаёт amendment/new planning version и exception,
  а отмена опубликованного эффекта проходит отдельной компенсирующей командой.
- Root cause: helper не различает cache-like draft projection и business/audit
  record опубликованного плана.
- Последствия: диспетчер теряет доказательство, что именно было утверждено,
  UI и DriverApp/RWMS расходятся, повторное планирование может создать второй
  набор назначений поверх уже опубликованного.

### AUD-041 — защита от удаления уже запланированной заявки никогда не срабатывает

- Severity: `P1`.
- Класс: подтверждённый destructive ordering bug, lifecycle/integrity.
- Где:
  - public endpoint заявляет, что удаляет заявку только при отсутствии ссылок
    сохранённого плана:
    `logistics/backend/app/api/catalog.py:825-837`;
  - service сначала вызывает удаление **всех** `RoutePlan` по датам заявки:
    `logistics/backend/app/services/catalog.py:1446-1458`;
  - лишь после этого ищет `RouteStop`/`UnassignedTask` references и собирается
    вернуть `REQUEST_ALREADY_PLANNED`: `:1459-1474`;
  - foreign keys и ORM relationships каскадно удаляют cycles, stops,
    unassigned rows и audit graph:
    `logistics/backend/app/models/domain.py:810-829,863-900,917-939,1007-1023`;
  - request-scoped session успешно commit-ится после handler:
    `logistics/backend/app/db.py:75-84`.
- Как воспроизвести:
  1. Создать ручную заявку и построить план, содержащий её task как назначенный
     stop либо unassigned task.
  2. Вызвать `DELETE /api/requests/{requestId}`.
  3. Invalidation удалит план и его references до защитного SELECT.
  4. Оба SELECT уже ничего не найдут; endpoint удалит саму заявку и вернёт 204,
     а не 409 `REQUEST_ALREADY_PLANNED`.
- Сейчас: порядок операций превращает проверку целостности в недостижимую
  защиту; запланированная и даже уже опубликованная manual заявка удаляется.
- Должно быть: reference/status check выполняется и блокирует команду **до**
  любого mutation; опубликованные facts не удаляются вообще, а отменяются
  отдельным audited transition/compensation flow.
- Root cause: destructive cache invalidation поставлена перед domain
  precondition; отсутствует regression test на delete уже запланированной
  заявки (поиск в `logistics/backend/tests` не нашёл такого сценария).
- Последствия: потеря заявки, маршрута и ручного аудита при уже существующих
  удалённых заданиях RWMS; 204 создаёт ложное впечатление безопасного удаления.

### AUD-042 — генератор тестовой нагрузки удаляет планы реальных RWMS-заявок

- Severity: `P1`.
- Класс: подтверждённое смешение test/simulation и production planning state.
- Где:
  - generator корректно удаляет только requests с
    `source_system=GENERATOR`, но отдельный helper удаляет **каждый**
    `RoutePlan` склада в диапазоне без проверки source/status:
    `logistics/backend/app/services/workload_generator.py:111-153`;
  - и создание, и удаление тестовой нагрузки вызывают этот helper:
    `:170-202,287-305`;
  - public endpoints доступны как обычные warehouse commands:
    `logistics/backend/app/api/catalog.py:272-314,317-344`;
  - UI предупреждает, что удалит «все сохранённые планы», но одновременно
    утверждает, что ручные и RWMS-операции останутся без изменений:
    `logistics/frontend/src/app/App.tsx:1112-1119`.
- Как воспроизвести:
  1. Синхронизировать реальную RWMS-заявку на дату D и подтвердить её план.
  2. Для того же склада вызвать «Создать нагрузку» с horizon, включающим D,
     либо «Удалить нагрузку» за D.
  3. Реальная заявка останется, но её план, cycles, stops, explanation и audit
     будут удалены вместе с тестовыми artefacts.
- Сейчас: isolation действует только на requests; план не хранит provenance и
  очищается по warehouse/date целиком, включая `CONFIRMED`.
- Должно быть: simulation/test workspace или как минимум provenance/version
  изолирует generated plans; test cleanup не мутирует real/manual/RWMS plan и
  никогда не удаляет published operational facts.
- Root cause: route plan моделируется как единственный заменяемый артефакт даты,
  хотя в одной таблице смешаны test, draft и published business state.
- Последствия: тестирование диспетчера уничтожает реальный план дня и его
  аудит; уже опубликованные DriverApp/RWMS assignments расходятся с planner UI.

### AUD-043 — черновой/неутверждённый план можно опубликовать в RWMS

- Severity: `P1`.
- Класс: подтверждённый lifecycle/authorization-of-effect defect.
- Где:
  - public command `POST /plans/{planId}/rwms/apply` напрямую вызывает remote
    apply: `logistics/backend/app/api/rwms.py:123-132`;
  - `prepare_plan_for_rwms_apply` проверяет только optimistic version, но не
    `PlanStatus.CONFIRMED`: `logistics/backend/app/integrations/rwms_sync.py:597-614`;
  - `build_assignments_command` также не проверяет status:
    `:202-401`;
  - закрытие приёма сначала архивирует **все** active plans, включая ранее
    подтверждённый, генерирует новый automatic plan и сразу подготавливает его
    к RWMS apply, не вызывая `confirm_plan`:
    `logistics/backend/app/services/planning_days.py:106-160` и
    `logistics/backend/app/api/plans.py:68-117`;
  - generated plan создаётся со статусом `GENERATED`:
    `logistics/backend/app/services/planner_runtime.py:1590-1615`.
- Как воспроизвести:
  1. Построить plan со статусом `GENERATED`, не нажимать «Утвердить».
  2. Вызвать `/plans/{id}/rwms/apply` с текущей version; либо закрыть приём
     доставок и позволить auto-apply финального вновь сгенерированного plan.
  3. Assignments будут отправлены canonical logistics/task-board, хотя в
     planner plan не проходил confirmation transition.
- Сейчас: `CONFIRMED` блокирует дальнейшее ручное редактирование, но не является
  обязательным precondition внешнего необратимого эффекта.
- Должно быть: ровно одна server-owned publish transition атомарно фиксирует
  immutable published version; external apply принимает только её, а повтор
  использует тот же durable receipt/idempotency key. Закрытие клиентского
  приёма и утверждение операторского плана не должны неявно подменять друг
  друга.
- Root cause: acceptance closure, plan confirmation и RWMS publication — три
  раздельные команды без общей state-machine инварианты.
- Последствия: DriverApp получает назначения из плана, который UI всё ещё
  считает редактируемым; последующее изменение/удаление этого plan создаёт
  расхождение operational truth.

### AUD-044 — полный panel test gate даёт ложные timeout-failures

- Severity: `P2` (release/test reliability; product failure не подтверждён).
- Класс: test infrastructure, suite resource contention.
- Фактическая проверка:
  - полный `panel/npm test`: 221 files / 1 334 tests, 1 328 passed, 6 failed;
    все шесть остановились ровно по 5-секундному timeout;
  - затронуты четыре файла: logistics returns, rental-item command access,
    repair-estimate catalog picker и transfer-plan dialog;
  - focused rerun ровно этих файлов: 4 files / 48 tests, 48 passed за 26,58 с.
- Сейчас: полный gate красный, хотя каждый спорный набор проходит в
  изолированном запуске; тесты конкурируют за CPU/event loop при жёстком общем
  5-секундном лимите.
- Должно быть: suite имеет доказанный concurrency/resource budget, а таймауты
  отражают реальные зависания, не wall-clock pressure соседних файлов.
- Последствия: нестабильный release gate, повторные прогоны и риск пропустить
  реальную regression среди инфраструктурного шума.

### AUD-045 — после каждого reload старые региональные заявки объявляются «новыми»

- Severity: `P2`.
- Класс: notification semantics, UX/operational trust.
- Где:
  - при первом получении workspace effect проходит по **всем** RWMS requests
    представительских складов и создаёт toast «Новая заявка»:
    `logistics/frontend/src/app/App.tsx:285-319`;
  - дедупликация существует только в `useRef(Set)` текущего React lifetime:
    `App.tsx:152,300-301`;
  - notification history хранится только в неперсистентном Zustand state,
    стартует пустой после reload и ограничивается последними 200:
    `logistics/frontend/src/stores/ui-store.ts:158-210`.
- Как воспроизвести:
  1. Иметь несколько старых RWMS-заявок связанного представительского склада.
  2. Открыть planner: каждая будет объявлена «Новая заявка».
  3. Перезагрузить страницу: те же уведомления появятся снова, а прочитанность
     и история предыдущей сессии исчезнут.
- Сейчас: UI сравнивает данные только с пустым process-local set, а не с
  server event/revision/cursor или хотя бы инициализированным baseline.
- Должно быть: durable server-owned notification/event identity и прочитанность
  пользователя либо явный initial baseline без уведомления; alert создаётся
  только для revision, действительно появившейся после watermark.
- Последствия: диспетчер перестаёт доверять колокольчику, может повторно
  обработать старую заявку и пропустить настоящую новую среди шума.

### AUD-046 — назначенный наёмник не имеет поддержанного канала получения и исполнения рейса

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1`.
- Класс: незавершённый end-to-end flow, external carrier execution.
- Где:
  - private task-board endpoint явно создаёт contractor profile без учётных
    данных: `InternalLogisticsDriverAssignmentController.createContractor`;
  - профиль хранит только identity/contact: `ContractorDriverService.create`;
  - planner записывает `CONTRACTOR_HANDOFF`, worker ID, имя и телефон и
    публикует `ASSIGNED_DRIVER` task:
    `contractor_assignment._persist_contractor_handoff` и
    `_rwms_contractor_assignments`;
  - обычный DriverApp требует `WORKER` JWT и точную worker identity.
- Как воспроизвести:
  1. Создать профиль наёмного водителя.
  2. Передать ему реальную доставку.
  3. Попытаться отправить исполнителю маршрут, адреса, состав груза и фото или
     открыть назначение под его учётной записью.
- До исправления: в canonical contracts и активных клиентах не найдено ни создания
  credentials для этого потока, ни подписанной ограниченной ссылки, ни
  публичной contractor-страницы, ни отдельного API получения/подтверждения
  такого рейса. Телефон хранится как контакт, но не является delivery channel
  или authentication identity.
- Должно быть: передача должна завершаться исполнимым каналом — либо явно
  provisioned contractor credentials и допустимым mobile surface, либо
  сроковой/отзываемой scoped-ссылкой с адресами, грузом, фотографиями и
  безопасным подтверждением операций.
- Причина: реализованы административный профиль и внутренний assignment, но не
  граница внешнего исполнителя.
- Последствия: UI показывает, что доставка передана наёмнику, хотя в системе у
  него нет поддержанного способа увидеть и выполнить её; диспетчер вынужден
  передавать данные вручную, подтверждения и аудит теряются.
- Исправлено: выбран вариант сроковой/отзывной scoped-ссылки без создания
  фиктивных credentials. Logistics-service хранит capability и точные task
  bindings, task-board остаётся владельцем ordered execution/evidence state,
  media-service — владельцем bytes, а stateless gateway ограничивает
  anonymous transport. Standalone dispatcher выдаёт ссылку только для
  полностью подтверждённой реальной handoff-команды; public Panel выполняет
  точный маршрут и не видит остальную доску или данные другого подрядчика.
- Доказательства:
  - `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/InternalLogisticsDriverAssignmentController.java:39-49`;
  - `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/ContractorDriverService.java:24-61`;
  - `logistics/backend/app/services/contractor_assignment.py:553-721`;
  - `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/WarehouseAccessAuthorizer.java:46-60`;
  - `contracts/openapi/logistics-service.yaml` — authenticated create/revoke и
    exact anonymous read/action/evidence/media capability;
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/contractor/share/ContractorRouteShareService.java`;
  - `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/ContractorTaskExecutionService.java`;
  - `services/media-service/internal/api/contractor_task_execution.go`;
  - `panel/src/features/logistics/contractor-route-share/public-contractor-route-page.tsx`;
  - `logistics/frontend/src/features/contractors/ContractorDriversPanel.tsx`.

### AUD-047 — старые poison-bookings блокируют background recovery всех новых

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1` при рабочем объёме, `P2` на малом dev-наборе.
- Класс: starvation, recovery/observability, `SYSTEMIC PROBLEM`.
- Исправление: Flyway V75 добавляет persisted due time, короткие lease и
  quarantine; presentation и receipt-bearing checkout используют bounded
  `FOR UPDATE SKIP LOCKED` claims (50/100), exponential backoff 2 секунды —
  5 минут и terminal quarantine после восьмой ошибки. Claim transaction
  завершается до remote effect, terminal write fenced точным живым lease,
  решения о времени используют PostgreSQL clock. Шесть fixed-name gauges
  показывают backlog, oldest age и quarantine без high-cardinality labels.
- Проверка: независимый общий logistics gate прошёл `146/146`, включая clean
  Flyway V1—V75, JPA validation, конкурентные claims, stale lease, backoff,
  quarantine, продвижение 51-й записи и metrics integration.
- Где:
  - основной presentation booking retry всегда берёт первые 50 `PENDING`, а
    transient/unknown failure лишь увеличивает `attemptCount`, не меняя state и
    не задавая retry time:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/PresentationBookingService.java:72-80,215-229`,
    `.../PresentationBookingStore.java:227-232,282-290` и
    `.../domain/PresentationBooking.java:151-156`;
  - `CustomerRentalSessionStore.pendingBookings` всегда возвращает только
    первые 100 `CHECKOUT_PENDING`, отсортированные от самых старых:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerRentalSessionStore.java:206-211`;
  - `CustomerCheckoutService.reconcilePending` обходит этот batch каждые две
    секунды, но любую `RuntimeException` молча игнорирует, не меняя claim,
    retry-at, attempts или terminal classification:
    `.../CustomerCheckoutService.java:170-187`.
- Как воспроизвести:
  1. Создать 51 `PresentationBooking.PENDING` либо 101 cart в
     `CHECKOUT_PENDING` с booking receipt.
  2. Сделать reconciliation первой страницы постоянно transient/unknown,
     оставляя state pending.
  3. Запустить scheduled reconciler многократно.
  4. Каждый проход снова выберет ту же первую страницу; следующая запись не
     попадёт в batch.
- До исправления: не было lease/claim, экспоненциального backoff, `nextAttemptAt`,
  quarantine/DLT или cursor pagination; исключение не логировалось и не
  учитывалось метрикой. Вызов списка bookings самим клиентом мог отдельно
  попытаться восстановить его cart, но автономный recovery гарантии не даёт.
- Должно быть: bounded claim с `SKIP LOCKED`/lease, persisted retry schedule и
  terminal quarantine, чтобы один poison record не блокировал очередь; должны
  быть метрики возраста/числа pending и безопасный diagnostic reason.
- Причина: recovery реализован как повтор первой страницы проекции, а не как
  очередь с прогрессом и классификацией отказов.
- Последствия: создание/сохранение заказа, conversion holds, подтверждение
  slot, furniture tasks и локальный customer state могут навсегда остаться
  незавершёнными; новые клиенты зависают за старыми poison records, при этом
  оператор не видит причины.

### AUD-048 — генератор нагрузки не может проверить end-to-end исполнение в DriverApp

- Статус: `ИСПРАВЛЕНО И ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P2` как product/test gap; production data-isolation здесь сделана
  правильно.
- Класс: simulation boundary, testability.
- Где:
  - generator помечает заявки `WAREHOUSE_WORKLOAD_GENERATOR`:
    `logistics/backend/app/services/workload_generator.py:34-35,250-263`;
  - capacity projection специально берёт эти заявки и передаёт их в
    customer-slot capacity:
    `logistics/backend/app/services/capacity_projection.py:145-205`;
  - RWMS apply, который создаёт canonical driver assignments, принимает только
    `source_system == RWMS` и пропускает все generated/manual задачи:
    `logistics/backend/app/integrations/rwms_sync.py:214-247,661-674`;
  - отдельного dev-only canonical task bridge/fixture для DriverApp не найдено.
- Как воспроизвести:
  1. Сгенерировать тестовую нагрузку planner и построить/подтвердить её план.
  2. Убедиться, что она влияет на расчёт клиентской capacity.
  3. Войти соответствующим водителем в DriverApp.
  4. Сгенерированные stop/task не появятся, потому что не публикуются в
     task-board.
- Сейчас: генератор тестирует карту, planner и клиентские слоты, но не
  получение задания, offline queue, фото и driver state machine.
- Должно быть: не production-подмешивание, а явно изолированный тестовый
  environment/tenant или контрактный fixture bridge, который создаёт
  безопасные canonical test tasks и позволяет пройти весь DriverApp flow.
- Последствия: зелёная planner-симуляция не доказывает работоспособность
  исполнения водителем; критические интеграционные ошибки проявляются только
  на реальных заявках или ручных сложных fixtures.

### AUD-049 — abuse throttling анонимной регистрации зависит от внешней конфигурации, которой нет в репозитории

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: auth-service теперь владеет durable per-source/global budget,
  gateway канонизирует источник запроса, а `429` возвращает безопасный русский
  Problem Details и `Retry-After`; внешний ingress остаётся defense in depth.

- Severity: `P1` как production readiness gate; фактическая публичная
  уязвимость конкретного production ingress **не подтверждена — требуется
  дополнительная проверка внешней конфигурации**.
- Класс: Security, abuse/DoS, operational dependency.
- Где:
  - `POST /api/customer/v1/registrations` намеренно доступен без
    аутентификации после CSRF bootstrap:
    `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java:477-500`;
  - контроллер не имеет request/IP/account throttling:
    `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/api/CustomerRegistrationController.java:22-38`;
  - case-insensitive advisory lock защищает только конкурентное создание одного
    логина, но не множество разных логинов:
    `services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/eventing/AuthInvariantGuard.java:38-54`;
  - архитектурная документация прямо требует production-ingress throttling и
    сообщает, что gateway его не хранит:
    `docs/project-knowledge/architecture.md:41-44`,
    `docs/project-knowledge/contracts.md:31-37`,
    `services/auth-service/README.md:154-158`;
  - repository-wide поиск не нашёл `limit_req`, Bucket4j, RateLimiter или
    другого активного ограничения для этого маршрута; внешняя конфигурация
    ingress в репозиторий не входит.
- Как проверить/воспроизвести безопасно:
  1. На отдельном test ingress выяснить, есть ли лимит на `/auth/api/customer/v1/registrations`.
  2. Отправить контролируемую серию валидных запросов с разными логинами и
     корректной CSRF pair.
  3. Проверить появление 429, bounded resource use и отсутствие записей после
     исчерпания лимита.
- Сейчас по коду: каждый валидный уникальный запрос запускает password hashing,
  транзакцию регистрации, event-store/outbox и запись пользователя.
- Должно быть: доказанный ingress rate limit по источнику и дополнительный
  глобальный budget/circuit breaker, метрики 429 и alert; CSRF остаётся, но не
  считается anti-abuse механизмом.
- Последствия при отсутствии внешнего лимита: дешёвая массовая генерация
  customer accounts, CPU pressure на password encoder, рост БД/outbox и отказ
  auth-service для штатных пользователей.

### AUD-050 — CustomerApp показывает цену без пользовательского разделителя тысяч

- Severity: `P3`.
- Класс: UX, money formatting.
- Где:
  - `client-app/app/src/main/java/dev/buhanzaz/rwms/client/ui/DeliveryFlowScreens.kt:481-491`
    формирует строку прямой интерполяцией `"Стоимость доставки: $price ₽"`;
  - тот же файл `:724-726` формирует held-slot value как `"$it ₽"`;
  - тест `client-app/app/src/test/java/dev/buhanzaz/rwms/client/ui/DeliveryFlowScreensTest.kt:63`
    закрепляет строку `12500 ₽`, то есть отсутствие locale formatting;
  - для сравнения standalone logistics использует
    `Intl.NumberFormat('ru-RU')` в
    `logistics/frontend/src/utils/format.ts:38-41`.
- Как воспроизвести:
  1. Получить slot с `deliveryPriceRubles=28500`.
  2. Открыть выбор/подтверждение доставки в CustomerApp.
  3. Увидеть `28500 ₽` вместо `28 500 ₽`.
- Сейчас: значение корректное и server-owned, но формат хуже читается.
- Должно быть: единый locale-aware money formatter и `Не рассчитана` для null;
  не использовать floating point для суммы.
- Последствия: небольшое ухудшение читаемости и несогласованность UI; расчёт и
  сохранение цены не повреждаются.

### AUD-052 — основной склад видит спрос своей группы, но не рассматривает ресурсы других опорных складов

- Severity: `P1` для сети с несколькими опорными складами.
- Класс: `SYSTEMIC PROBLEM`, planning-group/resource asymmetry.
- Где:
  - `logistics/backend/app/services/planner_runtime.py:735-789` для обычного
    выбранного склада строит planning group и включает заявки всех напрямую
    обслуживаемых представительских складов;
  - тот же сервис `:890-902` вызывает `_with_support_resources`, но немедленно
    возвращает исходный snapshot, если корневой `snapshot.warehouse` сам не
    представительский;
  - следовательно, demand Великого Новгорода попадает в план корневого СПб, но
    допустимый альтернативный ресурс второго опорного склада для той же
    представительской заявки в набор кандидатов не попадает.
- Как воспроизвести:
  1. Связать один представительский склад с двумя основными складами.
  2. Выбрать первый основной склад и построить общий план его группы.
  3. Оставить водителей первого склада занятыми, а второго — свободными.
  4. Региональная заявка останется нераспределённой вместо сравнения двух
     разрешённых support-вариантов.
- Сейчас: направление агрегации спроса зависит от выбранного корня, а поиск
  внешних ресурсов — от признака representative только у самого корня.
- Должно быть: для каждой региональной заявки планировщик рассматривает все
  активные входящие support links, разрешённые на дату, независимо от того,
  открыт план через основной или представительский склад; один и тот же
  кандидат не должен дублироваться.
- Root cause: group demand и support-resource enrichment реализованы двумя
  независимыми условиями с разной точкой отсчёта.
- Последствия: результат зависит от выбранного склада в UI, возможен ложный
  `CONTRACTOR_REQUIRED` при наличии выполнимого штатного ресурса.

### AUD-053 — непостоянная симуляция задержки рассчитывается и затем возвращает исходный план

- Severity: `P2`, для решений диспетчера по отклонениям — `P1`.
- Класс: real functional bug, simulation/API response.
- Где:
  - `logistics/backend/app/services/planner_runtime.py:616-705` при
    `persist=false` строит `shifted`, выполняет полную `validate_route_plan`, но
    не переносит рассчитанные циклы, ошибки, предупреждения или metrics в
    response и возвращает исходный ORM `plan`;
  - `logistics/backend/app/api/plans.py:257-306` после этого повторно читает тот
    же сохранённый план;
  - `logistics/frontend/src/api/client.ts:876-894` трактует ответ как
    `updated_schedule`, а `App.tsx:1174-1176` показывает его как результат
    симуляции.
- Как воспроизвести:
  1. Открыть план с рейсом после выбранного времени.
  2. Добавить непостоянную задержку с `persist=false`.
  3. Backend рассчитает сдвинутые времена, но ответ сохранит прежние времена и
     прежний validation verdict.
- Сейчас: UI подтверждает применение override, локальный playback знает о
  задержке, но server-validated updated schedule остаётся исходным.
- Должно быть: вернуть отдельную неперсистентную проекцию с пересчитанными
  циклами, остановками, сегментами, metrics и validation; DB plan/version/audit
  не должны изменяться.
- Root cause: чистый simulation result не имеет собственного transport mapper,
  поэтому код повторно использует сохранённый `RoutePlan` и выбрасывает расчёт.
- Последствия: логист принимает решение по противоречивому preview; риск не
  увидеть выход за смену или временное окно до реального изменения плана.

### AUD-054 — локальные ресурсы представительского склада скрыты и не участвуют в плане группы

- Severity: `P1`.
- Класс: `SYSTEMIC PROBLEM`, representative resource ownership/UI projection.
- Где:
  - `logistics/backend/app/api/catalog.py:497-529` сохраняет выбранный
    представительский склад в `workspace.warehouse`, но `drivers`, `vehicles`,
    `trailers` и `shifts` всегда читает у `planning_group.root`;
  - `logistics/frontend/src/app/Inspector.tsx:131-173` показывает эти массивы
    как ресурсы «выбранного склада» и их счётчики;
  - команды создания в
    `logistics/frontend/src/app/App.tsx:1089-1117` при этом пишут новый ресурс в
    `workspace.warehouse.id`, то есть в реально выбранный представительский
    склад;
  - после refresh backend снова возвращает каталог root, поэтому только что
    созданный локальный ресурс представительского склада исчезает из списка;
  - `logistics/backend/app/services/planner_runtime.py:770-832` агрегирует
    заявки всех участников группы, но строит `vehicles` и `shifts` только из
    корневого `workspace`; `_with_support_resources` в `:899-907` не вызывается
    для root-плана;
  - frontend всегда запускает auto-plan по
    `planning_root_warehouse_id`, а не по выбранному representative warehouse:
    `logistics/frontend/src/app/App.tsx:264,348-363`.
- Как воспроизвести:
  1. Создать основной склад A и представительский склад B, связать A → B.
  2. В B создать собственного водителя, машину и смену.
  3. Открыть логистику B и обновить workspace.
  4. UI покажет ресурсы A вместо локальных ресурсов B; новый водитель B не
     будет кандидатом автоплана общей группы.
- Сейчас: demand сети объединён, но resource projection и planning snapshot
  принадлежат только одному выбранному root. Это противоречит самой модели, где
  представительский склад может иметь собственных водителей и автомобили.
- Должно быть: workspace явно разделяет local/root/support ресурсы с их
  warehouse of origin; planner для каждой regional task сначала рассматривает
  local resources обслуживаемого склада, затем временно назначенные, все
  допустимые support links и подрядчика. Создание и чтение каталога должны
  использовать одну и ту же warehouse identity.
- Root cause: `PlanningWarehouseGroup` используется одновременно как группа
  спроса и как единый владелец каталогов, хотя это разные понятия.
- Последствия: доступный локальный водитель игнорируется, система ошибочно
  предлагает внешний ресурс/наёмника, а логист видит неверную принадлежность
  водителей и машин.

### AUD-055 — заказ может остаться локально активным после уже выполненного освобождения имущества

- Статус исправления: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В ТЕКУЩЕМ WIP`.
  Исходная цепочка ниже сохраняется как историческое доказательство; release
  steps теперь принадлежат durable recovery command V77 и не зависят от
  повторного действия браузера. Для quarantined-команд пока нет отдельного
  operator requeue/resolve API — это явно зарегистрированный эксплуатационный
  вопрос, а не скрытый успешный исход.
- Severity: `P1`.
- Класс: `SYSTEMIC PROBLEM`, cross-service transaction/recovery.
- Где:
  - `RentalOrderService` открывает локальную `@Transactional` для cancel,
    remove-unit и equipment commands:
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java:148-209`;
  - внутри неё
    `RentalOrderReservationService.removeUnit` сначала вызывает удалённый
    `releaseOrderUnit`, затем отдельный
    `replaceOrderEquipmentReservations`, и только после этого фиксирует
    logistics DB (`:83-161`);
  - `cancel` аналогично вызывает `releaseAllOrderUnits` (`:755-804`), затем
    вторую удалённую furniture-команду (`:805-823`) и лишь после этого меняет
    статус заказа и audit (`:824-870`);
  - JavaDoc класса прямо фиксирует, что remote effects остаются в транзакции
    вызывающего кода и собственной retry policy нет (`:55-59`);
  - integration test
    `OrderApiIntegrationTest.cancelRetryUsesReleaseAllReplayAfterUnknownOutcome`
    (`:2127-2169`) подтверждает промежуточное состояние: после первого `503`
    asset-side ownership уже отсутствует, а локальный order остаётся `DRAFT`.
- Как воспроизвести:
  1. Создать DRAFT-заказ с зарезервированной бытовкой.
  2. Вызвать cancel и заставить dependency вернуть transient failure уже после
     фактического `releaseAllOrderUnits` либо перед второй furniture-командой.
  3. Не повторять запрос тем же `Idempotency-Key` (закрыть вкладку, потерять
     in-memory command identity или дождаться другого оператора).
  4. Asset-service считает бытовку свободной, а logistics-service сохраняет
     незавершённый заказ без финального audit/receipt.
- Сейчас: повтор с тем же ключом умеет дочитать replay и завершить локальную
  транзакцию, но восстановление зависит от клиента; durable pending command,
  outbox/saga recovery и quarantine для исчерпанных попыток отсутствуют.
- Должно быть: logistics-service сохраняет durable command/saga intent до
  первого внешнего эффекта, повторяет идемпотентные шаги фоново и завершает
  локальную state transition только после доказанных receipts; UI получает
  состояние «идёт сверка», а не обязан быть механизмом восстановления.
- Последствия: одна бытовка может снова стать доступной в asset-service при
  формально неотменённом заказе; при повторе с новым ключом возможна неполная
  история release, а ручное расследование потребует сравнения двух сервисов.

### AUD-056 — версия заказа проверяется дважды в одной команде комплектации

- Severity: `P3`.
- Класс: code quality, regression signal.
- Где:
  `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderReservationService.java:190-191`.
- Сейчас: `RentalOrderProblems.requireVersion(order, request.expectedVersion())`
  вызывается два раза подряд без изменения состояния между вызовами.
- Должно быть: один version fence на заблокированном aggregate.
- Последствия: бизнес-результат сейчас не искажается, но дублирование — признак
  ручного слияния и увеличивает риск, что будущая побочная логика проверки
  выполнится дважды.

### AUD-057 — planner не может представить ночную или переходящую месяц смену

- Severity: `P2`; для склада с ночной эксплуатацией — `P1`.
- Класс: time/domain-model limitation.
- Где:
  - `logistics/backend/app/schemas/domain.py:521-542` требует
    `end_time > start_time`, ограничивает период 30 днями и запрещает переход
    `date_from/date_to` между месяцами;
  - те же ограничения продублированы application layer в
    `logistics/backend/app/services/catalog.py:765-775` и DB constraints в
    `logistics/backend/app/models/domain.py:559-574`;
  - `logistics/backend/app/services/planner_runtime.py:816-831` объединяет обе
    границы смены с одной `planning_date`, поэтому семантики «окончание на
    следующий день» нет.
- Как воспроизвести:
  1. Создать смену 22:00–06:00 либо диапазон 25 августа–5 сентября.
  2. Получить validation error до планирования.
- Сейчас: поддерживаются только дневные интервалы внутри одного месяца.
- Должно быть: либо явно объявить продуктовый запрет и показывать его в UI,
  либо хранить/вычислять start/end как warehouse-zoned instants с явным
  next-day marker; границы DST должны отклонять несуществующий local time и
  однозначно выбирать offset для повторяющегося часа.
- Последствия: нельзя планировать ночную погрузку, дальний межскладской рейс со
  сменой через полночь или непрерывный период работ, пересекающий месяц;
  попытки обхода несколькими сменами искажают break/shift-limit validation.

### AUD-058 — координаты `0,0` считаются пригодными для склада и клиентской маршрутизации

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: aggregate Warehouse запрещает новый placeholder, Panel сообщает
  об ошибке до команды, а CustomerApp и оба planner boundary fail-closed
  обрабатывают уже существующие `0,0` без автоматической подмены координат.

- Severity: `P2`; при production-конфигурации с placeholder-координатами —
  `P1`.
- Класс: real validation/geography bug.
- Где:
  - `services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/domain/Warehouse.java:434-452`
    проверяет только наличие пары и диапазоны WGS84, поэтому `0,0` сохраняется
    как обычная координата;
  - `panel/src/features/settings/warehouses/warehouse-settings-form.ts:50-117`
    и `panel/src/api/warehouse-api.ts:198-216` также принимают эту пару;
  - `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerWarehouseService.java:23-90`
    считает активный representative с `0,0` видимым и routable;
  - `CustomerDeliveryProperties.java:251-266` принимает `0,0` как валидный
    route origin; при этом именно `0` используется как placeholder по умолчанию
    для depot-переменных в `application.yaml:173-180`.
- Как воспроизвести:
  1. Создать или изменить активный представительский склад, указав широту `0`
     и долготу `0`.
  2. Открыть список складов CustomerApp или запросить варианты доставки.
  3. Склад пройдёт фильтр и дорожный расчёт будет выполняться от точки в
     Гвинейском заливе вместо понятного статуса «координаты не заданы».
- Сейчас: синтаксически допустимая WGS84-пара автоматически считается
  операционно пригодной, без distinction между введённой точкой и типичным
  placeholder.
- Должно быть: либо запретить `0,0` в warehouse-owned validation для текущей
  географии продукта, либо хранить отдельный подтверждённый routing-readiness
  факт и не допускать неподтверждённую точку в карту, CustomerApp и planner.
  Если в будущем склад на реальной точке `0,0` станет допустимым бизнес-кейсом,
  нужен явный override, а не молчаливое принятие placeholder.
- Root cause: все слои проверяют только числовые границы WGS84, а семантика
  «координаты пригодны для эксплуатации» отсутствует.
- Последствия: ложный склад в клиентском приложении, бессмысленные ETA/тарифы,
  чрезмерные route matrices и непонятные «нет доступных слотов» вместо
  диагностируемой ошибки конфигурации.

### AUD-059 — водитель основного склада не видит и не может закрыть задание со склада-источника представительства

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: task-board добавляет в DriverApp feed только точное remote
  assignment активного водителя, серверно разрешает физический warehouse для
  операций и учитывает эти задания в закрытии смены без раскрытия общего пула.

- Severity: `P1`.
- Класс: confirmed cross-service/cross-warehouse execution break.
- Где и полная цепочка:
  - standalone planner сохраняет регионального владельца заказа отдельно:
    `logistics/backend/app/integrations/rwms_sync.py:330-400` записывает
    representative в `assignment.serviceWarehouseId`, но создаёт
    `driverShiftPlans` для корневого склада;
  - `_driver_shift_plan_snapshot` в том же файле (`:476-503`) прямо требует,
    чтобы смена водителя принадлежала root plan warehouse, и передаёт именно
    root `warehouseId` в RWMS;
  - `RentalOrderPlanningIntegrationService.requireValidShiftPlans`
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java:456-482`)
    также запрещает shift plan с warehouse, отличным от root command;
  - заказ и shipment при этом остаются у `serviceWarehouseId`
    (`:241-303`), а `DocumentDriverTaskPlanner.taskWarehouse`
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DocumentDriverTaskPlanner.java:661-677`)
    помещает задание отгрузки в физический `inventorySourceWarehouseId`;
  - DriverApp всегда читает feed только в warehouse из JWT
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverTaskBoardController.java:66-101,184-189`), а
    `TaskBoardReadProjectionService.workerSnapshot` (`:201-237`) обходит
    очереди только этого warehouse;
  - закрытие смены отдельно считает `DONE`-задачи только по
    `shift.warehouse_id` и дате
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverShiftService.java:823-852`).
- Как воспроизвести:
  1. Создать root plan Санкт-Петербурга с водителем Санкт-Петербурга и заказом,
     чей `serviceWarehouseId` — представительский склад.
  2. Выбрать вариант «сначала приехать на представительство и взять местную
     бытовку», то есть `inventorySourceWarehouseId` равен представительскому
     складу.
  3. Подтвердить план: shift регистрируется в task-board для root warehouse,
     а shipment task — в очереди representative warehouse.
  4. Открыть DriverApp токеном этого штатного водителя основного склада.
- Сейчас: feed основного склада не содержит региональное задание; если его
  выполнить через административный обход, shift summary всё равно не увидит
  его среди задач смены и не докажет корректное завершение workday.
- Должно быть: разовый `CROSS_WAREHOUSE_SERVICE` должен иметь одну исполнимую
  driver trip/shift identity и явную область обслуживаемых warehouse либо
  assignment-to-shift link. Driver feed и shift completion должны выбирать
  задачи по этой identity, а не по совпадению одного warehouse ID; домашний
  склад водителя при этом не меняется.
- Root cause: четыре разных смысла склада частично разделены в planner/order,
  но task-board mobile projection и shift state machine по-прежнему используют
  `warehouse_id` одновременно как security scope, очередь задания и состав
  смены.
- Последствия: вариант с загрузкой локальной бытовки представительства,
  межскладской визит и часть return-logistics физически не исполняются в
  DriverApp; водитель может не получить точный маршрут, а смена зависнет перед
  закрытием либо будет закрыта без учёта региональной работы.

### AUD-060 — смена водителя публикуется до проверки назначений и может навсегда остаться пустой

- Текущий статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Исправление: logistics-service сначала валидирует/применяет assignments и
  только затем регистрирует согласованный shift plan; rejected assignment не
  оставляет готовую пустую смену, повтор остаётся идемпотентным.

- Severity: `P1`.
- Класс: confirmed partial-publish/transaction-ordering bug.
- Где и цепочка:
  - `build_assignments_command` всегда формирует общий список assignment и
    независимый список `driverShiftPlans`
    (`logistics/backend/app/integrations/rwms_sync.py:328-455`);
  - `RentalOrderPlanningIntegrationService.apply`
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java:241-315`)
    вызывает `registerShiftPlans(request)` **до** цикла, в котором проверяется
    каждый заказ и создаётся shipment;
  - ошибки конкретного назначения ловятся и превращаются в
    `RejectedPlanningAssignment`, не откатывая уже зарегистрированный remote
    shift plan (`:304-313`);
  - task-board `DriverShiftService.today` (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverShiftService.java:122-183`)
    при первом открытии DriverApp создаёт shift и замораживает plan;
  - `putPlan` после этого запрещает замену frozen plan (`:84-119`), а
    `startClosing` требует хотя бы одну реально созданную и завершённую задачу
    (`:274-293`, `:823-852`).
- Как воспроизвести:
  1. Сформировать plan с одной сменой и assignment на заказ со stale
     `expectedOrderVersion` либо другим постоянным domain conflict.
  2. Вызвать RWMS apply.
  3. Shift plan успешно зарегистрируется, assignment вернётся в `rejected`.
  4. Открыть DriverApp этого водителя: `today` заморозит пустую смену.
  5. Попытаться закрыть смену или опубликовать исправленный plan той же даты.
- Сейчас: публикация двух связанных частей не имеет общего prepare/finalize
  protocol; пустой или завышенный по `tripCount/routeDistanceMeters` shift
  становится самостоятельным authoritative фактом раньше его заданий.
- Должно быть: сначала атомарно доказать/зарезервировать все assignment либо
  сохранить durable publish saga со статусом `PREPARING`; task-board должен
  видеть смену как доступную водителю только после финального подтверждения
  состава. Частичные per-assignment outcomes должны приводить к пересчитанному
  shift snapshot или явной reconciliation, а не к готовой пустой смене.
- Root cause: cross-service publish разбит на необратимый remote effect и
  последующие независимые локальные команды без compensation/finalization
  barrier.
- Последствия: водитель получает ложный рабочий день, исправленный план может
  быть заблокирован как frozen, смену нельзя корректно завершить, а
  `tripCount/routeDistanceMeters` расходятся с фактическими задачами.

### AUD-061 — оперативно перемещённый водитель становится кандидатом нового склада, но DriverApp остаётся привязан к домашнему

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P1`.
- Класс: confirmed identity/operational-placement contract mismatch.
- Где и цепочка:
  - `WorkerOperationalAssignment` намеренно не меняет home warehouse
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/domain/WorkerOperationalAssignment.java:20-23,107-137`);
  - после `ACTIVE` или завершённого `PERMANENT` назначения
    `WorkerOperationalAvailabilityPolicy.resolve` возвращает destination как
    operational warehouse
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerOperationalAvailabilityPolicy.java:24-85`);
  - `LogisticsDriverDirectoryService` поэтому корректно выдаёт водителя
    планировщику как доступного на destination
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/LogisticsDriverDirectoryService.java:40-112`);
  - но публичные DriverApp endpoints получают warehouse исключительно из
    неизменяемого JWT claim и требуют ровно его
    (`DriverShiftController.java:158-170`,
    `DriverTaskBoardController.java:184-199`,
    `WarehouseAccessAuthorizer.java:62-75`);
  - `DriverShiftService.today` дополнительно требует, чтобы этот claim совпал
    с `worker.getWarehouseId()` — домашним warehouse
    (`DriverShiftService.java:152-166`);
  - auth-service выпускает `warehouse_id` непосредственно из
    `AuthSubject.warehouseId`
    (`services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java:748`);
    связи с operational assignment в этой цепочке нет.
- Как воспроизвести:
  1. Завершить временное или постоянное `RESOURCE_REPOSITION` водителя из
     склада A в склад B и перевести assignment в `ACTIVE`/`COMPLETED`.
  2. Запросить directory склада B — водитель присутствует и может быть выбран
     planner.
  3. Опубликовать ему shift/tasks склада B.
  4. Войти тем же водителем в DriverApp: токен остаётся warehouse A, а feed и
     `/shift/today` читают только A.
- Сейчас: planning availability и execution authorization используют разные
  источники warehouse placement; система обещает ресурс в B, который не может
  получить там смену и задания.
- Должно быть: home warehouse остаётся неизменным, но auth/task-board должны
  иметь server-authoritative operational execution scope (например, конкретное
  assignment/shift audience с destination warehouse и сроком действия).
  Нельзя просто перезаписывать home claim: temporary assignment и возврат
  требуют истории и ограниченной области.
- Root cause: operational assignment добавлен только в directory read model,
  но не включён в mobile principal/authorization contract и DriverShift owner
  lookup.
- Последствия: временное и постоянное перемещение формально завершается и
  влияет на планировщик/слоты, однако водитель не способен реально работать на
  новом складе; возможны ложная capacity и нераспределённые фактические рейсы.

### AUD-062 — перемещение автомобиля подтверждается без изменения его оперативного склада

- Severity: `P1`.
- Класс: confirmed no-op domain intent / resource consistency.
- Где и цепочка:
  - форма и API transfer plan раздельно принимают автомобиль рейса и автомобиль,
    который должен остаться на destination: `tripVehicleId` и
    `vehicleReposition`
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/api/LogisticsApiModels.java:263-265`);
  - `TransferPlan` сохраняет оба значения, а наличие только автомобиля или
    vehicle intent уже считается достаточным ресурсным содержимым для
    подтверждения рейса без груза
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/domain/TransferPlan.java:450-493`);
  - при создании workflow attempts `TransferPlanWorkflowStore` создаёт
    назначения только для `tripDriverId` и `driverReposition`: predicates
    `requiresTripOnlyAssignment` и `requiresRepositionAssignment` вообще не
    читают vehicle intent
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/TransferPlanWorkflowStore.java:80-119,1038-1046`);
  - `AssignmentCreateWork` содержит только `workerId`, а processor вызывает
    исключительно `createWorkerOperationalAssignment`; vehicle counterpart в
    типах work/effects отсутствует
    (`TransferPlanWorkflowStore.java:704-736,1299-1354`,
    `TransferPlanProcessor.java:36-109`);
  - глобальный поиск по active Spring services не находит
    `VehicleOperationalAssignment` или другого владельца временного/постоянного
    базирования автомобиля;
  - интеграционный тест передаёт `repositionedVehicle` в режиме `PERMANENT`, но
    mock/verification реализованы только для worker assignment, после чего
    transfer становится `RESERVED`
    (`services/logistics-service/src/test/java/dev/buhanzaz/rwms/logistics/TransferWorkflowSagaIntegrationTest.java:140-159,326-374`).
- Как воспроизвести:
  1. Создать transfer A → B и указать автомобиль рейса.
  2. Включить для автомобиля постоянное либо временное перемещение на B.
  3. Подтвердить transfer и дождаться окончания reservation workflow/прибытия.
  4. Повторно запросить доступные автомобили складов A и B.
- Сейчас: plan/API показывают намерение переместить автомобиль, а workflow
  успешно проходит без единого effect для автомобиля. Его каталог и
  доступность остаются на A; история оперативного базирования отсутствует.
- Должно быть: автомобиль, выполняющий рейс, и автомобиль, который меняет
  operational warehouse, должны иметь отдельные server-authoritative роли.
  Reposition требует durable assignment/history, интервала действия,
  overlap-reservation, перехода после фактического прибытия и синхронизации с
  planner directory. Пока такого owner/contract нет, подтверждать vehicle
  reposition как выполненную возможность нельзя.
- Root cause: API/domain intent добавлен раньше owning aggregate и executable
  saga; readiness проверяет сохранённые поля, а не наличие обязательного
  vehicle effect.
- Последствия: диспетчер видит ложное завершённое перемещение; planner может
  продолжать назначать автомобиль на A либо не видеть его на B; возможны
  двойное бронирование, невозможный маршрут и недостоверный аудит ресурсов.

### AUD-063 — panel требует даты наёмника, которых больше нет в контракте, и отвергает успешный ответ создания

- Severity: `P1`.
- Класс: confirmed frontend/backend contract mismatch, false failure after
  possible committed create.
- Где и полная цепочка:
  - canonical task-board contract определяет contractor как бессрочный профиль:
    create содержит только `contractorId`, `displayName`, `phone`, `comment`, а
    response — identity/version/home/name/phone/comment/active/type. Полей
    `availableFrom` и `availableUntil` нет, `additionalProperties: false`
    (`contracts/openapi/task-board-service.yaml:4330-4370`);
  - owning `ContractorDriverService` прямо документирует, что дата принадлежит
    downstream assignment, и профиль не хранит interval/vehicle/shift/cycle
    (`services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/ContractorDriverService.java:25-45`);
  - panel transport всё ещё объявляет обе даты обязательными в create и
    response, отправляет их серверу, а parser требует точного набора ключей и
    вызывает `timestamp(undefined)` при каноническом ответе
    (`panel/src/features/logistics/warehouse-transfers/api/logistics-driver-resources-api.ts:20-76,166-205`);
  - transfer dialog всё ещё требует начало/окончание, блокирует submit без них и
    сообщает, что создаёт период доступности
    (`panel/src/features/logistics/warehouse-transfers/transfer-plan-dialog.tsx:1587-1648,1675-1702`);
  - panel tests подменяют реальный contract фикстурой со старыми
    `availableFrom/availableUntil`, поэтому не ловят расхождение
    (`logistics-driver-resources-api.test.ts:70-110`,
    `transfer-plan-dialog.test.tsx:525-580`).
- Как воспроизвести:
  1. В форме межскладского transfer нажать «Добавить наёмного водителя».
  2. Заполнить навязанные даты, имя и телефон и отправить форму.
  3. Task-board обработает профиль без дат (либо строгий boundary отклонит
     лишние поля), а канонический успешный response не будет содержать дат.
  4. Panel parser выбросит «Сервис задач вернул некорректный ресурс водителя» и
     покажет ошибку вместо созданного профиля.
- Сейчас: пользователь не может надёжно создать наёмника из transfer flow.
  При обычной Jackson-конфигурации лишние request fields игнорируются, запись
  уже создана, но UI считает операцию неуспешной; закрытие/повтор формы с новым
  client UUID способно накопить дублирующиеся профили одного человека.
- Должно быть: profile create/edit не содержит дат; выбранная в header дата и
  конкретные заявки фиксируются отдельной командой AUTO/MANUAL dispatch.
  Panel типы и parser должны генерироваться/проверяться по canonical
  `ContractorDriver`, а contract test обязан использовать реальный response без
  obsolete полей.
- Root cause: task-board domain/contract был правильно изменён на address-book
  profile, но активный panel consumer и его fixtures остались на прежней
  date-bounded модели.
- Последствия: воспроизводимая ошибка из пользовательского сценария, ложное
  сообщение после возможной записи, скрытые/дублирующиеся наёмники и
  невозможность перейти к автоматическому или ручному назначению рейса.

### AUD-064 — настройки сохраняются локально до публикации capacity, а несовместимая смена вызывает внешний HTTP 400

- Severity: `P1`.
- Класс: confirmed cross-service contract mismatch, partial commit и ложный
  результат команды.
- Где и полная цепочка:
  - standalone-модель смены проверяет только `end_time > start_time` и
    неотрицательный `break_minutes`; ни create/update schema, ни service, ни DB
    constraint не требуют, чтобы перерыв был короче смены
    (`logistics/backend/app/schemas/domain.py:516-559`,
    `logistics/backend/app/services/catalog.py:735-793`,
    `logistics/backend/app/models/domain.py:559-591`);
  - frontend повторяет ту же неполную проверку, поэтому, например, смена
    `08:00–08:30` с перерывом `30` минут считается валидной
    (`logistics/frontend/src/components/EntityDialogs.tsx:371-407`);
  - standalone capacity DTO и canonical OpenAPI также ограничивают перерыв
    только диапазоном `0..720`, без правила `break < duration`
    (`logistics/backend/app/schemas/domain.py:1126-1141`,
    `contracts/openapi/logistics-service.yaml:3754-3768`);
  - фактический owning endpoint Spring дополнительно отклоняет
    `breakMinutes >= shift duration` через `IllegalArgumentException`, которое
    глобальный handler превращает в HTTP 400 `LOGISTICS_INVALID_REQUEST`
    (`services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/api/PlanningIntegrationApiModels.java:130-146`,
    `services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/api/LogisticsProblemHandler.java:133-146`);
  - любая смена warehouse settings или изохрон вызывает общий
    `updateWarehouse`, затем публикацию полного capacity snapshot
    (`logistics/frontend/src/features/settings/SettingsEditor.tsx:117-188`,
    `logistics/frontend/src/app/App.tsx:984`,
    `logistics/backend/app/api/catalog.py:534-550`);
  - `publish_capacity_after_mutation` сначала увеличивает generation и делает
    `session.commit()`, а только затем вызывает удалённый replace. Поэтому
    warehouse settings/тарифы уже durable, когда Spring возвращает 400
    (`logistics/backend/app/services/capacity_mutations.py:15-30`,
    `logistics/backend/app/services/capacity_projection.py:318-335`);
  - специальная frontend-reconciliation после такого partial success есть у
    генератора нагрузки, но отсутствует в generic settings save
    (`logistics/frontend/src/app/App.tsx:797-820,984`).
- Как воспроизвести:
  1. Создать активную смену `08:00–08:30` с перерывом `30` минут. Standalone UI,
     Pydantic и DB принимают её.
  2. Изменить цену изохроны либо любой параметр настроек склада и нажать
     «Сохранить».
  3. Standalone закоммитит настройку и новую capacity generation.
  4. Полный snapshot включит указанную смену; logistics-service вернёт HTTP 400.
  5. UI сообщит, что операция не удалась, хотя после refresh локальное значение
     окажется уже изменённым, а клиентская capacity-проекция останется прежней.
- Сейчас: один и тот же shift payload валиден у producer и невалиден у
  consumer; результат одной пользовательской команды разделяется между двумя
  системами. Повтор «Сохранить» снова увеличивает generation и повторяет
  неуспешную публикацию.
- Должно быть: invariant смены должен быть одинаков в canonical contract,
  producer validation и consumer domain. Недопустимый break отклоняется до
  локального commit. Межсервисная публикация после commit должна иметь явное
  durable состояние/outbox/retry; UI должен различать «локально сохранено,
  публикация ожидает повтора» и «ничего не сохранено».
- Root cause: скрытая более строгая проверка находится только в constructor
  Spring transport record и отсутствует в canonical schema/producer; синхронный
  внешний effect после локального commit всё ещё представлен вызывающему как
  единый атомарный save.
- Последствия: рассинхронизация цены/слотов между диспетчерской логистикой и
  CustomerApp, повторные generation без успешной projection, недостоверная
  обратная связь и невозможность понять, применилось ли изменение. Текущий WIP
  уже санитизирует upstream response до безопасного 502-кода; показанная ранее
  пользователем буквальная строка `RMS Logistics Service returned HTTP 400`
  относится к прежнему/опубликованному error mapper, но underlying HTTP 400 и
  partial-commit дефект подтверждаются текущим кодом.
- Runtime evidence: доступный контейнер
  `logistics-simulator-backend-1` работает около пяти часов, но его stdout за
  доступный период содержит только health-check GET и не содержит
  пользовательского settings request. Поэтому именно фактический payload
  прежней ошибки по логам **не подтверждён**; карточка фиксирует независимо
  воспроизводимый code path того же HTTP 400, а не выдумывает отсутствующий log.
  Read-only SQL текущей simulator-БД также не нашёл ни одной активной смены с
  `break_minutes >= duration`; следовательно, этот конкретный trigger сейчас в
  данных отсутствует и не объявляется причиной прежнего пользовательского
  инцидента без сохранённого request/log. Текущие `capacity_generation` всех
  трёх simulator warehouses совпадают с `source_generation` соответствующих
  `customer_warehouse_capacity_snapshot` в canonical logistics DB
  (`СПБ=919`, `В. Новгород=918`, `МСК=82`), то есть на момент аудита
  незавершённой публикации в доступном runtime нет.

### AUD-065 — UI-тест подрядчиков имеет race с асинхронной загрузкой каталога

- Severity: `P3`; test reliability, не подтверждённый production-дефект.
- Класс: `QUICK WIN`, asynchronous test synchronization.
- Где:
  `logistics/frontend/tests/contractor-assignment.test.tsx`, сценарий
  автоматического формирования рейса.
- Как воспроизвести:
  1. Запустить focused contractor suite в среде, где ответ каталога разрешается
     позже первого render tick.
  2. Тест выполняет синхронный `getAllByRole` до появления карточек.
  3. Получить ложный failure «элемент не найден», хотя UI после завершения
     загрузки корректен.
- Сейчас после исправления: тест ожидает кнопку через async query и затем
  проверяет прежний точный command payload; product assertions не ослаблены.
- Должно быть: async UI-тест обязан синхронизироваться по видимому результату
  загрузки, а не по скорости event loop.
- Root cause: синхронный selector применялся к данным, которые компонент всегда
  получает асинхронно.
- Последствия до исправления: нестабильный CI и риск принять ложный красный gate
  за product regression либо, наоборот, привыкнуть игнорировать failures.

### AUD-066 — Panel error regressions не покрывают abort и утечку malformed body напрямую

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P3`; test coverage/security regression proof, не подтверждённый
  production-дефект.
- Класс: `QUICK WIN`, negative error-boundary coverage.
- Где:
  `panel/src/features/assistant/api/assistant-api.test.ts` и
  `panel/src/features/rental-items/cabin-photo-presentations-api.test.ts`.
- Как воспроизвести: прочитать текущие focused suites либо выполнить
  независимый Luna-gate: сетевой failure и malformed payload покрыты, но ни
  один тест не создаёт `AbortError` и не требует `REQUEST_ABORTED`; тест
  malformed JSON публичной галереи не сравнивает пользовательский `message` с
  безопасным текстом и не запрещает в нём сырой body.
- Сейчас: production mapper имеет соответствующие ветви, однако регрессия в
  этих ветвях может пройти focused suite незамеченной.
- Должно быть: два прямых отрицательных теста фиксируют code/message и отдельно
  доказывают, что диагностический body доступен только через
  `diagnosticMessage`.
- Root cause: исходный focused gate проверял общий класс ошибки, а не каждую
  security-значимую границу представления.
- Последствия: будущая правка adapter catch/parse может снова показать
  пользователю технический текст при зелёном focused CI.

### AUD-067 — Panel теряет диагностическое сообщение браузерного AbortError

- Статус: `ИСПРАВЛЕНО И НЕЗАВИСИМО ПРОВЕРЕНО В WIP; ЕЩЁ НЕ ОПУБЛИКОВАНО`.
- Severity: `P3`; observability/error-boundary defect, пользовательский текст
  при этом остаётся безопасным.
- Класс: `QUICK WIN`.
- Где:
  `panel/src/lib/api-client.ts`, `diagnosticMessage()` и
  `apiErrorFromRequestFailure()`; воспроизводится через
  `panel/src/features/assistant/api/assistant-api.test.ts`.
- Как воспроизвести:
  1. Отклонить `fetch` значением
     `new DOMException("assistant request aborted by user", "AbortError")`.
  2. Выполнить `streamAssistantTurn()`.
  3. Проверить созданный `ApiError`.
- Сейчас: `code = REQUEST_ABORTED` и безопасный русский `message` корректны,
  но `diagnosticMessage = Unknown transport failure`.
- Должно быть: диагностическое поле сохраняет исходную причину отмены для
  log/telemetry, не подменяя безопасный пользовательский текст.
- Root cause: проверка `cause instanceof Error` не переносима между всеми
  DOM/browser realms и не охватывает реальный `DOMException` в тестовой среде.
- Последствия: расследование отменённых/оборванных запросов теряет исходный
  контекст; одинаковый fallback скрывает различие пользовательской отмены и
  неизвестного transport failure.

## Тесты и проверки, уже выполненные

- `logistics/backend`: `make test` — 360 collected; 352 passed, 2 failed,
  6 skipped. Skips требуют `VALHALLA_SYNTHETIC_URL`.
- `logistics/backend`: Ruff — passed.
- `logistics/backend`: Mypy — passed, 71 source files.
- `logistics/frontend`: Vitest — 26 files / 155 tests; 24 files и 153 tests
  passed, 2 failed.
- `services/logistics-service`: 724 tests; 722 passed, 2 failed. Один failure
  воспроизводит timezone-sensitive test-only warehouse admission: тест задаёт
  `LocalDate.now()` в Europe/Moscow, а synthetic ticket выводит дату из UTC
  `occurredAt`; около полуночи даты расходятся. Второй failure ожидал проверку
  отсутствующей repair queue, но workflow остановился раньше с состоянием,
  недопустимым для arrival. Тот же метод
  `TransferWorkflowSagaIntegrationTest.blocksArrivalBeforeMutationWhenTheTargetRepairQueueIsMissing`
  при отдельном запуске прошёл: 1 test, 0 failures, `BUILD SUCCESSFUL` за 50
  секунд. Весь `TransferWorkflowSagaIntegrationTest` также прошёл отдельно:
  9 tests, 0 failures, `BUILD SUCCESSFUL` за 58 секунд. Это исключает
  стабильный product failure и указывает на order-dependent test state,
  exhausted shared fixture либо другое загрязнение полной suite.
- Внешние GET smoke-checks без credentials — `/api/health` и
  `/api/warehouses` оба вернули 200, что подтвердило AUD-001.
- `services/task-board-service`: 281 tests; 277 passed, 2 failed, 2 skipped.
  Оба failure вызваны `too many clients already`; test executor также сообщил
  `Java heap space`. Это зафиксировано как `AUD-024`.
- `client-app`: `./gradlew test --no-daemon --max-workers=2
  -Pkotlin.compiler.execution.strategy=in-process` — `BUILD SUCCESSFUL` за
  38 секунд, `:app:testDebugUnitTest` без failures; 37 задач были
  `UP-TO-DATE`. После проверки Gradle/Kotlin daemon не осталось.
- `driver-app`: `./gradlew test --no-daemon --max-workers=2
  -Pkotlin.compiler.execution.strategy=in-process` — build завершился с кодом
  `1` до запуска тестов на `:core-network:compileDebugJavaWithJavac`.
  Android JDK image transform не смог выполнить
  `/opt/java/temurin-26/bin/jlink` для Android 36
  `core-for-system-modules.jar`; Kotlin также сообщил о fallback с JDK target
  26 на JVM 24. Это блокер окружения проверки, а не подтверждённый failure
  теста. После команды task-owned Gradle/Kotlin daemon не осталось.
- `driver-app` с документированным JDK 17:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test --no-daemon
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` —
  `BUILD SUCCESSFUL` за 4 минуты 43 секунды; 216 tests, 0 skipped, 0 failures,
  0 errors. 348 Gradle tasks: 53 executed, 12 from cache, 283 up-to-date.
  После проверки task-owned Gradle/Kotlin daemon не осталось. Это подтверждает,
  что предыдущий JDK 26 failure был environment/toolchain mismatch, а не
  failure тестов DriverApp.
- `panel`: `npm test` — 221 test files / 1 334 tests; 217 files и 1 328 tests
  passed, 4 files / 6 tests failed исключительно по 5-секундному timeout.
  Затронуты `logistics-returns-page.test.tsx`,
  `rental-items-command-access.test.tsx`,
  `repair-estimate-catalog-picker.test.tsx` и три сценария
  `warehouse-transfers/transfer-plan-dialog.test.tsx`. Полный прогон занял
  336,83 секунды и не оставил Vitest/Vite processes. Выполняется отдельный
  focused rerun этих четырёх файлов, чтобы отличить suite resource contention
  от стабильной regression.
- `panel`: `npm run typecheck` — exit 0; `npm run lint` — exit 0.
- Focused panel rerun четырёх timeout-файлов:
  `npx vitest run logistics-returns-page.test.tsx
  rental-items-command-access.test.tsx repair-estimate-catalog-picker.test.tsx
  warehouse-transfers/transfer-plan-dialog.test.tsx` — 4 files / 48 tests,
  все passed за 26,58 секунды. Следовательно, шесть failures полного прогона
  являются suite-timeout/resource-contention, а не стабильными failures этих
  product сценариев.
- `worker-app` с JDK 17:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test --no-daemon
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` —
  `BUILD SUCCESSFUL`; 248 tests, 0 skipped, 0 failures, 0 errors, 322 Gradle
  tasks были up-to-date. Репозиторий не изменён. После проверки обнаруженный
  wrapper/daemon по `pwdx` принадлежал одновременно выполнявшемуся ManagerApp
  build в `/home/developer/projects/rwms/app`, а не WorkerApp, поэтому он не
  останавливался как чужой активный процесс.
- `app` (ManagerApp) с JDK 17:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test --no-daemon
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` —
  `BUILD SUCCESSFUL` за 4 минуты 45 секунд; 332 debug + 332 release unit tests,
  0 failures, 0 errors, 0 skipped. После проверки task-owned Gradle/Kotlin
  daemon не осталось; `git status` не изменился.
- Runtime read-only check для `AUD-064`: stdout текущего simulator backend за
  доступные 5 часов содержит только health checks; request прежнего settings
  failure отсутствует. SQL не нашёл активных смен, где перерыв поглощает всю
  смену. Simulator/canonical capacity generations совпали для всех трёх
  складов (`919/918/82`), поэтому текущая проекция синхронизирована; конкретный
  прежний incident без сохранённого request/log не атрибутирован.

## Проверенные сильные стороны, которые нельзя потерять при исправлениях

- Stateful Spring services владеют отдельными PostgreSQL, canonical contracts
  отделены от JPA, gateway не владеет бизнес-процессом.
- Во многих критических Spring-командах есть `@Version`/`expectedVersion`,
  idempotency receipts, transactional outbox/inbox и version-gap handling.
- DriverApp имеет зрелую offline основу: tenant-scoped Room, зашифрованный
  durable outbox, стабильные operation IDs, atomic optimistic projection,
  повторяемые media upload/finalize и reconciliation после server conflicts.
- Planner использует exact road matrix, детерминированный bounded search,
  поэтапные load transitions, locked/manual cycles, draft/confirmed plans и
  reason codes; изохроны не подменяют финальную дорожную маршрутизацию.
- Customer inquiry-create idempotency key уже переживает recreation через
  `CustomerWorkflowStore`; проблема AUD-004 относится к другим effects.

## Требуют дальнейшего доказательства

- Полная state machine для Order/Delivery/Pickup/Trip/Cycle/Vehicle и наличие
  всех запретов переходов на каждом public endpoint.
- Возможность двойного назначения при одновременной работе нескольких
  диспетчеров и background planner для каждого конкретного command path.
- Capacity по каждому route leg, trailer/weight/dimension ограничения и
  поведение при pickup/delivery комбинациях на production Valhalla profile.
- Warehouse isolation всех public APIs и media reads (не только наличие JWT).
- Pagination/N+1/map-marker/route-matrix bottlenecks на 10 000 заказов в сутки.
- Реальный runtime эффект warning `function definition logisticsInbound is not valid`.
- Contractor pickup/handoff parity между UI, FastAPI и canonical owner.
- Фактический request/response прежнего HTTP 400 при settings save: текущие
  runtime logs его не сохранили, а доступные capacity generations уже совпали.
- Полный error catalog и все оставшиеся места вывода raw exception.
- Семантика draft/published/rollback и защита manual locks от следующего auto run.

---

# Итоговый технический, архитектурный и продуктовый аудит

Срез репозитория: ветка `develop`, commit
`723b64575bdfb11ad1a49a54745993079e36aecc`, дата аудита 2026-08-31.
Все выводы ниже получены read-only трассировкой активного кода, canonical
contracts, Flyway/Alembic, тестов и документации. Исторические каталоги не
использовались как источник текущего поведения. Подробные доказательства,
шаги воспроизведения и root cause находятся выше в `AUD-001`—`AUD-067`;
`AUD-051` отдельно снят как неподтверждённая гипотеза и не считается дефектом.

## Executive Summary

1. Главный немедленный blocker — опубликованный standalone FastAPI planner
   доступен анонимно, включая изменяющие операции (`AUD-001`, P0). До устранения
   его нельзя считать операторской production-системой.
2. В канонических Spring-сервисах архитектурная база заметно сильнее: отдельные
   БД владельцев, Flyway, `@Version`, idempotency receipts, transactional
   outbox/inbox и fail-closed авторизация используются системно.
3. Модель представительского склада действительно встроена в обычный
   `Warehouse`: есть `representative`, координаты, IANA timezone и направленная
   many-to-many сеть `WarehouseSupportLink`. Отдельной
   `RepresentativeWarehouse` нет — это правильное решение.
4. Planner умеет искать support-кандидатов, объяснять причины, считать точные
   дорожные матрицы и моделировать загрузку по остановкам, но межскладская
   логика пока не замкнута в исполнимый RWMS-маршрут: ресурсы сети выбираются
   неполно, задание со склада-источника недоступно водителю, operational
   placement расходится с JWT, а vehicle reposition является no-op
   (`AUD-020`, `AUD-035`, `AUD-036`, `AUD-052`, `AUD-054`, `AUD-059`—`AUD-062`).
5. Жизненный цикл плана дня небезопасен: возможны несколько активных или
   подтверждённых планов на дату, удаление подтверждённого плана при изменении
   спроса, публикация draft и потеря audit (`AUD-031`, `AUD-040`, `AUD-043`).
6. Генератор тестовой нагрузки правильно не создаёт production tasks в
   DriverApp, но способен удалить планы реальных RWMS-заявок в том же горизонте
   (`AUD-042`) и потому не изолирован достаточно.
7. Клиентский final slot hold — одна из лучших частей системы: warehouse/day
   advisory lock, row locks, повторный route/capacity fingerprint и одна
   транзакция. В текущем WIP устранены оба найденных ложных capacity-result:
   route matrix теперь bounded-батчируется до явного лимита 128 точек, а
   support link больше не открывает неподтверждённый слот (`AUD-028`, `AUD-029`).
8. Наёмные водители моделируются отдельным profile/handoff, но discriminator
   теряется на одной API-границе, канал исполнения не завершён, а panel всё ещё
   использует удалённые из canonical contract даты и отвергает успешный create
   response (`AUD-003`, `AUD-046`, `AUD-063`).
9. Timezone владельцем назначен Warehouse и хранится как canonical IANA ID с
   историей, но standalone planner и Android-клиенты местами используют
   UTC/Moscow/device date (`AUD-005`, `AUD-023`). Multi-timezone rollout сейчас
   небезопасен.
10. DriverApp и WorkerApp имеют зрелую offline-основу: tenant-scoped Room,
    encrypted session/outbox, стабильные operation IDs, photo recovery и
    authoritative conflict reconciliation. Это нужно сохранить.
11. Пользовательские ошибки не централизованы: panel и Android могут показать
    сырой backend `detail`, английский internal enum или platform exception
    (`AUD-006`).
12. Масштабирование всё ещё ограничат квадратная матрица planner, полный
    workspace payload, тысячи DOM markers и mutating sync каждые 15 секунд
    (`AUD-019`, `AUD-026`, `AUD-027`). Непагинированные logistics reads и N+1
    lines устранены в текущем WIP (`AUD-018`).
13. Background recovery клиентских booking переведён на bounded leased queue с
    persisted backoff, quarantine и метриками; poison rows больше не блокируют
    новые записи (`AUD-047`, исправлено в текущем WIP).
14. Audit planner нельзя считать юридически надёжным: actor берётся из тела
    запроса или фиксирован как `local-admin`, а часть plan audit удаляется
    каскадом (`AUD-030`, `AUD-040`).
15. Тестовая база широкая и большинство наборов зелёные, но полные task-board,
    panel и planner gates имеют resource/time-dependent failures; release gate
    пока не детерминирован (`AUD-007`, `AUD-008`, `AUD-024`, `AUD-044`).
16. В текущем WIP исправлена потеря запрещённых/no-trailer/special-price зон:
    exceptional policies снова warehouse-owned и участвуют в planner,
    capacity и клиентском search/hold; исторически удалённая геометрия требует
    внешней резервной копии (`AUD-025`).
17. Динамический actual ETA, route adherence, exception inbox и planned-vs-
    actual пока не образуют production execution loop: есть planned ETA и
    симуляция, но нет подтверждённого владельца live-обновлений.
18. Общая оценка: сильное ядро WMS/RWMS и мобильного исполнения сочетается со
    существенно более слабым standalone planning-контуром. Масштабировать
    нужно не добавлением новых экранов, а закрытием ownership, lifecycle,
    security и execution gaps.
19. Сохранение warehouse settings не является атомарным пользовательским
    действием: standalone фиксирует локальные тарифы/настройки до внешнего
    capacity replace, а producer и consumer по-разному проверяют перерыв смены.
    Это подтверждает один воспроизводимый источник HTTP 400 и оставляет CustomerApp на старой
    capacity-проекции после уже применённого локального изменения (`AUD-064`).

## Critical Findings

### P0

- `AUD-001`: анонимный публичный planner позволяет читать операционные UUID и
  по коду имеет доступ к create/update/delete, confirm и RWMS apply. Требуется
  немедленно закрыть ingress и добавить principal/RBAC/warehouse grants в самом
  приложении.

### P1 — блокеры корректной эксплуатации

- Projection/lifecycle: `AUD-002`, `AUD-022`, `AUD-031`, `AUD-033`, `AUD-039`,
  `AUD-040`, `AUD-041`, `AUD-042`, `AUD-043`.
- Representative/cross-warehouse: `AUD-020`, `AUD-035`, `AUD-036`,
  `AUD-052`, `AUD-054`, `AUD-059`, `AUD-061`, `AUD-062`.
- Cross-service publish/consistency: `AUD-060`, `AUD-064`; durable recovery
  cancel/remove-unit для `AUD-055` исправлен в текущем WIP.
- Contractor: `AUD-003`, `AUD-037`, `AUD-046`, `AUD-063`.
- Клиентская capacity: открытых P1 из этой пары после исправления `AUD-028` и
  `AUD-029` в текущем WIP нет; остаются связанные product gaps из других карточек.
- Data/constraints: `AUD-025` исправлен в текущем WIP; историческая геометрия
  не восстанавливается без внешней резервной копии.
- Audit/security/release: repository-side `AUD-016` исправлен в текущем WIP;
  остаются `AUD-030` и `AUD-049` (последний зависит от неподтверждённой внешней
  ingress-конфигурации), а production key custody/rotation является внешним
  решением.
- Scale: `AUD-019`, `AUD-026`, `AUD-027`; `AUD-018` исправлен в текущем WIP.
- Multi-timezone: `AUD-023` до появления складов вне московского пояса.

## Bugs

Полные сценарии воспроизведения приведены в карточках `AUD-001`—`AUD-067`;
`AUD-051` снят после сквозной проверки и дефектом не считается.
Практический индекс подтверждённых дефектов:

| ID | Severity | Что ломается |
|---|---:|---|
| AUD-001 | P0 | Анонимный доступ к operator planner |
| AUD-002 | P1 | Исчезнувшая из feed заявка продолжает планироваться |
| AUD-003 | P1 | Contractor попадает в штатный optimizer |
| AUD-004 | P2 | Повтор effect после process death получает новый ключ |
| AUD-005 | P2 | DriverApp выбирает день по timezone телефона |
| AUD-006 | P2 | UI показывает raw/internal error |
| AUD-007/008/024/044 | P2 | Нестабильные full test gates |
| AUD-010 | P2 | Неистекающая presentation теряет меняющиеся media generations |
| AUD-011 | P1 | Cabin + photos создаются browser saga с partial result |
| AUD-012 | P2 | Remote contractor command выполняется под DB locks |
| AUD-013 | P2 | Address-only order исключался вместо geocoding flow; исправлено в WIP |
| AUD-018 | P1 scale | Непагинированные reads и N+1 document lines; исправлено в WIP |
| AUD-020 | P1 | Теряется inventory source warehouse |
| AUD-022 | P1 | Planner create/update допускают duplicate/lost update |
| AUD-023 | P1 multi-TZ | Дата вычисляется не по timezone склада |
| AUD-025 | P1 | Потеря запретных/no-trailer/special-price зон; исправлено в WIP |
| AUD-026 | P1 | Read polling каждые 15 секунд мутирует и синхронизирует 31 день |
| AUD-028 | P1 | >30 workload points закрывают все клиентские слоты |
| AUD-029 | P1 | Теоретический support link открывает неподтверждённый слот; исправлено в WIP |
| AUD-031 | P1 | Несколько активных/confirmed plan на warehouse/date |
| AUD-032 | P1 | Close day сообщает успех при rejected RWMS assignments |
| AUD-033 | P1 | Flexible request входит в планы нескольких дат |
| AUD-035 | P1 | Support-warehouse plan не публикуется из-за warehouse mismatch |
| AUD-036 | P1 | Positioning/ETA исправлены в WIP; transfer cargo ещё не входит в общий executable stream |
| AUD-037 | P1/P2 | Contractor UI принимает pickup, backend handoff его отвергает |
| AUD-040 | P1 | Demand change удаляет confirmed plan и audit |
| AUD-041 | P1 | Guard удаления заявки проверяет ссылки уже после их удаления |
| AUD-042 | P1 | Test generator удаляет real RWMS plans |
| AUD-043 | P1 | Draft plan можно применить в RWMS |
| AUD-045 | P2 | Reload повторно объявляет все старые заявки новыми |
| AUD-046 | P1 | Contractor не может получить/исполнить назначенное задание |
| AUD-047 | P1 scale | Poison records блокируют recovery новых bookings; исправлено в WIP |
| AUD-050 | P3 | CustomerApp показывает `28500 ₽` без разделителя тысяч |
| AUD-052 | P1 | Группа склада не сравнивает ресурсы всех допустимых опорных складов |
| AUD-053 | P2/P1 | Simulation рассчитывает отклонение, но возвращает исходный план |
| AUD-054 | P1 | Локальные ресурсы representative warehouse скрыты от группового плана |
| AUD-055 | P1 | После release имущества локальный заказ может остаться активным; исправлено в WIP |
| AUD-056 | P3 | Одна команда комплектации дважды проверяет одну expected version |
| AUD-057 | P2/P1 | Ночная/переходящая месяц смена не представима в planner |
| AUD-058 | P2/P1 | Placeholder `0,0` считается рабочей точкой склада |
| AUD-059 | P1 | Support-водитель не видит задание со склада-источника representative |
| AUD-060 | P1 | Shift plan публикуется до assignments и может остаться пустым/frozen |
| AUD-061 | P1 | Operational destination водителя расходится с warehouse claim DriverApp |
| AUD-062 | P1 | Vehicle reposition подтверждается без изменения operational warehouse |
| AUD-063 | P1 | Panel contractor create не соответствует бессрочному canonical profile |
| AUD-064 | P1 | Settings уже закоммичены, когда несовместимая capacity-смена вызывает HTTP 400 |
| AUD-065 | P3 | UI regression гоняется с асинхронной загрузкой каталога подрядчиков |
| AUD-066 | P3 | Error-boundary tests не доказывают abort и изоляцию malformed body |
| AUD-067 | P3 | Browser AbortError теряет диагностическое сообщение |

## Architecture Findings

| Область | CURRENT | RISK | TARGET |
|---|---|---|---|
| Planner boundary | Отдельный FastAPI/PostGIS владеет локальным demand/plan и сервисным RWMS OAuth | Два жизненных цикла, stale projection, anonymous principal, destructive sync | Аутентифицированный planning application с явной projection protocol; canonical owner подтверждает publish |
| Warehouse projection | Canonical `Warehouse` автоматически проецируется в planner | Polling workspace одновременно читает и мутирует 31 день | Event/incremental sync + явный command/recovery job; GET остаётся side-effect free |
| Day plan | `RoutePlan` имеет version/status, но нет one-active DB invariant | Дубликаты, draft publish, удаление confirmed/audit | Отдельные immutable plan versions; ровно один published pointer на warehouse-local date |
| Cross-warehouse | Support leg/origin и resource intents существуют частично в metrics/API | Водитель не видит часть задач, destination не входит в execution scope, автомобиль не перемещается | Один trip aggregate со stops/legs, service/source/origin/support IDs; driver/vehicle operational assignments и canonical publish contract |
| Contractor | Профиль отдельно, assignment отдельно, но DTO стирает type, а panel ждёт удалённые даты | Ложная ошибка после create, вымышленная capacity либо невыполнимый handoff | Canonical date-free profile + day assignment + typed candidate + scoped external execution surface |
| Slots | Server-side route/capacity и linearizable final hold | Матрица hard cap и speculative support capacity | Bounded incremental capacity model; exact transactional recheck только для доказанного ресурса |
| Manual override | Version-fenced moves, locks и audit существуют | Reoptimize не архивирует source; actor spoofable | Authenticated actor, immutable version lineage, fixed constraints survive replan |
| Media/cabin create | UI оркестрирует несколько владельцев | Partial cabin without photos/tasks | Owning saga with prepare/effects/finalize/recovery |
| Errors | Backend detail используется как UX message | Internal text и непоследовательные действия | Stable error code catalog + localized client presentation + correlation ID |
| Recovery | Несколько зрелых outbox/inbox, но booking retry = first page loop | Starvation без диагностики | Lease/claim, nextAttemptAt, backoff, quarantine и metrics |
| Capacity publication | Warehouse settings коммитятся перед синхронным full-snapshot replace; producer/consumer имеют разные shift invariants | Ложный failed save и stale CustomerApp slots/prices | Единый canonical invariant + durable publish state/outbox + явный partial-success UX |
| Time | Warehouse хранит IANA timezone/history | Часть клиентов/sidecar используют system clock | WarehouseClock/explicit ZoneId на каждой local-date boundary |
| Notifications | In-memory UI baseline и local test logs | Старые события становятся новыми; нет production delivery | Durable user notification event/read watermark/outbox |

## Domain Model Findings

### Warehouse

- `Warehouse` — правильный aggregate owner identity, metadata, coordinates,
  timezone, lifecycle и representative characteristic. `@Version` и
  one-way lifecycle защищают изменения.
- `WarehouseSupportLink` — правильная направленная many-to-many edge с
  уникальностью направления, policy flags, weekdays/dates/exclusions и
  интервалом. Это сеть, а не ошибочный `parentWarehouseId`.
- Слабое место находится не в canonical model, а в planner projection и её
  автоматической синхронизации (`AUD-002`, `AUD-026`).

### Order / Delivery / Pickup

- Отдельной универсальной таблицы `Delivery` нет. Коммерческий владелец —
  `RentalOrder`; физическое исполнение — typed `LogisticsDocument`, его lines,
  `DriverLogisticsTask` и task-board route entries. Такое разделение в целом
  корректно.
- `RentalOrder` не перегружен движением машины: он хранит коммерческий статус,
  desired days, terms и source semantics.
- `LogisticsDocumentState` богатый, но переходы централизованы в aggregate
  methods и workflow stores. Невозможные переходы обычно fail-closed.
- В standalone planner `LogisticsRequest.status` и `PlanningTask.status`
  дублируют lifecycle и меняются слабее (`AUD-039`, `AUD-041`).

### Driver / ExternalDriver / Assignment

- Task-board различает `STAFF` и `CONTRACTOR`, хранит operational assignment с
  lifecycle и version. Это правильная база.
- Теряется именно discriminator при выдаче каталога planner (`AUD-003`).
- Canonical contractor profile правильно отделён от даты задания, но panel
  продолжает требовать и парсить obsolete interval (`AUD-063`).
- Home/operational warehouse разделены в canonical assignment, но execution
  endpoints продолжают ограничиваться home warehouse из JWT; разовый визит и
  reposition поэтому не доходят до исполнимого DriverApp flow
  (`AUD-035/036/059/061`).

### Vehicle / Trip / Cycle / Cargo / DayPlan

- Planner vehicle содержит capacity, trailer и физические размеры/массы; stop
  хранит `load_before/load_after`, поэтому leg-level capacity моделируема.
- `Vehicle` фактически имеет только `active` вместо полноценного operational
  lifecycle; maintenance/defect availability не подтверждена как вход planner.
  Transfer plan принимает temporary/permanent vehicle reposition, но owning
  assignment aggregate/effect отсутствует (`AUD-062`).
- `RouteCycle` — depot-to-depot plan fragment, не execution aggregate. У него
  есть `locked` и manual flags, но нет собственного state machine.
- `RoutePlan` — текущий DayPlan, но его версия является mutable row version, а
  не immutable published revision. Это источник `AUD-031/040/043`.
- Физический cabin ID и `inventorySourceWarehouseId` не проходят через весь
  planner plan (`AUD-020`), поэтому cargo model для regional direct fulfillment
  неполна.

## State Machines

| Aggregate | Подтверждённая цепочка | Оценка |
|---|---|---|
| RentalOrder | `DRAFT → SAVED → FULFILLED → CLOSED`; `DRAFT → CANCELLED`; completed inventory имеет отдельную terminal компенсацию | Строгая, переходы внутри aggregate, fulfill/close idempotent |
| Return document | `DRAFT → REGISTERING → INSPECTION_REQUIRED → ACCEPTING → ACCEPTED → ESTIMATE_PENDING/REQUESTED`, с `CONFLICT/RECONCILIATION_REQUIRED` | Строгая orchestration state machine |
| Shipment document | `DRAFT/PREPARING → AWAITING_CONFIRMATION → CONFIRMING_PREPARATION → SHIPPED`, pre-start cancellation и reconciliation | Строгая, но много cross-service effects |
| Transfer document | `DRAFT → DEPARTING → IN_TRANSIT → ARRIVING → COMPLETED`, cancellation только до допустимой границы, reconciliation path | Хорошая физическая custody state machine |
| Driver logistics task | `REGISTERING → SCHEDULED → CURRENT → FINALIZING → COMPLETED`; `CANCELLED/RECONCILIATION_REQUIRED` terminal | Хорошая, task-board version синхронизируется |
| Task-board route entry | `WAITING → IN_PROGRESS ↔ PAUSED → DONE`, pre-start `CANCELLED` | Service-owned, optimistic/fenced; mobile surface policy запрещает cross-role команды |
| Driver shift | `DAILY_BRIEFING_REQUIRED → MEDICAL_CHECK_REQUIRED → VEHICLE_INSPECTION_REQUIRED → READY_TO_START → SHIFT_ACTIVE → SHIFT_CLOSING → RETURN_TO_WAREHOUSE_REQUIRED → END_VEHICLE_CHECK_REQUIRED → SHIFT_READY_TO_CLOSE → SHIFT_CLOSED` | Образцовая adjacent state machine, server timestamps |
| Planner request/task | Редактируемые `DRAFT/READY/PLANNED/IN_PROGRESS/COMPLETED/CANCELLED/UNASSIGNED` | Не state machine: переходы и ссылки расходятся (`AUD-039/041`) |
| Planner plan | `DRAFT/GENERATED/VALIDATED/CONFIRMED/ARCHIVED` | Переходы частично проверяются, но DB uniqueness и immutable lineage нет |
| RouteCycle | status отсутствует; только plan membership, lock и timestamps | Нормально для draft projection, недостаточно как execution trip |
| Vehicle | `active` boolean + configuration | Нет подтверждённой maintenance/defect state machine в planner input |

Не подтверждено — требуется дополнительная проверка: единая state machine
клиентской отмены/failed delivery отсутствует, потому что сам supported flow
отмены подтверждённого CustomerApp заказа не найден (`AUD-038`).

## Race Conditions, Idempotency и Transactions

### Что защищено хорошо

- Canonical logistics document creates используют subject + operation +
  `Idempotency-Key`, request digest, row/advisory locks и unique receipts.
- `RentalOrder`, warehouse, driver shift и большинство task-board mutations
  несут `expectedVersion`/`@Version`; 409 заставляет клиент перечитать
  authoritative state.
- Финальный CustomerApp slot hold линеаризован с planner capacity и whole-day
  driver reservations: два клиента не могут незаметно занять один остаток
  мощности.
- Asset transfer reserve/depart/arrive использует конкретные cabin IDs,
  versions и идемпотентные эффекты; между source, in-transit и destination есть
  отдельная custody фаза.
- DriverApp/WorkerApp сохраняют operation ID вместе с offline command и media
  evidence, поэтому network retry не создаёт новый бизнес-эффект.

### Подтверждённые слабые места

| Сценарий двух участников | Фактический результат |
|---|---|
| Два planner-клиента создают один catalog object | Нет обязательного idempotency/expectedVersion; возможен duplicate либо DB 500 (`AUD-022`) |
| Два reoptimize/auto-run на один день | Нет unique active-plan invariant; обе версии могут остаться активными (`AUD-031`) |
| Flexible request строится на двух датах | Нет глобального reservation fence на request; request попадает в оба плана (`AUD-033`) |
| Demand refresh против confirmed plan | Один path запрещает, другой cascade-delete удаляет plan/audit (`AUD-040`) |
| Contractor handoff + удалённый task-board | Локальные row/advisory locks удерживаются через HTTP (`AUD-012`) |
| Customer problem report после process death | Новый idempotency key может создать дубль (`AUD-004`) |
| Background booking recovery | Исправлено в WIP: bounded due claims, lease fencing, backoff и quarantine обеспечивают продвижение новых записей (`AUD-047`) |
| Publish shift plan против stale assignment | Смена регистрируется первой; rejected assignment не компенсирует пустой ready plan (`AUD-060`) |
| Vehicle reposition против planner catalog | Intent подтверждается без vehicle assignment/fence; старый склад продолжает считать автомобиль своим (`AUD-062`) |
| Settings save против capacity consumer | Локальный commit уже завершён, когда более строгий consumer отвергает весь snapshot; UI сообщает общий failure (`AUD-064`) |

### Partial updates

- `AUD-011`: browser сначала создаёт cabin, затем media/effects. Падение между
  шагами оставляет реальную бытовку без обещанной photo setup.
- Canonical Spring cross-service workflows в основном используют
  prepare/remote/finalize и recovery receipts — это правильная альтернатива 2PC.
- Standalone planner sync смешивает refresh, invalidation и generation в
  запросе workspace; partial failure труднее объяснить и повторить.
- Planner apply должен быть отдельным идемпотентным publish workflow с
  per-assignment outcomes; сейчас `AUD-032/043` позволяют ложный общий успех
  или применение неутверждённого состояния.
- `AUD-060`: shift plan становится отдельным remote fact до проверки всех
  assignment. Нужен `PREPARING`/finalization barrier либо компенсация и
  reconciliation состава смены.
- `AUD-064`: warehouse settings, тарифы и capacity generation становятся
  durable до вызова remote replace. Откат request-scoped session уже не может
  отменить commit; нужен persisted publish status/retry и отдельный
  пользовательский outcome, а не видимость одной атомарной операции.

## Database Findings

### Сильные стороны

- Каждый stateful Spring service владеет своей PostgreSQL; cross-database FK и
  shared JPA entities не найдены.
- Schema mutation выполняется service-local Flyway, Hibernate работает как
  validator. Planner отдельно использует Alembic для своей проекции.
- В ключевых aggregates есть версии, check/unique constraints, event stream,
  outbox/inbox, quarantine для version gaps и индексы по operational keys.
- Transfer schema хранит конкретные cabin allocations, source balance/reserve
  versions и in-transit workflow, а не только строку «2 бытовки».

### Риски

- `route_plans` не имеет partial unique index «один active/published plan на
  warehouse/date» (`AUD-031`).
- Planner enum statuses хранятся строкой и не везде защищены DB check;
  application допускает несогласованные request/task/plan combinations.
- Logistics document list теперь читает bounded page и batch-load её lines;
  прежний N+1 устранён в WIP (`AUD-018`).
- Retention/partition/archive policy для event, audit, media metadata, route
  traces, notifications и многолетней истории не подтверждена (`AUD-017`).
- Booking recovery переведён на persisted due schedule, leased `SKIP LOCKED`
  claims и quarantine в WIP (`AUD-047`).
- Миграция zone model была destructive относительно ещё нужных бизнес-типов;
  replacement semantics восстановлены в WIP, но удалённую ранее геометрию
  можно вернуть только из внешней резервной копии (`AUD-025`).

## API Findings

### Сильные стороны

- Canonical OpenAPI под `contracts/openapi/` действительно задаёт границы;
  Spring DTO не являются JPA entities.
- Большинство mutable canonical commands имеют idempotency header,
  `expectedVersion`, domain code и корректный 409.
- Private service APIs используют отдельные OAuth audiences/scopes; browser и
  Android идут через public gateway.
- Customer price рассчитывается server-side; клиент не присылает доверенную
  итоговую стоимость.

### Проблемы

- Standalone `/logistics-simulator/api/**` не versioned и не authenticated
  (`AUD-001`).
- Workspace GET фактически является command: sync feed, invalidation,
  autogeneration (`AUD-026`). Это нарушает HTTP/read semantics.
- Workspace payload отдаёт всю историю заявок, планы, resources и map points
  без page/cursor (`AUD-027`).
- Logistics document API сохранил array-body совместимость и получил bounded
  pagination headers; N+1 устранён batch projection (`AUD-018`, исправлено в WIP).
- SSE contract обещает `Last-Event-ID`, но producer не умеет durable replay
  (`AUD-009`).
- `employmentType` теряется в planner driver DTO (`AUD-003`), а
  `inventorySourceWarehouseId` — в planning request (`AUD-020`).
- Panel contractor DTO расходится с canonical task-board schema: лишние даты в
  request и обязательные несуществующие даты в response (`AUD-063`).
- Capacity shift contract слабее реального Spring consumer: OpenAPI допускает
  `breakMinutes`, равный всей длительности смены, но endpoint отклоняет его
  HTTP 400. Producer принимает и сохраняет тот же payload (`AUD-064`).
- Error envelope технически унифицирован как Problem Details, но его `detail`
  ошибочно считается готовым UX-текстом (`AUD-006`). Нужен stable catalog:
  `code`, safe localized message key, recovery action, correlation ID.
- API contractor принимает разные task kinds на разных слоях (`AUD-037`).

## Validation Findings

- Warehouse backend проверяет обязательный canonical IANA timezone, парность и
  диапазоны координат, нормализованное уникальное имя и optimistic version.
- Vehicle/trailer/cargo имеют положительные physical dimensions/weight,
  capacity bounds и consistency checks; planner повторно проверяет capacity,
  shift, break, time windows и route after manual edits.
- Customer slot backend не доверяет frontend: повторно проверяет cart/slot
  versions, cabin count, address attestations, route, tariff, warehouse policy
  и workload fingerprint.
- Inactive drivers/vehicles/shifts фильтруются из обычных planning candidates.
- Цена не может быть отрицательной и вычисляется server-side.
- Подтверждённые gaps: status transitions planner не централизованы
  (`AUD-039`), source inventory не валидируется end-to-end (`AUD-020`),
  contractor kind validation расходится (`AUD-037`). Address-only demand
  (`AUD-013`) и единое правило `breakMinutes < duration` (`AUD-064`) исправлены
  в текущем WIP, но ещё не опубликованы.
- Координаты `(0,0)` формально валидны по WGS84, но текущий продукт использует
  их как placeholder отсутствующей географии и одновременно считает склад
  routable. Это подтверждённый readiness bug (`AUD-058`): нужна единая
  warehouse-owned политика «координаты пригодны для логистики».

## Error Handling Catalog — целевое UX-поведение

| Техническая категория | Что видеть пользователю | Системное действие |
|---|---|---|
| 400/422 invalid input | Конкретное поле и способ исправить | Не retry; сохранить введённые данные |
| 409 version conflict | «Данные изменил другой пользователь; мы обновили карточку» | Authoritative refetch/rebase, без скрытого overwrite |
| Capacity/window/shift conflict | «Рейс не помещается…» + перенести/расширить окно/contractor | Structured reason code, не внутренний constraint dump |
| 401 | «Сессия истекла» | Один token refresh, затем sign-in |
| 403 | «Недостаточно прав для этого склада/действия» | Не retry; audit denied attempt |
| 404 owner-scoped | Нейтральное «объект не найден» | Не раскрывать чужой warehouse/subject |
| 429 | «Слишком много запросов; повторите позже» | Уважать Retry-After/backoff |
| 502/503/504/transport | «Сервис временно недоступен; действие сохранено/не сохранено» | Retry только для идемпотентного/durable effect |
| 500/unknown | «Не удалось выполнить операцию. Код обращения …» | Correlation ID, structured log/alert |
| SQL/stack/JSON/internal enum | Никогда не показывать | Только sanitized telemetry с секретами/PII redaction |

Текущий `AUD-006` показывает, что этот catalog пока не является общей
реализацией: отдельные экраны имеют хорошие local mappings, но общий transport
слой всё ещё доверяет `detail`.
`AUD-064` добавляет отдельное требование: сообщение об upstream-сбое обязано
явно говорить, было ли локальное изменение уже сохранено. Простая локализация
502 не исправляет ложную семантику результата команды.

## Time and Timezone Findings

- Source of truth сделан правильно: `Warehouse.timeZone` — canonical IANA ID,
  есть revision и append-only effective history.
- Server driver shift использует warehouse-local work date и server timestamps.
- Ошибки находятся на границах: DriverApp применяет device `LocalDate.now()`,
  planner содержит UTC/Moscow defaults, а некоторые tests зависят от системной
  полуночи (`AUD-005`, `AUD-007`, `AUD-008`, `AUD-023`).
- Ночные смены в standalone planner ограничены `end_time > start_time`, то есть
  повторяемая смена через 00:00 этой моделью не представляется. Для текущих
  дневных смен это допустимо; поддержка overnight shift **не подтверждена —
  требуется продуктовое решение**, а не молчаливое изменение.
- DST корректно представим через ZoneId/aware datetimes, но сквозной test со
  складом в DST-поясе не найден.

## Geography Findings

- Warehouse coordinates — canonical source карты; planner projection
  materializes склад автоматически, ручного второго warehouse lifecycle нет.
- Latitude/longitude не перепутаны в проверенных mappers; диапазоны и парность
  валидируются.
- Route feasibility использует directed road provider и truck profile;
  straight-line/isochrone не считается финальным маршрутом.
- Isochrone ladder хранит время и цену, крайняя изохрона ограничивает
  customer availability; отдельные special/restriction semantics восстановлены
  в текущем WIP и не подменяют обычное покрытие (`AUD-025`).
- Route/cache key включает coordinates, поэтому изменение координат создаёт
  другой key. Explicit purge старых bounded entries не обязателен для
  correctness, но graph/profile version нужен (`AUD-034`).
- Boundary behavior сложных polygons покрыт библиотечным spatial predicate;
  отдельного production dataset test для точек ровно на границе не найдено —
  не подтверждено, требуется проверка на реальных геометриях.

## Delivery Price and Payments

- Итоговая delivery price рассчитывается backend по warehouse isochrone/special
  tariff, сохраняется в held slot/planning revision и возвращается в canonical
  API.
- Standalone logistics cards, unassigned list, map card и slot checker уже
  отображают цену через `formatDeliveryPrice`; `null` становится
  «Не рассчитана», а не `0 ₽`. CustomerApp также показывает slot price и имеет
  regression test на отсутствие ложного `0 ₽`, но пока не форматирует тысячи
  (`AUD-050`).
- После reload цена приходит из сохранённого request/slot projection, а не из
  frontend constant. Это требование реализовано правильно.
- Но изменение tariff может сохраниться только в standalone и не попасть в
  capacity snapshot из-за одной невалидной смены. Тогда dispatcher после
  refresh видит новую цену, а CustomerApp продолжает рассчитывать доступность
  по старой проекции (`AUD-064`).
- Полноценного payment/acquiring/refund aggregate или API в активном проекте не
  найдено. Поэтому idempotency оплаты оценить невозможно и нельзя объявлять её
  реализованной: **не подтверждено — требуется отдельный scope, если RWMS должен
  принимать платежи**.

## Frontend Findings

### Panel

- Активный `panel/` следует React/TypeScript/Vite/TanStack/shadcn паттернам;
  typecheck и lint зелёные.
- Warehouse form и representative support links находятся в существующих
  settings, а не в отдельном приложении.
- Общий `ApiError` сохраняет HTTP status/code, но `readErrorDetails` принимает
  raw backend detail; десятки компонентов выводят `error.message` (`AUD-006`).
- Cabin create/media flow оркестрируется браузером (`AUD-011`).
- Форма создания наёмника и её strict response parser используют устаревшие
  `availableFrom/availableUntil`, которых нет в canonical task-board contract;
  успешный backend create превращается в UI error (`AUD-063`).
- Полный test suite чувствителен к параллельной нагрузке и общему 5s timeout,
  хотя focused rerun зелёный (`AUD-044`).

### Standalone logistics UI

- Хорошо: warehouse dropdown группирует main/representative warehouses; marker
  warehouse selection не меняет zoom; list → map использует pan/ease с текущим
  zoom; timezone отображается у выбранного склада; simulation и reason codes
  помогают логисту.
- Плохо: UI каждые 15 секунд вызывает mutating workspace refresh (`AUD-026`),
  создаёт marker на каждую точку (`AUD-027`) и заново объявляет старые requests
  новыми после reload (`AUD-045`).
- Plan/editor/simulation показывают близкие проекции, но имеют разные функции:
  слепое удаление любой из них сейчас не оправдано. Следует объединить
  навигацию и сохранить simulation как непубликуемый сценарий.
- Технические planner constraints могут попасть пользователю вместо короткой
  русской причины (`AUD-006`).
- Generic settings save не различает local commit и failed capacity publish:
  после upstream rejection он не refresh/reconcile состояние и показывает
  обычную ошибку всей операции. Аналогичный generator flow такую ситуацию уже
  обрабатывает отдельно, что подтверждает непоследовательность (`AUD-064`).

## Mobile Findings

### DriverApp

- Сильная offline-first реализация: Room, encrypted session, durable outbox,
  stable command/evidence IDs, правильный порядок reservation → upload →
  finalize → complete, conflict recovery и сохранение фото при закрытии
  процесса.
- Строгая DriverShift state machine, обязательные photo/evidence gates и
  разделение Driver/Worker mobile surfaces.
- Применённый route snapshot и warehouse-timezone planned ETA исправлены в WIP;
  pre-claim и dynamic/actual ETA owner остаются открытой частью `AUD-015`.
- Cross-warehouse execution scope сломан: task warehouse может отличаться от
  claim/home warehouse, а repositioned водитель остаётся авторизован только в
  старом складе (`AUD-059/061`).

### WorkerApp

- Те же сильные offline/tenant isolation primitives, role-specific commands и
  media recovery. Полный JDK17 unit gate зелёный.
- Raw backend detail всё ещё может пройти в sync outcome (`AUD-006`).

### CustomerApp

- Сильные стороны: OAuth/PKCE, encrypted session, explicit warehouse, server
  address/route/slot price, durable inquiry create key, slot hold и booking
  recovery.
- Weak points: часть effect keys живёт только в памяти (`AUD-004`), нет
  supported cancel/reschedule после booking (`AUD-038`), hard matrix limit
  превращается в «нет слотов» (`AUD-028`).
- Representative warehouses корректно присутствуют при active + coordinates;
  для них предлагается только day-level `DURING_DAY`, что соответствует
  текущей политике.

### ManagerApp

- Unit tests зелёные, domain clients используют gateway и version conflicts.
- Repository-side signing и APK trust gate теперь воспроизводим для всех
  Android release surfaces (`AUD-016` исправлен в WIP). Production-публикация
  ManagerApp/DriverApp остаётся fail-closed до внешнего provision signer hash.

## Security Findings

### Критично

- `AUD-001`: planner полностью обходит auth/RBAC/warehouse isolation.
- `AUD-049`: anonymous customer registration требует внешнего throttling;
  наличие этого правила на реальном production ingress не подтверждено.
- `AUD-030`: planner audit actor контролируется клиентом и не связан с JWT.

### Что сделано хорошо

- Spring APIs локально валидируют JWT audience/scope и warehouse grants; gateway
  не является единственной линией защиты.
- Customer endpoints связывают inquiry/cart/media с JWT subject, а не только с
  UUID в URL.
- Media-service хранит MinIO private, не выдаёт object key/origin/credentials,
  проверяет owner proof, subject и warehouse; unversioned public download не
  найден.
- Driver/Worker surface policies запрещают использовать токен одного native
  приложения для команд другого.
- Repository scan не обнаружил private keys или очевидные production secrets;
  найденные pattern hits были test/dev material. Это не заменяет secret scan
  runtime/VPS.

### Не подтверждено — требуется дополнительная проверка

- Реальные TLS/ingress headers, WAF/rate limit и network ACL находятся вне
  репозитория.
- Ротация production OAuth client secrets, MinIO credentials, backup encryption
  и incident access logs без runtime/secret manager не доказаны.

## Reliability / Offline Findings

- DriverApp/WorkerApp: высокая устойчивость к network loss, process death,
  duplicate upload и lost response.
- Customer checkout: durable receipt, short local transactions и bounded
  autonomous recovery (`AUD-047`, исправлено в WIP) сделаны хорошо; отдельные
  ephemeral keys (`AUD-004`) ещё оставляют пробелы.
- Spring event delivery: outbox/inbox, aggregate ordering, dedup и version-gap
  quarantine — сильная системная часть.
- Planner: DB-backed plans есть, но polling mutation, destructive regeneration,
  no active-plan invariant и анонимные команды снижают надёжность сильнее, чем
  наличие обычной SQL transaction её повышает.
- SSE является invalidation hint, а не durable stream; клиентский periodic pull
  компенсирует loss, но контракт должен быть честным (`AUD-009`).

## Performance Findings

### NOW

- Убрать mutating 31-day sync с каждого 15-second workspace poll (`AUD-026`).
- Pagination/batch loading logistics documents реализованы и проверены в WIP
  (`AUD-018`).
- Recovery starvation устранён persisted schedule, lease/backoff/quarantine и
  метриками (`AUD-047`, реализовано и проверено в WIP).

### SOON

- Workspace отдаёт bounded date/status slice; map использует clustering/
  virtualization вместо marker на каждую историческую заявку (`AUD-027`).
- Route/cache keys получают graph/profile version и TTL (`AUD-034`).
- Background jobs получают lease, bounded concurrency, metrics и backpressure.

### AT SCALE

- `N×N` exact matrix для 10 000 задач физически неприемлема (`AUD-019`): нужны
  географическая декомпозиция, staged candidate pruning, bounded submatrices и
  budgeted optimization.
- История/outbox/audit/route trace нуждается в retention/partition/archive
  policy (`AUD-017`).
- Frontend не должен получать 10 000 полных entities; нужны server summaries,
  cursors и detail-on-demand.

## Observability and Explainable Logistics

### Уже есть

- Planner сохраняет structured reason codes, validation issues, score,
  warnings, detour, slack, arrival, empty distance и shift utilization.
- Spring services проводят correlation IDs через события и имеют outbox/inbox
  backlog/version-gap metrics.
- Manual changes и transfer workflows сохраняют audit/history в большинстве
  canonical paths.

### Не хватает

- Единый `planningRunId`/trace от customer slot или RWMS order через candidate,
  publish, task-board и DriverApp execution.
- Durable reason для каждого unassigned и rejected publish outcome, доступный
  логисту без debugger.
- Метрики backlog, возраста oldest pending и quarantine добавлены fixed-name
  gauges без динамических labels (`AUD-047`, реализовано в WIP).
- Server-authenticated audit actor (`AUD-030`) и immutable plan lineage
  (`AUD-040`).
- Actual GPS/arrival/departure telemetry, route adherence и live ETA owner.

Ответ на вопрос «почему доставка не распределилась?» в standalone planner часто
можно получить из reason codes. Ответ «что с ней произошло после публикации и
почему факт отклонился от плана?» пока нельзя получить одной связной трассой.

## Audit Log Findings

- Canonical Spring aggregates создают domain events с subject/correlation,
  event store и outbox; многие workflow receipts позволяют восстановить точную
  повторную команду и результат.
- Task execution хранит server timestamps и append-only time events; transfer
  custody и inventory history пригодны для расследования.
- Planner `ManualChangeAudit` хранит before/after plan version, reason и payload,
  но `changed_by` берётся из клиента/`local-admin` (`AUD-030`).
- Audit rows planner cascade-delete вместе с plan, а некоторые input changes
  физически удаляют confirmed plan (`AUD-040`). Поэтому журнал planner не
  соответствует требованию «кто/когда/что изменил» до исправления identity и
  retention.
- Единый cross-service audit view по order/delivery/trip/cabin/driver отсутствует;
  dossier частично решает cabin history, но не полный dispatch decision trail.

## DevOps / SRE Findings

- Репозиторий сознательно содержит local `compose.yaml`, а не production
  orchestration; runtime ingress/backup/secret rotation нельзя вывести из него.
- Опубликованный `logistics/deploy/nginx-public-path.conf` раскрывает planner
  без auth (`AUD-001`) — это подтверждённая deployment ошибка, а не только код.
- Health/readiness и Micrometer/OpenMetrics присутствуют у многих сервисов;
  outbox backlog/age metrics — хорошая база.
- Полные suites демонстрируют resource pressure: PostgreSQL `too many clients`,
  Java heap и Vitest timeout. CI/VPS должны иметь явные worker budgets,
  Testcontainers cleanup и стабильный JDK17 toolchain (`AUD-024/044`).
- Audit не запускал deploy/restart и не менял runtime. Состояние production
  backups, restore drills, RPO/RTO, alert routing и autoscaling не подтверждено.

## UX Findings

### Клиент

- Хорошо: склад, адрес, карта, cabin selection, furniture, date/slot, цена и
  booking образуют понятный server-backed flow.
- Плохо: отсутствие cancel/reschedule, ложное «нет слотов» при limit 32,
  технические ошибки и отсутствие точного статуса восстановления заказа.
- Автоматизировать: geocoding/address readiness, предложение ближайшего
  подтверждаемого дня, повторный exact capacity check, уведомление об изменении
  ETA. Не выбирать за клиента другой платный тариф без подтверждения.

### Логист

- Хорошо: карта связана со списком, plan reasons, locks, simulation и
  unassigned причины уже уменьшают ручную работу.
- Плохо: пользователь вынужден доверять плану, который может не опубликоваться;
  contractor assignment не доходит до исполнителя; reload создаёт notification
  noise; технические причины просачиваются в UI.
- Автоматизировать: exception inbox, сравнение support alternatives, полезную
  загрузку как side-effect-free proposal, recovery/publish retry. Оставить
  ручное подтверждение пустого пробега, source cabin и contractor handoff.

### Водитель/рабочий

- Хорошо: последовательные действия, offline commands и фото-гейты.
- Плохо: нет live route/ETA loop, warehouse timezone не везде используется,
  contractor вообще не имеет execution surface.

## Logistics Algorithm Findings

### Как работает текущий городской план

1. Planner получает READY demand выбранного main warehouse и напрямую
   обслуживаемых representative warehouses.
2. Большая заявка раскладывается на vehicle-sized `PlanningTask` по 1–2
   бытовки с сохранением delivery/pickup, priority, hard window и cargo facts.
3. Загружаются активные driver/vehicle shifts, vehicle/trailer profile,
   warehouse operations time и ограничения.
4. Для кандидатов запрашивается directed road matrix с departure-aware truck
   profile. Изохрона используется для тарифа/доступности, но не заменяет
   дорожный маршрут.
5. Детерминированная heuristic сначала защищает обязательные deliveries,
   затем вставляет pickups/другие задачи перед, между или после stops, создавая
   дополнительные depot cycles при необходимости.
6. На каждой stop фиксируются `quantity_delta`, `load_before/load_after`; shift,
   break, capacity, hard windows, service duration и depot return валидируются
   по последовательности, а не по общему числу уникальных бытовок.
7. Score учитывает route duration/distance, empty mileage, detour, resource
   activation и workload balance. Не назначенные задачи сохраняют reason codes.
8. Ручной move/reorder пересчитывается server-side и принимается только после
   полной валидации. Locked cycle должен пережить reoptimization.
9. Confirm сохраняет local plan и warning/empty-positioning audit; отдельный
   RWMS apply создаёт canonical driver assignments только для реальных RWMS
   deliveries.

### Между складами

- Support links фильтруются по active/calendar/capability. Planner строит
  candidates водителей опорных складов, учитывает positioning arrival/buffer и
  объясняет `NO_LOCAL_DRIVER`, support origin и empty leg.
- Transfer arrival preview использует точный routing provider и заданное время
  отправления, не прямую линию.
- Positioning теперь входит в canonical ordered shift operations и DriverApp
  planned ETA. Открытая часть `AUD-036` — actual load/unload interwarehouse
  transfer cargo в той же последовательности; inventory snapshot не
  подменяется плановыми операциями.
- Canonical publish проверяет driver/shift warehouse и отвергает support plan
  (`AUD-035`).
- Inventory source и concrete cabin allocation не проходят через planner до
  direct fulfillment (`AUD-020`). Поэтому заявленная альтернатива
  «забрать местную бытовку» против «везти бытовку основного склада прямо
  клиенту» ещё не является замкнутой оптимизационной альтернативой.
- Даже когда task-board создаёт задание на физическом representative
  inventory-source warehouse, DriverApp support-водителя читает только home
  warehouse claim; выполнить load/unload последовательность нельзя
  (`AUD-059`).
- Reposition водителя влияет на directory, но не расширяет mobile execution
  scope (`AUD-061`); аналогичный vehicle intent вообще не создаёт назначения
  и является подтверждаемым no-op (`AUD-062`).
- При нескольких support warehouses групповой plan не сравнивает полный набор
  их ресурсов, а локальные ресурсы representative скрыты (`AUD-052/054`).

### Практические ограничения алгоритма

- Это детерминированная bounded heuristic, а не глобальный оптимум VRPTW. Для
  текущего продукта это разумно: объяснимость и воспроизводимость важнее
  теоретического optimum.
- Exact full-day matrix имеет квадратичную стоимость (`AUD-019`); без
  decomposition целевой объём 10 000 задач недостижим.
- Реальный traffic/dynamic ETA после старта не возвращается в optimizer.
- Vehicle maintenance/defect, carrier cost/rating и фактическая дорожная
  телеметрия не подтверждены как constraints.
- Side-effect-free candidate evaluation соблюдается в основном, но plan
  lifecycle/publish fences недостаточны (`AUD-031/043`), а shift plan
  публикуется раньше проверки assignment (`AUD-060`).

## Manual Override and Auto-planning

### Manual override

- Логист может version-fenced переместить/reorder task, lock task/cycle,
  изменить допустимые planning facts и повторно валидировать план.
- Backend, а не React, пересчитывает route/capacity/window/shift constraints.
- Confirmed plan запрещён для обычного manual edit; warnings и empty support
  leg требуют явного принятия/причины.
- Locked cycles передаются в reoptimization, то есть механизм fixed assignment
  существует.
- Недостатки: actor недостоверный (`AUD-030`), reoptimize оставляет source
  active (`AUD-031`), часть refresh/generator paths удаляет plan/audit
  (`AUD-040/042`), а raw validation message не всегда пригоден пользователю.

### Auto-planning

- Seeded/deterministic heuristic и persisted optimization run позволяют
  повторить расчёт при одинаковом snapshot/provider.
- `DRAFT/GENERATED/VALIDATED/CONFIRMED/ARCHIVED` формально разделены, simulation
  не должна публиковать inventory effects.
- Но это не полная модель `DRAFT PLAN` против immutable `PUBLISHED PLAN`:
  published pointer, uniqueness, rollback и immutable lineage отсутствуют.
- Background refresh способен изменить/удалить mutable plan без отдельного
  operator command (`AUD-026/040/042`). Поэтому опубликованный план пока нельзя
  считать защищённым от следующего auto run на всех paths.
- Rollback к предыдущей immutable опубликованной версии не подтверждён.

## Missing Enterprise Logistics Capabilities

Оценка означает ценность для RWMS, а не абстрактную популярность функции.

| Возможность | Ценность | Текущее состояние и вывод |
|---|---|---|
| Planned ETA | HIGH VALUE | Applied-plan ETA доведён до task-board/DriverApp; pre-claim preview остаётся открытой частью `AUD-015` |
| Dynamic ETA | HIGH VALUE | Нет production owner/live loop; нужен после фактической телеметрии |
| Exception Management | HIGH VALUE | Reason codes есть, но единого exception inbox и execution exceptions нет |
| Dynamic Replanning | HIGH VALUE | Simulation/reoptimize есть для plan, не для live canonical remainder |
| Proof of Delivery | HIGH VALUE | Фото/evidence и timestamps есть; подпись/geoposition/тип доказательства неполны |
| Geofencing | MEDIUM VALUE | Enum `GEOFENCE` есть, service пока принимает только `MANUAL`; внедрять после location consent/battery policy |
| SLA | HIGH VALUE | Hard windows валидируются; нужен actual SLA breach loop |
| Driver utilization | HIGH VALUE | Planned shift utilization считается; добавить actual comparison |
| Vehicle utilization | HIGH VALUE | Planned load/activation есть; actual usage отсутствует |
| Deadhead / empty mileage | HIGH VALUE | Метрика и penalty есть; execution/proposal gap `AUD-036` |
| Cost per delivery | HIGH VALUE | Customer tariff есть; полной фактической себестоимости рейса нет |
| Planned vs Actual | HIGH VALUE | Нет сквозной actual timeline; необходим для улучшения нормативов |
| Route adherence | MEDIUM VALUE | Не подтверждено; полезно после opt-in GPS, не раньше |
| Dispatch board | HIGH VALUE | Базовая карта/план/нераспределённые уже есть; надо стабилизировать, не создавать второй board |
| Exception inbox | HIGH VALUE | Один из лучших следующих UX-шагов: показывать только решения логиста |
| Planning scenarios | MEDIUM VALUE | Simulation уже есть; добавить resource what-if после стабилизации |
| Simulation | HIGH VALUE | Уже сильная часть; сохранить side-effect free и отделить от publish |
| Historical analytics | MEDIUM VALUE | Event data есть, но retention/actual facts недостаточны |
| Customer notifications | HIGH VALUE | Local test log есть; production outbox/provider/consent не реализованы |
| Driver communication | MEDIUM VALUE | Адреса/tasks есть; чат не нужен первым, нужны structured exception callbacks |
| External carriers | HIGH VALUE | Профиль/handoff начаты, execution channel отсутствует (`AUD-046`) |
| Carrier rating | MEDIUM VALUE | Полезно позже после накопления actual cost/SLA; сейчас данных нет |
| Delivery priority | HIGH VALUE | Priority/mandatory уже есть; сохранить единым constraint |
| Constraints engine | HIGH VALUE | Constraints существуют, но разделены между planner/canonical services; нужен один authoritative publish validation contract, не новый универсальный микросервис |
| Appointment scheduling | HIGH VALUE | Hard windows/slots есть; cancel/reschedule и confirmation semantics неполны |
| Failed delivery workflow | HIGH VALUE | Customer problem evidence есть, но отдельного failed-delivery state flow не подтверждено |
| Return logistics | HIGH VALUE | Сильный existing return/transfer workflow; расширять, не заменять |
| Capacity forecasting | HIGH VALUE | Day capacity есть; недельный overload forecast будет полезен после исправления speculative support |
| Demand heatmap | MEDIUM VALUE | Полезна на десятках складов, но не раньше pagination/aggregation |
| Driver shift planning | HIGH VALUE | Period shifts и strict daily shift есть; нужен forecast/conflict UX |
| Maintenance constraints | HIGH VALUE | WMS ремонт существует, но planner vehicle maintenance input не подтверждён |
| Digital twin / simulation | MEDIUM VALUE | Базовая детерминированная simulation уже покрывает большую часть ценности; сложный digital twin сейчас не нужен |

## Features That Should NOT Be Added

1. Отдельная сущность или приложение `RepresentativeWarehouse` — сломает
   единый warehouse identity и уже правильную support-link сеть.
2. Второй optimizer/capacity/slot calculator — сначала нужно сделать текущий
   planner authoritative на publish boundary.
3. AI/LLM, который «сам решает маршруты». Структурированные deterministic
   reasons уже лучше проверяемы и аудируемы.
4. Жёсткий `parentWarehouseId` и древовидная иерархия — связи many-to-many уже
   соответствуют реальной сети.
5. Ещё один contractor mobile app до определения credentials/scoped handoff;
   сначала достаточно безопасной ограниченной execution surface.
6. Полный live GPS surveillance без правовой цели, consent, retention и battery
   budget. Для первого шага достаточно event/geofence evidence.
7. Predictive ML demand forecasting до появления чистого planned-vs-actual
   датасета и исправления stale/generated demand.
8. Новые статусы «на всякий случай». Сначала нужен каталог допустимых переходов
   существующих planner statuses.
9. Browser-owned compensation/saga для cabin, transfer или booking. Recovery
   должен принадлежать owning service.
10. Kafka как database/replay archive для UI. Durable state остаётся в БД
    владельца, события — at-least-once transport.
11. Прямой доступ клиентов к internal service URLs/MinIO/Valhalla.
12. «Автоматическое» создание фиктивного contractor или случайной бытовки ради
    заполнения пустого перегона.

## Things That Should Be Removed or Simplified

| Решение | Кандидат | Почему |
|---|---|---|
| REMOVE | Анонимная публикация planner API | P0, не имеет допустимого operator use case |
| REMOVE | Client-supplied `changed_by`/`local-admin` | Actor должен идти из authenticated principal |
| REMOVE | Legacy delivery-zone ownership склада | Дальность/цена — изохроны; оставить только запрет/no-trailer/special-price semantics |
| REMOVE | Initial-load «новые заявки» | Первый snapshot — baseline, не событие (`AUD-045`) |
| SIMPLIFY | Workspace mega-response | Bounded summary + detail queries/cursors |
| SIMPLIFY | 15-second full refresh | Read-only invalidation/pull; mutation — отдельный job/command |
| SIMPLIFY | Технические planner errors | Reason code → русский текст + действие; diagnostics только в log |
| SIMPLIFY | Plan/editor navigation | Одна day-plan workspace; simulation остаётся отдельным непубликуемым режимом |
| MERGE | Дублирующиеся plan/routes представления одной версии | Один plan version, разные projections, без отдельного lifecycle |
| AUTOMATE | Retirement отсутствующих feed requests | Только после contract tombstone/authoritative omission semantics |
| AUTOMATE | Recovery pending bookings | Lease/backoff/quarantine вместо ручного повторения |
| AUTOMATE | Contractor route pack | После явного назначения генерировать scoped task page/link |
| AUTOMATE | Exception prioritization | Показывать логисту SLA/capacity/publish failures, не весь шум |
| KEEP | Explicit warning/empty-leg confirmation | Это важное человеческое решение и audit point |
| KEEP | Locked manual assignments | Planner не должен уничтожать доказанное ручное решение |
| KEEP | Day simulation | Ценно при условии полной изоляции от publish/real demand |
| KEEP | Exact server-side slot recheck | Защищает от double booking |
| KEEP | Warehouse/source/origin/reposition separation | Критично для отчётности и физической истины |

## Top 15 Improvements

| # | Название | Проблема / решение | Business value | Complexity | Priority | Когда |
|---:|---|---|---|:---:|:---:|:---:|
| 1 | Закрыть planner security boundary | `AUD-001`: auth middleware, operator RBAC, warehouse grants, deny-by-default ingress | Исключает полный несанкционированный доступ | M | P1 | NOW |
| 2 | Immutable DayPlan revisions | `AUD-031/040/043`: plan versions + один published pointer + DB invariant + запрет draft apply | Нельзя потерять/дважды опубликовать день | L | P1 | NOW |
| 3 | Authoritative feed lifecycle | `AUD-002`: tombstone/revision/cursor и безопасное retirement stale projection | Исключает повторные/отменённые доставки | L | P1 | NOW |
| 4 | Изолировать test workload | `AUD-042/048`: отдельный tenant/environment и безопасный E2E fixture bridge | Тестирование без риска для real plans | M | P1 | NOW |
| 5 | Executable cross-warehouse trip | `AUD-020/035/036/052/054/059/061/062`: service/source/origin/support IDs, physical stops, execution scope и driver/vehicle placement | Реально запускает representative logistics | XL | P1 | NOW/NEXT |
| 6 | Завершить external carrier flow | `AUD-003/037/046/063`: date-free canonical profile, day AUTO/MANUAL assignment, typed catalog, scoped route page и delivery+pickup parity | Даёт работающий fallback без фиктивной машины | L | P1 | NOW/NEXT |
| 7 | Починить slot capacity degradation | Убрать hard 32-point all-or-nothing; support capacity только после feasible reservable plan | Не теряются продажи при загруженном дне | L | P1 | NOW |
| 8 | Центральный error catalog и честный command outcome | Stable code → русский UX + действие; local commit/remote publish показываются раздельно, raw detail только telemetry (`AUD-006/064`) | Логист понимает причину, факт сохранения и следующий шаг | M | P1 | NOW |
| 9 | Warehouse-local clock | Explicit warehouse ZoneId/Clock во frontend/backend/mobile; убрать Moscow/device defaults | Безопасный multi-region rollout | M | P1 | NOW |
| 10 | Recovery queue semantics | Lease, `nextAttemptAt`, backoff, terminal quarantine, metrics для bookings | Автовосстановление не блокируется poison row | M | P1 | NOW |
| 11 | Planner command и capacity publish consistency | Idempotency keys, expectedVersion, единый shift invariant, durable publish status/outbox и 409/retry semantics (`AUD-022/064`) | Нет дублей, lost updates и ложных failed save | M/L | P1 | NOW |
| 12 | Scalable reads and matrix decomposition | Pagination/batch lines, bounded workspace, clustering, geographic candidate partitions | Поддержка тысяч заявок без отказа | XL | P1 | NEXT |
| 13 | Authenticated immutable audit | Actor из JWT, plan lineage/audit отдельно от cascade-delete | Расследуемость и ответственность действий | M | P1 | NOW |
| 14 | Execution events + exception inbox | Actual arrival/departure/failure, SLA, planned-vs-actual, логист видит только исключения | Меньше ручного контроля, быстрее реакция | L | P2 | NEXT |
| 15 | Deterministic release gate | Stable clocks/timeouts, resource budgets/Testcontainers cleanup, release signing/install verification | Доверие к сборке и мобильному релизу | M | P1 | NOW |

## Technical Debt Register

| ID | Проблема | Модуль | Риск/последствия | Сложность | Приоритет | Тип |
|---|---|---|---|:---:|:---:|---|
| TD-001 | Planner без principal/RBAC | logistics backend/deploy | Полный обход доступа | M | P0 | SYSTEMIC PROBLEM |
| TD-002 | Нет feed tombstones/cursor | RWMS ↔ planner | Stale/cancelled demand | L | P1 | SYSTEMIC PROBLEM |
| TD-003 | Employment type erasure | driver directory adapter | Contractor в staff optimizer | S | P1 | QUICK WIN + contract guard |
| TD-004 | Неустойчивые mobile effect keys | CustomerApp | Duplicate reports/conflicts | M | P2 | SYSTEMIC PROBLEM |
| TD-005 | Несогласованный clock | planner/Android | Неверный день/смена/слот | M | P1 | SYSTEMIC PROBLEM |
| TD-006 | Raw error detail как UX | panel/mobile/FastAPI | Технические/английские ошибки | M | P2 | SYSTEMIC PROBLEM |
| TD-007 | Browser saga cabin/media | panel + asset/media | Partial resource creation | L | P1 | SYSTEMIC PROBLEM |
| TD-008 | Remote HTTP under locks | contractor handoff | Lock amplification/deadlock pressure | M | P2 | SYSTEMIC PROBLEM |
| TD-009 | Нет pagination + N+1 | logistics-service | Память/latency/DB load | M | P1 scale | QUICK WIN/M |
| TD-010 | Full N² route matrix | planner | Невозможны тысячи задач | XL | P1 scale | SYSTEMIC PROBLEM |
| TD-011 | Потеря inventory source | planning contract | Неверный склад/остатки/отчётность | L | P1 | SYSTEMIC PROBLEM |
| TD-012 | Catalog commands без fences | planner | Дубли/lost update | M | P1 | SYSTEMIC PROBLEM |
| TD-013 | Потерянные restriction zones | migrations/domain | Исправлено в WIP; историческая геометрия требует backup | L | P1 | SYSTEMIC PROBLEM |
| TD-014 | Mutating polling | planner UI/API | Нагрузка и непредсказуемые изменения | M | P1 | SYSTEMIC PROBLEM |
| TD-015 | Matrix hard cap закрывает slots | Customer capacity | Потеря доступных заказов | L | P1 | SYSTEMIC PROBLEM |
| TD-016 | Speculative representative capacity | Customer slot policy | Ложное обещание клиенту | L | P1 | SYSTEMIC PROBLEM |
| TD-017 | Нет one-active-plan invariant | planner DB/domain | Двойная публикация | L | P1 | SYSTEMIC PROBLEM |
| TD-018 | Audit actor spoofable | planner | Недостоверная история | S/M | P1 | QUICK WIN + migration |
| TD-019 | Support trip не исполним | planner/canonical publish | Cross-warehouse flow не работает | XL | P1 | SYSTEMIC PROBLEM |
| TD-020 | Request status не state machine | planner | Невозможные комбинации | L | P1 | SYSTEMIC PROBLEM |
| TD-021 | Destructive plan regeneration | planner sync/generator | Потеря plan/audit | M | P1 | SYSTEMIC PROBLEM |
| TD-022 | Contractor без delivery channel | task-board/planner | Назначение нельзя выполнить | L | P1 | SYSTEMIC PROBLEM |
| TD-023 | Recovery first-page starvation | logistics customer | Новые booking зависают | M | P1 | SYSTEMIC PROBLEM |
| TD-024 | Нет retention/archive policy | platform | Неконтролируемый рост/комплаенс | L | P2→P1 | SYSTEMIC PROBLEM |
| TD-025 | Нестабильные full gates | tests/CI | Красные релизы/скрытые regressions | M | P2 | QUICK WIN/M |
| TD-026 | Anonymous registration throttle внешний | auth/ingress | Account spam/CPU DoS | S/M | P1 | OPERATIONAL GATE |
| TD-027 | Cross-warehouse execution scope привязан к home JWT | auth/task-board/DriverApp | Support/repositioned водитель не получает задания destination | L | P1 | SYSTEMIC PROBLEM |
| TD-028 | Shift plan публикуется до assignment validation | planner ↔ task-board | Пустая frozen смена и ложная trip capacity | L | P1 | SYSTEMIC PROBLEM |
| TD-029 | Vehicle reposition не имеет owner/effect | logistics/task-board/planner | Ложное базирование и double booking автомобиля | L | P1 | SYSTEMIC PROBLEM |
| TD-030 | Групповой candidate search асимметричен | planner | Игнорируются support/local representative resources | L | P1 | SYSTEMIC PROBLEM |
| TD-031 | Panel contractor DTO отстал от canonical contract | panel/task-board | Ложный create failure и скрытые дубли профилей | S/M | P1 | QUICK WIN + contract test |
| TD-032 | Capacity shift invariant расходится, publish идёт после local commit без явного outcome | planner/logistics-service/CustomerApp | HTTP 400, stale slots/prices и ложная ошибка сохранения | M/L | P1 | SYSTEMIC PROBLEM + contract quick win |

## Strong Parts of the Current System

1. Ясные service ownership boundaries и отдельная БД каждого stateful Spring
   сервиса.
2. Canonical OpenAPI/event contracts находятся отдельно от persistence models.
3. Warehouse aggregate с canonical IANA timezone/history, optimistic version и
   lifecycle readiness.
4. Representative warehouse как boolean characteristic и направленная
   many-to-many support network, без дублирующего типа.
5. Transfer custody: concrete cabin reservation → departure/in-transit →
   arrival, с versions и idempotent reconciliation.
6. Customer final slot transaction с advisory/row locks и повторным capacity
   fingerprint.
7. RentalOrder и DriverShift — хорошо выраженные state machines.
8. Transactional outbox/inbox, deduplication, aggregate ordering и version-gap
   quarantine в Spring-контуре.
9. DriverApp/WorkerApp offline-first foundation и evidence recovery.
10. Private MinIO, owner proofs, subject-bound media authorization и отсутствие
    storage coordinates в public API.
11. Exact road routing/truck profiles, а не прямая линия или изохрона как
    финальное доказательство маршрута.
12. Leg/stop load transitions, hard windows, break/shift/depot return validation.
13. Deterministic planner, structured reason codes и manual locks вместо
    непрозрачного black box.
14. Side-effect-free simulation и полезные route explanations.
15. Server-side pricing и запрет доверять клиентской итоговой стоимости.
16. Role-isolated DriverApp/WorkerApp surfaces и fail-closed command policy.

Эти решения нужно сохранить при исправлении P0/P1; переписывание Spring-ядра
или мобильного offline слоя с нуля было бы ошибкой.

## Итоговая оценка архитектуры

| Область | Оценка | Объяснение |
|---|:---:|---|
| Domain model | 6.5/10 | Сильные Warehouse/Order/Transfer/Shift; слабые planner Request/Plan и incomplete source/origin semantics |
| Backend architecture | 7/10 canonical, 4/10 planner | Spring ownership/outbox зрелые; standalone sidecar нарушает security/read/lifecycle boundaries |
| Frontend architecture | 6/10 | Panel структурирован, но raw errors/browser saga; planner workspace слишком stateful и polling-heavy |
| Mobile architecture | 8/10 Driver/Worker, 6.5/10 Customer | Отличный offline execution; timezone/error/cancel gaps |
| API design | 6/10 | Canonical contracts/version fences хороши; planner API, SSE и unpaged reads снижают оценку |
| Database design | 7/10 | Flyway/checks/versions/eventing сильны; active-plan uniqueness, retry queue и retention отсутствуют |
| Reliability | 6/10 | Outbox/offline/slot fence сильны, но destructive planner flows и starvation дают P1 |
| Scalability | 4/10 | N² matrix, unbounded payloads/N+1/DOM markers не выдержат заявленный объём |
| Observability | 6/10 | Reason codes и infrastructure metrics есть; нет end-to-end planning/execution trace и poison metrics |
| Security | 4/10 overall | Канонические сервисы защищены хорошо, но один публичный P0 planner доминирует над средней оценкой |
| Testability | 7/10 | Очень широкие suites и integration tests; full gates nondeterministic/resource-sensitive |
| UX логиста | 6/10 | Карта, reasons, simulation полезны; publish/contractor/notification/error gaps разрушают доверие |
| UX водителя | 7.5/10 | Чёткие state/actions/offline/photo; не хватает warehouse clock и live ETA/exception loop |
| UX клиента | 6.5/10 | Хороший booking flow и server slots; нет cancel/reschedule, hard capacity failure непрозрачен |
| Logistics automation | 6/10 | Хороший городской heuristic и explainability; cross-warehouse execution и live replanning незавершены |

## Recommended Architecture Direction

1. Сохранить canonical владельцев: warehouse identity — warehouse-service,
   inventory/cabins — asset-service, коммерческий order/transfer —
   logistics-service, execution/shift — task-board.
2. Рассматривать `logistics/` как planning application, а не отдельный источник
   бизнес-истины. Его DB — versioned projection/candidate store.
3. Ввести явный projection protocol: source revision/cursor, upsert+tombstone,
   last successful watermark и recovery, без mutation в GET workspace.
4. Сделать DayPlan immutable revision aggregate: draft revisions можно
   пересчитывать; publish атомарно меняет единственный pointer; прошлые версии и
   audit не удаляются.
5. Добавить canonical `TripPlan` publish contract с route origin, service
   warehouse, inventory source, support link, driver assignment type,
   vehicle/trailer и ordered physical stops/load events. Включить временный
   execution scope водителя и отдельные driver/vehicle operational assignments.
6. Финальная publish validation выполняется владельцами ресурсов с versions и
   idempotency; producer заранее применяет те же canonical invariants, а
   частичные outcomes либо компенсируются, либо становятся durable
   reconciliation state. Их нельзя маскировать ни общим 200, ни общим failed
   save после уже выполненного local commit (`AUD-064`).
7. Contractor остаётся отдельным assignment type и получает минимальную
   scoped/revocable execution surface, не фальшивую штатную смену.
8. Клиентские точные слоты открываются только под atomic reservable plan;
   потенциальный support/contractor даёт `requiresConfirmation/day`, не гарантию.
9. Actual execution events (departed/arrived/failed/evidence) формируют
   planned-vs-actual и exception inbox; только после этого добавлять dynamic ETA.
10. UI становится exception-oriented dispatch console: обычный план строится
    автоматически, логист принимает решения только по отклонениям, пустому
    пробегу, contractor и физическому source cargo.

## Roadmap

### NOW — до реального использования

1. Закрыть `AUD-001`; проверить внешний registration throttle (`AUD-049`).
2. Запретить draft apply и destructive deletion confirmed/audit; обеспечить
   one published plan (`AUD-031/040/043`).
3. Изолировать generator от real plans (`AUD-042`).
4. Исправить stale feed lifecycle и request delete guard (`AUD-002/041`).
5. Устранить contractor type erasure и либо закрыть незавершённый handoff, либо
   дать исполнителю поддержанный канал; синхронизировать date-free profile UI с
   canonical contract (`AUD-003/046/063`).
6. Проверить импорт исторических exceptional polygons из доступной резервной
   копии; runtime semantics уже восстановлены в WIP (`AUD-025`).
7. Устранить ложные representative slots и matrix all-or-nothing (`AUD-028/029`).
8. Не обещать cross-warehouse execution до устранения `AUD-059/060/061/062`:
   связать задачи destination со scoped principal, финализировать shift только
   после assignment и реализовать vehicle placement.
9. Ввести warehouse-local clock и безопасный error catalog.
10. Синхронизировать shift invariant и сделать capacity publication честным
    durable workflow с retry/partial-success UX (`AUD-064`).
11. Исправить booking recovery starvation и client effect keys.
12. Сделать release/test gates детерминированными и доказать Android signing.

### NEXT — после стабилизации

1. Исполнимый cross-warehouse trip + inventory source + concrete cabins +
   driver/vehicle operational execution scope.
2. Bounded/paginated APIs, batch lines, map clustering и matrix decomposition.
3. Immutable plan lineage и authenticated audit UI.
4. Actual execution events, SLA/exceptions и planned-vs-actual.
5. Customer cancel/reschedule и failed-delivery workflow.
6. Production customer notifications и scoped contractor page.
7. Vehicle maintenance constraints и capacity forecast.

### LATER — при росте

1. Dynamic ETA и event-driven remaining-day replanning.
2. Carrier cost/rating после накопления actual данных.
3. Historical travel/service-time calibration.
4. Demand heatmap и scenario planning на уровне сети складов.
5. Retention/partition/archive automation и аналитические read models.
6. Geofencing только после privacy/consent/retention дизайна.

## Verification Verdict

- Проверены backend planner, planner frontend, logistics-service,
  task-board-service, panel, CustomerApp, DriverApp, WorkerApp и ManagerApp.
- Большинство тестов зелёные; точные команды и числа находятся в разделе
  «Тесты и проверки, уже выполненные» выше.
- Подтверждённые красные full gates не скрывались и не отключались:
  planner 2 failures/6 external skips, planner UI 2 failures, logistics-service
  2 order/environment-sensitive failures, task-board resource exhaustion,
  panel 6 timeout failures при зелёном focused rerun 48/48.
- Ruff, strict mypy, panel typecheck/lint, четыре Android JDK17 test gates и
  focused transfer tests прошли.
- Production Valhalla synthetic graph, authenticated VPS E2E, external ingress
  throttling и release APK install/signing **не подтверждены — требуется
  дополнительная проверка**.

# Если бы я стал тимлидом этого проекта завтра

1. Немедленно закрыл бы публичный planner и ввёл security regression gate.
2. Заморозил бы новые logistics features до исправления plan publish/lifecycle
   и test workload isolation.
3. Назначил бы одного владельца end-to-end DayPlan contract и запретил
   browser/sidecar трактовать projection как canonical факт.
4. Утвердил бы immutable plan revision + one published pointer как основной
   архитектурный инвариант.
5. Завершил бы один вертикальный cross-warehouse сценарий до DriverApp с
   конкретной бытовкой, прежде чем расширять optimizer.
6. Закрыл бы contractor flow либо честно пометил его unavailable; ложное
   «передано» хуже отсутствующей кнопки.
7. Ввёл бы единый error catalog и correlation/planning IDs во всех клиентах.
8. Сделал бы clocks, Testcontainers budgets и полные CI gates
   детерминированными; flaky red не принимал бы за норму.
9. Поставил бы SLO/метрики на pending recovery, publish rejection, unassigned
   reasons и outbox age.
10. После стабилизации перевёл бы UX логиста на exception inbox и
    planned-vs-actual, сохранив текущие deterministic reasons, simulation и
    ручные locks.
