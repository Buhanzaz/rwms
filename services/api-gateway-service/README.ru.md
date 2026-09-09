# API Gateway RWMS

[English version](README.md)

`api-gateway-service` — публичная статeless-граница RWMS. Он даёт браузеру и
мобильным приложениям одну стабильную точку входа для OIDC и публичных API
сервисов, не раскрывая внутреннюю сеть сервисов.

Gateway намеренно является транспортной границей, а не бизнес-сервисом. Он не
владеет базой данных, JPA-моделью, миграциями схемы, клиентом Kafka/RabbitMQ,
хранилищем токенов, OAuth-клиентом, workflow, кэшем или бизнес-правилами.

## Зачем он нужен

Без единой границы каждый клиент должен был бы знать адрес каждого внутреннего
сервиса, детали аутентификации и сетевые правила. Тогда релиз клиента напрямую
зависит от топологии развёртывания, а перенос, разделение или защита сервиса
становятся дороже.

Gateway решает действительно общие задачи публичной границы:

| Проблема | Ответственность gateway | Результат |
| --- | --- | --- |
| Клиенты не должны обращаться к приватным хостам сервисов | Публикует небольшой allow-list маршрутов | Внутренняя топология остаётся закрытой, а сервисы можно переносить независимо. |
| Публичным вызовам нужна единая первичная защита | Локально валидирует Bearer JWT и применяет edge-политику маршрутов | Неаутентифицированный запрос или запрос к приватному пути отсекается до попадания в сервис. |
| Браузеру нужен единый same-origin API | Обслуживает публичные пути `/auth/**` и `/api/**` | В panel нет внутренних адресов сервисов или конфигурации вида `localhost:<port>`. |
| Прокси-заголовки могут быть подделаны | Удаляет входящие `Forwarded` и `X-Forwarded-*`, а канонические OIDC-метаданные строит только из конфигурации | Клиент не может выбрать OIDC host, scheme, port или префикс `/auth`. |
| Сбой downstream-сервиса должен быть понятен клиенту | Преобразует ошибки соединения и таймауты в RWMS Problem Details | Клиент получает безопасный и единообразный `502` или `504` без внутренних адресов и текста исключения. |
| Долгие потоки и предсказуемо медленные операции отличаются от обычного HTTP | Использует отдельные ограниченные обработчики для SSE, байтов загрузки, commit HTML-импорта, пересчёта результата инвентаризации и turns ассистента | Обычный proxy timeout не обрывает поддерживаемый долгий запрос. |
| Нужна наблюдаемость общей точки входа | Предоставляет health/readiness, Prometheus, tracing и correlation ID | Доступность и путь запроса можно расследовать без логирования учётных данных и payload. |

При этом доменный сервис по-прежнему владеет смыслом API, бизнес-авторизацией,
переходами состояний и данными. Канонические OpenAPI- и event-контракты остаются
источником истины для этих задач.

## Чего gateway не делает

Эти ограничения — осознанные архитектурные решения:

- Он не объединяет ответы нескольких сервисов в новый бизнес-ответ. Клиент
  может объединить независимые публичные чтения; оркестрацией, которой нужна
  согласованность, владеет доменный сервис.
- Он не исполняет команды, не запускает саги, не вычисляет бизнес-состояние и
  не хранит состояние workflow.
- Он не хранит токены и не является OAuth-клиентом. Как resource server он
  валидирует JWT через JWKS приватного `auth-service`.
- Он не проксирует внутренние межсервисные вызовы. Для них используются
  приватные адреса и сервисные учётные данные.
- Он не делает Kafka, общий кэш или базу данных неявной зависимостью gateway.

Именно узкая зона ответственности делает gateway заменяемым, горизонтально
масштабируемым и безопасным в эксплуатации. Так на сетевой границе не возникает
второй скрытый монолит.

## Как проходит запрос

1. Panel, manager app, WorkerApp, DriverApp или внешний публичный клиент обращается к
   настроенному публичному хосту по `/auth/**` или `/api/**`.
2. Gateway валидирует host и удаляет forwarding-метаданные, пришедшие от
   клиента. Для OIDC relay он восстанавливает публичные значения только из
   своей конфигурации.
3. Spring Security применяет edge-политику: приватные namespace запрещены,
   явно публичные пути разрешены, поток задач worker требует отдельного scope,
   остальные публичные API требуют валидный Bearer JWT.
