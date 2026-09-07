# Сервис assistant RWMS

[English version](README.md)

assistant-service владеет stateful rental conversations, messages, tool-call history и своим booking-event inbox. Rental inquiries, short cabin selections и availability остаются в собственности logistics-service. Assistant не является вторым владельцем rental domain и не читает БД logistics.

## Назначение и граница ответственности

Public assistant API позволяет пользователю отдельного web- или Android-приложения менеджера аренды начать или продолжить conversation и получить streamed tool-assisted response. Сервис сохраняет user message, provider/tool interaction и final assistant message, поэтому последующее чтение conversation использует server-authoritative history.

По умолчанию каждая попытка запроса к провайдеру ограничена общим deadline 60 секунд, включая чтение тела,
и паузой между SSE-строками до 15 секунд. Таймаут или отмена turn закрывает тело ответа и отменяет
reader. Действующий повтор разрешён только до получения provider output. Timeout, error и completion
клиентского SSE отменяют незавершённую работу; совершённые tool effects и сохранённая история
остаются, а прерванный turn не сохраняет успешный ответ ассистента.

Сервис намеренно не:

- принимает решение о cabin availability, не публикует rental inquiry и не изменяет logistics aggregate локально;
- не предоставляет browser-owned saga или mock result, если logistics или provider недоступны;
- не позволяет LLM выполнять произвольные HTTP, SQL, files или commands; и
- не принимает direct public client request вне gateway.

Канонический API — [assistant-service.yaml](../../contracts/openapi/assistant-service.yaml).
Факт завершения booking объявлен в
[logistics-events.yaml](../../contracts/events/logistics-events.yaml).

