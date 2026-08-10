# Сервис assistant RWMS

[English version](README.md)

assistant-service владеет stateful rental conversations, messages, tool-call history и своим booking-event inbox. Rental inquiries, short cabin selections и availability остаются в собственности logistics-service. Assistant не является вторым владельцем rental domain и не читает БД logistics.

## Назначение и граница ответственности

Public assistant API позволяет authorized rental user начать или продолжить conversation и получить streamed tool-assisted response. Сервис сохраняет user message, provider/tool interaction и final assistant message, поэтому последующее чтение conversation использует server-authoritative history.

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
    local JWT и rentalAccess check -> conversation/message persistence
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

Clarification questions хранятся в V5 assistant schema со стабильными
question, option и branch identities. Несколько ветвей, например ОСБ и ЛДСП,
остаются независимо `PENDING`/`ANSWERED` и могут быть отвечены в любом порядке.
Button turn принимает только сохранённые `questionId` и `optionId`; видимое
сохранённое user message содержит вопрос и выбранный label, а не технический
branch key. SSE events `clarification.requested` и
`clarification.answered` используют ту же reloadable question shape.

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

## Публичный API, authorization и isolation

Interactive clients вызывают только public gateway routes, а не private service origin:

| Public route | Meaning | Authorization |
| --- | --- | --- |
| POST /api/assistant/v1/conversations | Создать или replay conversation и delegated inquiry | Authenticated JWT с rentalAccess |
| GET или DELETE /api/assistant/v1/conversations/{conversationId} | Прочитать или archive одну owner-scoped conversation | Тот же owner-scoped rental access |
| POST /api/assistant/v1/conversations/{conversationId}/turns | SSE stream одного persisted user turn | Тот же owner-scoped rental access |
| PUT /api/assistant/v1/conversations/{conversationId}/selection | Сохранить точные current cabin IDs и немедленно освободить удалённые | Тот же owner-scoped rental access плюс Idempotency-Key |

Conversation creation принимает ровно одно из `clientId` или `newClient`.
`newClient` содержит `clientType`, `displayName`, `phone`, optional `email`,
`comment` и `source`, а для legal entity также обязательный `contactPerson`.
Responsible manager остаётся во владении logistics и не может
быть выбран этим request.

Сервис требует UUID JWT subject и rentalAccess claim до чтения или изменения conversation state. Private logistics request пересылает current Bearer token, поэтому logistics сохраняет собственное user и warehouse authorization decision. Сервис никогда не отправляет этот token LLM provider и не логирует request bodies или provider credentials.

## Persistence, event delivery и failures

Flyway владеет service-local schema; Hibernate только валидирует её.
Conversation records используют optimistic versioning; только короткая local
creation finalization берёт transaction-scoped advisory lock для одного
conversation ID. Booking listener регистрируется Boot Kafka как
`assistantRentalInquiryBookedListener`. После archive conversation остаётся доступной как
история, но больше не читает live holds и не показывает live clarifications. Если terminal
inquiry прочитан до прихода его Kafka booking fact в inbox, та же local conversation
согласованно помечается archived вместо сообщения об upstream outage. Booking listener
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