4. Таблица маршрутов принимает только известный публичный префикс и отклоняет
   опасные формы пути: traversal и закодированные slash/backslash-варианты.
5. Gateway пересылает запрос на настроенный приватный target. Из доменных API
   relay удаляются cookies; OIDC-маршруты сохраняют поведение, нужное
   authorization flow.
6. Запрос исполняет сервис-владелец. Его ответ остаётся доменным ответом —
   gateway не превращает его в агрегированный результат.
7. Если target недоступен или истёк транспортный deadline, gateway возвращает
   безопасный RWMS Problem Details. В остальных случаях он передаёт ответ
   downstream-сервиса.

```text
Публичный клиент
    |
    v
public /auth/** или /api/**
    |
    v
API gateway: проверка host/headers, CORS, JWT, route policy, observability
    |
    +-- private auth-service
    +-- private domain services
    |
    v
Сервис-владелец: бизнес-авторизация, инвариант, транзакция, данные
```

## Политика публичных маршрутов

Исполняемая таблица маршрутов находится в
[`GatewayRouteConfiguration`](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteConfiguration.java).
Таблица ниже описывает публичную форму API, но не заменяет OpenAPI-контракт
сервиса-владельца.

| Публичная точка входа | Приватный target / обработка пути | Важное ограничение |
| --- | --- | --- |
| `/auth/**` | `auth-service`; `/auth` удаляется перед пересылкой | `/auth/callback` принадлежит panel; `/auth/api/internal/**` никогда не пересылается. CSRF bootstrap/registration клиента остаются анонимными на stateless edge и после forwarding защищаются в auth-service парой cookie+header. |
| `/api/task-board/**` | `task-board-service`; внешний префикс меняется на downstream `/api/**` | Internal-пути запрещены. Потоки событий WorkerApp и DriverApp имеют отдельные SSE-маршруты и точные scopes. |
| `/api/warehouse/**` | настроенный target `warehouse-service`, путь без изменений | Internal-пути запрещены; текущий маршрут зарезервирован для warehouse API W1. |
| `/api/asset/**` | `asset-service`, путь без изменений | Internal-пути запрещены. HTML-import commit обслуживает отдельный handler. |
| `/api/maintenance/**` | `maintenance-service`, путь без изменений | Internal-пути запрещены. |
| `/api/cad/v1/**` | optional target `CAD_SERVICE_URL`, путь без изменений | Точный `POST /invitations/accept` анонимен, поскольку authority задаёт подписанная capability. Остальные маршруты требуют либо проверенный Bearer JWT, либо заголовок строгого формата `CadGuest projectId.invitationId.token`; cad-service на каждом запросе проверяет capability, membership и отзыв. `/api/cad/internal/**` и `/api/cad/private/**` запрещены. |
| `/api/media/**` | `media-service`, путь без изменений | Internal- и private-пути запрещены. Source/variant upload content и SSE используют отдельные handlers. |
| `/api/inventory/**` | `inventory-service`, путь без изменений | Internal- и private-пути запрещены. Пересчёт завершённого результата использует отдельный handler с тайм-аутом 60 секунд; все остальные inventory-запросы сохраняют обычный тайм-аут. |
| `/api/logistics/**` | `logistics-service`, путь без изменений | Internal- и private-пути запрещены. Анонимно доступны точные операции client-presentation, cabin-photo-presentation и contractor-route capability, а также четыре GET-шаблона гостевого каталога ниже. Профиль, корзина и заказы клиента требуют аутентификации; logistics проверяет точную комбинацию CUSTOMER/client/scope. |
| `/api/assistant/**` | `assistant-service`, путь без изменений | Internal- и private-пути запрещены. Turns диалога использует отдельный streaming handler. |
| `/api/dossier/**` | `dossier-service`, путь без изменений | Только `GET`: dossier является read-проекцией и не имеет публичного command route. |
| `/api/analytics/v1/**` | `analytics-service`; внешний префикс меняется на downstream `/api/v1/**` | Только аутентифицированный `GET`. |