## Поток запроса, tools и booking

    public gateway /api/assistant/**
            |
            v
    exact rental-manager client/scope + signed manager subject
            -> owner-scoped conversation/message persistence
            |
            +--> private logistics REST с current user bearer
            |        -> exact cabin facets/searches и rental inquiry state
            |
            +--> configured OpenAI-compatible LLM provider
                     -> bounded tool allow-list -> persisted tool result

    logistics.rental-inquiry.booked.v1
            |
            v
    local event inbox -> archive только matching local conversation

Executor является тонким dispatcher над отдельными facet/search, reference,
clarification и selection collaborators. Он принимает только документированные
tools, валидирует arguments, отклоняет unsupported result fields и преобразует
upstream failures в safe tool outcomes. Tool results и completed conversation
history передаются configured provider для последующих turns; считайте
sanitization tool payloads и provider data handling production boundary, а не
browser presentation data.

Caller-supplied conversation ID — stable idempotency key, который пересылается
при создании delegated rental inquiry. ID optional в public contract. Local
preflight и finalization используют отдельные короткие transactions; remote
call выполняется без assistant database transaction или advisory lock.
Finalization lock создаёт либо проверяет одну local row при гонке same-key
calls. Replay с `newClient` повторно проверяет idempotent remote result, а
replay с existing client может вернуть уже проверенную local row. Remote
success и local persistence failure не образуют одну atomic cross-service
transaction, поэтому recovery использует тот же conversation ID, а не новый
key.

Optional `rentalOrderId` связывает conversation с существующим заказом и
требует его existing `clientId`. Assistant-service передаёт обе identity в
logistics-service и не дублирует принадлежащие logistics проверки editable
order, client или warehouse. `GET /api/assistant/v1/conversations?rentalOrderId=...`
возвращает owner-scoped active link. Partial unique database index допускает
только одну non-archived conversation для заказа: active conversation
переоткрывается, а после archive можно создать новую связанную conversation.
Local order-scoped finalization lock сводит concurrent creates к этой одной
active link. Перед повторным использованием link через order-filtered list или
create assistant-service читает в logistics точный inquiry/client/order context
вне local transaction. `ACTIVE` можно переиспользовать; `BOOKED` или `ARCHIVED`
архивирует только эту точную local conversation под существующим order lock,
после чего create может открыть новый linked inquiry, а list не возвращает
terminal chat. Другой winner, обнаруженный под lock, повторно проверяется уже
вне transaction; bounded churn, неизвестное состояние, identity mismatch или
dependency failure завершаются fail-closed. Unfiltered history list выполняет
один local query без reconciliation каждой row через logistics.

Для cabin search уже сохранённый `AssistantToolCall.id` является durable attempt
root. Direct search использует этот UUID; каждый bounded logical probe и final
selection получают отдельный deterministic UUID из того же root и stable step
name. `HttpLogisticsClient` никогда не создаёт search key и повторяет только
один lost-response `IOException` с byte-identical request и caller key. Новый
экземпляр client может повторно использовать переданный durable key, но
assistant не сохраняет Bearer token и не выполняет automatic post-restart
search resumption.

## Интерактивный выбор и справочная система бытовок

Availability result невозможен, пока каждая group не содержит точные текущие
`cabinType` и `finish`. Current logistics facets также содержат точные
type-to-dimension relations. Один связанный размер выбирается
детерминированно; несколько связанных размеров создают buttons без search или
hold. Фраза «6 метров» преобразуется только в точный facet, эквивалентный
`6x2.4` и связанный с выбранным type. Если type отсутствует, type buttons
содержат только совместимые с размером варианты; несвязанные modules или posts
охраны не придумываются.

Clarification questions имеют стабильные question и option identities и
durable sequence на всю conversation. Один batch может содержать несколько
questions, но actionable и видим только его старейший `PENDING` head; следующие
`QUEUED` questions скрыты. Button turn принимает только сохранённые
`questionId` и `optionId` head-вопроса. Ответ на промежуточный head активирует и
отправляет следующий question без вызова provider; provider/tool execution
возобновляется ровно один раз после последнего ответа batch. Answered и
superseded history остаётся reloadable в порядке sequence. Видимое сохранённое
user message содержит вопрос и выбранный label, а не технический branch key.

`lookup_cabin_catalog` — отдельный read-only help path. Он объясняет текущие
relations type, finish, characteristics и sizes либо запрашивает одну bounded
warehouse-scoped fact page по точному number/text, включая linoleum, без
availability search или hold. После успешного search `filterSuggestions`
содержит только значения из current warehouse facets.

Missing или null search `resultMode` означает `REPLACE`; `APPEND` действует
только при явном значении. Для APPEND allocation probes не выполняются.
Authoritative held IDs и snapshots items со статусом `FREE` поступают из
logistics. Public selection PUT и tool `remove_selected_cabins` сохраняют
точные current IDs; removal немедленно заменяет logistics selection и тем самым
сбрасывает/освобождает удалённые asset holds. assistant-service не хранит
параллельную hold truth.

Перед каждым tool execution сервис читает current logistics inquiry context.
Зафиксированный logistics warehouse ограничивает возвращаемый facet list и
каждый search, clarification, catalog и selection mutation; несовпадающий
model argument отклоняется до downstream operation. Warehouse никогда не
кэшируется и не сохраняется assistant-service, поэтому последующая фиксация
warehouse заказа учитывается на следующем tool call, а logistics по-прежнему
повторно валидирует каждую mutation.

## Публичный API, authorization и isolation

Interactive clients вызывают только public gateway routes, а не private service origin:

| Public route | Meaning | Authorization |
| --- | --- | --- |
| POST /api/assistant/v1/conversations | Создать или replay conversation и delegated inquiry | Dedicated rental-manager client, `rental.manage` и signed manager subject |
| GET /api/assistant/v1/conversations | Получить owner history или найти active conversation по optional `rentalOrderId` | Та же manager/owner boundary |
| GET или DELETE /api/assistant/v1/conversations/{conversationId} | Прочитать или archive одну owner-scoped conversation | Та же manager/owner boundary |
| POST /api/assistant/v1/conversations/{conversationId}/turns | SSE stream одного persisted user turn | Та же manager/owner boundary |
| PUT /api/assistant/v1/conversations/{conversationId}/selection | Сохранить точные current cabin IDs и немедленно освободить удалённые | Та же manager/owner boundary плюс Idempotency-Key |

Conversation creation принимает ровно одно из `clientId` или `newClient`.
При передаче `rentalOrderId` дополнительно требуется `clientId`;
logistics-service решает, находится ли этот current order в любом состоянии,
которое его текущие правила считают редактируемым, и отклоняет client mismatch.
`newClient` содержит `clientType`, `displayName`, `phone`, optional `email`,
`comment` и `source`, а для legal entity также обязательный `contactPerson`.
Responsible manager остаётся во владении logistics и не может
быть выбран этим request.

Сервис принимает только tokens клиентов `rwms-rental-manager-web` и
`rwms-rental-manager-android` для распознанной не-клиентской роли `USER` с
`rentalAccess`, точным application scope `rental.manage` и подписанным UUID manager subject.
Manager identity не принимается как request parameter. Lists, direct reads и
mutations требуют этого owner; foreign ID выглядит
отсутствующим. Private logistics request пересылает current Bearer token,
поэтому logistics сохраняет собственное user и warehouse authorization
decision. Сервис никогда не отправляет этот token LLM provider и не логирует
request bodies или provider credentials.

## Persistence, event delivery и failures

Flyway владеет service-local schema; Hibernate только валидирует её. V6
добавляет nullable order link, active-order partial uniqueness constraint и
ordered clarification sequence. Existing questions ранжируются без потерь;
если в старых данных несколько `PENDING` rows, только старейшая остаётся
actionable, а остальные становятся `QUEUED`. Conversation records используют
optimistic versioning; только короткие local creation и clarification
transitions берут transaction-scoped advisory locks для одной
order/conversation identity. Booking listener регистрируется Boot Kafka как
`assistantRentalInquiryBookedListener`. После archive conversation остаётся доступной как
история, но больше не читает live holds и не показывает live clarifications. Если terminal
inquiry прочитан до прихода его Kafka booking fact в inbox, та же local conversation
согласованно помечается archived вместо сообщения об upstream outage. Reconciliation fence
включает и conversation, и inquiry identity, поэтому late booking event для archived inquiry
идемпотентен и не может архивировать более новую conversation заказа. Booking listener
принимает только точную booking shape `DomainEventEnvelopeV2` из
`rwms.logistics.rental-inquiry.events.v1`: duplicate или additional object
fields, изменённые producer/type/version, invalid coordinates, не-UUID
`aggregateId` либо Kafka key/correlation ID, отличный от
`payload.conversationId`, отклоняются до persistence. `aggregateId` является
идентичностью rental inquiry; точный payload содержит только `conversationId`
и `orderId`.

Каждый valid envelope проходит object-key canonicalization и SHA-256 binding к
event ID и durable topic/partition/offset receipt. Тот же event ID с другим
canonical hash или тот же source receipt с другими evidence является
конфликтом и никогда не считается successful duplicate. Inbox rows до V4
остаются `LEGACY_PROCESSED` с явно неизвестным canonical hash и не могут быть
незаметно перепривязаны.

Processing имеет четыре lifetime-persisted попытки в t=0/1/3/7 seconds; Kafka
redelivery никогда не сбрасывает `attempt_count`. После exhaustion переход
inbox и sanitized DLT evidence фиксируются atomically. Rejected raw values,
Bearer data и exception text никогда не сохраняются: evidence содержит только
message hash, safe failure code, optional staged event ID и
topic/partition/offset. Valid failed envelope можно approve/reject с fenced
review version и replay только из canonical inbox copy, staged до failure.
`AssistantDltRecoveryService` намеренно не имеет public API и сейчас не имеет
production caller; это только internal test-proven reviewed-replay seam, а не
automatic recovery. Interrupted retry waits и DLT write failures передаются
Kafka.

Durable tool calls, которые остаются в состоянии `STARTED`, наблюдаются через
два lazy gauge с фиксированными именами:
`rwms.assistant.recovery.tool-calls.started` сообщает их количество, а
`rwms.assistant.recovery.tool-calls.started.oldest.age.seconds` — возраст самой
старой записи. Оба выполняют read-only JPA queries во время scrape, не публикуют
conversation или turn IDs, tool names, failure codes или payload labels,
ограничивают будущие timestamps нулём и возвращают `NaN` при ошибке доступа к
БД. Строки
`COMPLETED` и `FAILED` являются terminal history и исключены; scrape никогда не
изменяет и не повторяет tool call.

Provider и logistics failures отдаются как safe API/SSE failure codes. Asynchronous turn executor bounded; caller не должен считать connection failure или SSE failure event успешным availability result. Health, Prometheus, tracing и structured logs доступны через Spring configuration.

## Локальная разработка и production configuration

Ignored local .env.local может быть загружен из service directory или repository root launch. Environment variables override его в production.

| Setting | Назначение |
| --- | --- |
| ASSISTANT_DB_URL, ASSISTANT_DB_USERNAME, ASSISTANT_DB_PASSWORD | Service-owned PostgreSQL connection |
| AUTH_ISSUER, AUTH_AUDIENCE | Local JWT issuer и audience validation |
| PANEL_ORIGIN | Явный browser CORS origin |
| LLM_BASE_URL, LLM_API_KEY, LLM_MODEL | OpenAI-compatible provider endpoint, credential и model |
| LLM_REQUEST_TIMEOUT | Общий deadline попытки запроса, включая headers и SSE body; по умолчанию 60s |
| LLM_STREAM_IDLE_TIMEOUT | Максимальная пауза между SSE-строками провайдера; по умолчанию 15s |
| LOGISTICS_BASE_URL | Private logistics-service base address |
| ASSISTANT_KAFKA_ENABLED, ASSISTANT_KAFKA_BROKERS | Booking event consumption |
| ASSISTANT_SSE_TIMEOUT | Maximum server-sent-event turn lifetime |

Startup fails closed, когда LLM_API_KEY требуется, но пуст. Локально используйте только development values; managed environments должны передавать реальные secrets и private service addresses. Не помещайте credentials в source, READMEs или event data.

## Проверка и безопасные изменения

Запустите root module suite:

    bash ./gradlew :services:assistant-service:test

Standalone service build также поддерживается:

    bash ./gradlew -p services/assistant-service test

Перед изменением conversation command, tool, provider, booking event или logistics request:

1. проследите public OpenAPI, gateway route, logistics owner и всех active consumers;
2. сохраните owner authorization, stable idempotency, local persistence и replay-safe event handling;
3. сделайте явной policy remote effect/recovery вместо browser rollback; и
4. протестируйте provider timeout, upstream failure, duplicate booking delivery, archived inquiry и concurrent/retried turn paths.

Основные implementation references: [AssistantConversationService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java),
[AssistantTurnService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantTurnService.java),
[AssistantToolExecutor](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantToolExecutor.java),
[AssistantCabinSearchTool](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantCabinSearchTool.java),
[AssistantCabinReferenceTool](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantCabinReferenceTool.java),
[AssistantClarificationService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantClarificationService.java),
[AssistantSelectionService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantSelectionService.java),
[AssistantConversationCreationStore](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationCreationStore.java),
[HttpLogisticsClient](src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java)
[RentalInquiryArchiveService](src/main/java/dev/buhanzaz/rwms/assistant/eventing/RentalInquiryArchiveService.java)
[AssistantDltRecoveryService](src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantDltRecoveryService.java)
и [AssistantRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantRecoveryMetrics.java).