Gateway остаётся stateless и не хранит source-address rate-limit state. Он
удаляет forwarding headers вызывающей стороны и передаёт auth-service
проверенный числовой адрес клиента. `GATEWAY_TRUSTED_PROXY_ADDRESSES` задаёт
доверенные адреса непосредственного proxy (по умолчанию `127.0.0.1,::1` для
текущего локального Nginx). Такой proxy обязан перезаписывать `X-Real-IP`
наблюдаемым адресом клиента; текущая конфигурация Nginx это делает. Gateway
принимает ровно один IP literal, нормализует эквивалентные формы IPv6 и
отклоняет неверные или множественные значения с 400. При отсутствии заголовка
или недоверенном peer используется TCP-адрес; клиентские `Forwarded` и
`X-Forwarded-*` не определяют identity. Долговечные per-source и глобальный
budgets регистрации принадлежат auth-service. Throttling на production ingress
остаётся дополнительным слоем защиты. CSRF-защита предотвращает cross-site
submission, но не является abuse throttling.

Специальные маршруты намеренно имеют приоритет над общими маршрутами сервиса:

- Гостевой просмотр разрешает без входа только GET для
  `/api/logistics/public/v1/catalog/warehouses`, `/{warehouseId}/facets`,
  `/{warehouseId}/cabins` и `/{warehouseId}/cabins/{cabinId}/photos/{mediaId}`
  под этим префиксом складов. Logistics проверяет видимость склада, доступность,
  цены и доступ к производным фотографиям. Другие пути и методы каталога требуют
  аутентификации; клиентские команды и namespace media не открываются.
- `GET /api/task-board/worker/v1/events`, `GET /api/task-board/driver/v1/events`,
  `GET /api/asset/v1/events` и `GET /api/media/v1/events` проходят через
  ограниченный асинхронный SSE proxy.
- `POST /api/asset/v1/html-imports/*/commit` и
  оба `PUT /api/media/v1/upload-sessions/*/content` и
  `PUT /api/media/v1/upload-sessions/*/variants/*/content` используют
  специальные ограниченные прокси для поддерживаемого долгого трафика. Пути,
  bearer/idempotency headers, content bytes и downstream responses проходят без
  изменений; cookies удаляются, адреса и credentials MinIO не раскрываются.
- `POST /api/inventory/v1/sessions/*/outcome/recalculate` использует отдельный
  downstream read deadline 60 секунд. Точная команда исключена из общего
  inventory-маршрута; её путь, заголовки авторизации и идемпотентности
  пересылаются без изменений, а cookies удаляются.
- `/api/cad/v1/**` использует отдельный streaming proxy с downstream read
  deadline 45 секунд. Он сохраняет public path, Bearer и idempotency headers,
  передаёт поддерживаемые request bodies без application aggregation и удаляет
  cookies. Настроенный target optional; локальный fallback не создаётся.
- `POST /api/assistant/v1/conversations/*/turns` использует streaming proxy
  ассистента, поэтому корректный потоковый ответ не наследует обычный read
  deadline.
- Точные contractor-route capability paths анонимно разрешают чтение маршрута,
  команды этапов, ограниченную загрузку evidence и scoped-чтение изображений.
  Они удаляют cookies и authorization, сохраняют idempotency, checksum и время
  съёмки evidence и не открывают более широкое поддерево
  `/api/logistics/public/**`. До проксирования evidence bytes точный servlet
  boundary требует объявленный размер и принимает только JPEG до 15 МиБ или
  WebP до 1 МиБ. Поскольку MVC proxy использует downstream chunked transfer,
  gateway заменяет любое присланное клиентом private relay-значение своим
  проверенным размером; logistics требует это утверждение и повторяет проверку
  фактического тела и checksum.

Endpoint добавляется в эту таблицу только после того, как определены его
публичный контракт и сервис-владелец. Нельзя открыть `/internal/**` или
`/private/**` только потому, что клиенту нужна возможность: сервис-владелец
должен определить для неё публичный API.

## Граница безопасности и доверия

[`GatewaySecurityConfiguration`](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewaySecurityConfiguration.java)
реализует следующую политику:

- Сервис stateless: сессии, request cache и CSRF-state не используются как
  замена токеновой защите API.
- Локально проверяются timestamp, issuer и audience JWT. JWKS берётся с
  приватного target `auth-service`, а не через видимый браузеру gateway route.
- По умолчанию `/api/**` требует аутентификацию. Приватные маршруты, включая
  CAD `/api/cad/internal/**` и `/api/cad/private/**`, явно запрещены; публичные
  OIDC, health, Android App Links и документированные подписанные logistics
  capability operations — узкие исключения.
- Namespace WorkerApp требует ровно `SCOPE_worker.tasks`, а namespace DriverApp —
  ровно `SCOPE_driver.tasks`; токен одного native-клиента не проходит в поверхность
  другого. Доменные сервисы всё равно принимают собственные решения авторизации.
- CORS использует явный allow-list origin, явный набор методов/заголовков и
  credentials там, где они нужны. Wildcard origin отклоняется startup-проверкой.
- Production-проверка применяет одну policy ко всем настроенным downstream:
  каждый target является HTTP(S) origin без path, не может повторять публичный
  host gateway и не может использовать localhost или loopback-адрес. Публичные
  значения обязаны использовать HTTPS.

Это разделение принципиально: edge аутентифицирует и защищает публичную
поверхность, а сервис-владелец авторизует бизнес-операцию. Если перенести всю
доменную авторизацию в gateway, появятся две конкурирующие реализации политики,
которые будет трудно безопасно развивать.

## Streaming, ошибки и наблюдаемость

Таймаут ожидания SSE-заголовков отменяет исходящий HTTP exchange и возвращает `504`.
У ответа, пришедшего после таймаута или отмены, отменяется подписка на тело;
слот соединения освобождается однократно и доступен следующему подключению.

Для gateway SSE — только транспорт. Он ограничивает число одновременных
потоков, использует асинхронный I/O, фильтрует forwarding-заголовки и отменяет
upstream-подписку при отключении клиента. Смысл события, replay, порядок и
состояние проекции остаются у сервиса-производителя.

Для обычных proxy-вызовов ошибки соединения переводятся в общий формат Problem
Details: недоступный target становится `502 Bad Gateway`, timeout —
`504 Gateway Timeout`. В ответ намеренно не попадают upstream URL, текст
исключения и тело запроса.

Optional CAD route возвращает `503 Service Unavailable` со стабильным кодом
RWMS Problem Details, когда `CAD_SERVICE_URL` отсутствует. Он не раскрывает
default backend или configuration value.

Gateway предоставляет:

- `/actuator/health/liveness` и `/actuator/health/readiness` как публичные
  transport-level health probes. Они не проксируются в downstream-сервисы.
- `/actuator/prometheus` для аутентифицированного RWMS Bearer JWT scraping.
- Micrometer/OpenTelemetry tracing со стандартным W3C HTTP trace context и
  `X-Correlation-Id` как отдельным RWMS correlation contract.
- ECS JSON-логи без пользовательских полей для request headers, Bearer tokens,
  payload, entity IDs и PII.

## Почему стоит делать именно так

| Альтернатива | Почему это создаёт проблему | Выбранный подход |
| --- | --- | --- |
| Разрешить клиентам ходить в каждый сервис напрямую | Раскрывает топологию, расширяет публичную поверхность атаки и привязывает релиз к адресам сервисов. | Одна стабильная публичная точка входа с приватными upstream target. |
| Превратить gateway в business aggregator | Появляется междоменная связанность и скрытый владелец workflow, retry и failure-сценариев. | Оставить его transport-only; бизнес-оркестрация принадлежит доменному сервису-владельцу. |
| Доверять `Forwarded` headers от клиента или произвольного ingress | Вызывающий может влиять на построение public URL и нарушить OIDC-предпосылки. | Удалять эти заголовки и строить канонические значения из валидированной конфигурации. |
| Применять одну generic proxy-политику ко всему трафику | У SSE, upload и streaming-ответа ассистента разные время жизни и потребление ресурсов. | Направлять известный долгий трафик через узкие ограниченные специализированные handlers. |
| Делать авторизацию только на edge | Приватный вызов может обойти gateway, а доменные правила начнут расходиться с владельцем агрегата. | Здесь — грубая публичная edge-политика, в сервисе-владельце — бизнес-авторизация. |
| Делать gateway stateful | Появляются sticky sessions, состояние восстановления и БД у самого открытого компонента. | Сохранять stateless-подход и масштабировать gateway независимо. |

Цель не в том, чтобы добавить на edge больше логики. Цель — сделать
предсказуемую и защищённую публичную границу, поведение которой можно менять
независимо от доменного состояния.

## Локальная разработка

Видимый браузеру issuer — это origin Vite, потому что Vite проксирует `/auth`
в gateway. Запустите и `auth-service`, и `task-board-service` с одним явным
issuer, чтобы authorization server выпускал, а downstream resource server
принимал browser-visible значение:

```powershell
$env:AUTH_ISSUER = "http://localhost:8080/auth"
```

URI client-credentials и worker-credentials в task-board должны оставаться
прямыми внутренними URI auth (`http://localhost:9000`): эти вызовы не проходят
через gateway. JWKS gateway также выводит из этого приватного target, а не из
публичного `/auth`. После этого запустите модуль с профилем `dev`, а затем Vite:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
.\gradlew.bat :services:api-gateway-service:bootRun
```

Dev-defaults направляют auth на `9000`, task-board на `8081`, резервируют
warehouse target на `8083`, получают JWKS с внутреннего auth target на `9000`
и поднимают gateway на `8088`. Запускайте panel/Vite последним на `8080`: он
проксирует `/auth` и `/api` на `8088`, сохраняя `/auth/callback` в SPA. В
dev CAD намеренно не имеет default: когда local CAD server запущен, явно
задайте `CAD_SERVICE_URL=http://127.0.0.1:8097`. Его путь сохраняется, а proxy
передаёт contract-supported bodies потоково; production ingress также должен
принимать максимальный body size CAD contract до того, как запрос дойдёт до
gateway. В production нет localhost- или secret-bearing-defaults: должны быть
заданы все настроенные target, public issuer/base URI и разрешённый panel
origin.

## Исполняемый parity маршрутов и безопасности

[`GatewayRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteSecurityParityTest.java)
разбирает каждый канонический service OpenAPI и исполняет реальные упорядоченные router functions.
Его domain-инвентарь содержит каждую каноническую публичную операцию `/api/**`; операции auth-service,
делегированные под `/auth/**`, и локальные для media-service probes `/health/**` образуют явные
отдельные разделы. Gate проверяет owner-specific rewrite путей task-board и analytics, приоритет
специализированных SSE/upload/import/inventory-recalculation/assistant routes, однозначное исключение
команды пересчёта из общего inventory-маршрута, нулевую маршрутизацию каждой канонической
internal-операции и зарезервированного private/internal alias, а также реальную edge security
classification. Все публичные domain-операции требуют Bearer-аутентификацию, кроме явно
классифицированных подписанных logistics capability operations. Отдельный auth-раздел проверяет и Bearer, и
точное OpenAPI-требование `csrfCookie + csrfHeader`; CSRF остаётся проверкой auth-service и не
дублирует state в gateway.

Запуск focused gate из корня репозитория:

```bash
bash ./gradlew :services:api-gateway-service:test --tests 'dev.buhanzaz.rwms.gateway.config.GatewayRouteSecurityParityTest'
```

## Правила безопасного изменения

При изменении gateway:

1. Начинайте с канонического OpenAPI-контракта сервиса-владельца. Не создавайте
   gateway-only публичную операцию.
2. Добавляйте или меняйте явный public route и покрывайте focused gateway
   tests его положительный сценарий и запрет private path.
3. Сохраняйте разделение public/private, локальную JWT-валидацию, correlation
   и Problem Details.
4. Не переносите в этот модуль доменные команды, агрегацию, persistence, Kafka
   или бизнес-решения о retry.
5. Для SSE или долгого маршрута определяйте владение replay и recovery в
   контракте producer-сервиса: gateway может транслировать поток, но не хранит
   его состояние.

Полезные команды проверки из корня репозитория:

```bash
bash ./gradlew :services:api-gateway-service:test
bash ./gradlew :services:api-gateway-service:javadoc
```

## Основные исходные материалы

- [Таблица маршрутов](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteConfiguration.java)
- [Безопасность edge и CORS](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewaySecurityConfiguration.java)
- [Проверки безопасности production-конфигурации](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayProductionSafetyValidator.java)
- [Обработка Forwarded headers и public host](src/main/java/dev/buhanzaz/rwms/gateway/config/GatewayWebConfiguration.java)
- [Транспортный SSE handler](src/main/java/dev/buhanzaz/rwms/gateway/config/SseProxyHandler.java)
- [Архитектурная граница gateway](../../docs/project-knowledge/architecture.md)
- [Владение контрактами и порядок их изменения](../../docs/project-knowledge/contracts.md)
