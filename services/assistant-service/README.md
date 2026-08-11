# RWMS Assistant Service

[Русская версия](README.ru.md)

assistant-service owns stateful rental conversations, messages, tool-call
history and its booking-event inbox. Rental inquiries, short cabin selections
and availability remain owned by logistics-service. The assistant is not a
second rental domain owner and does not query a logistics database.

## Purpose and boundary

The public assistant API lets an authorized rental user start or resume a
conversation and stream a tool-assisted response. The service persists the user
message, provider/tool interaction and final assistant message so a later
conversation read has server-authoritative history.

It deliberately does not:

- decide cabin availability, publish a rental inquiry or mutate a logistics
  aggregate locally;
- expose a browser-owned saga or mock result when logistics or the provider is
  unavailable;
- let the LLM execute arbitrary HTTP, SQL, files or commands; or
- accept a direct public client request outside the gateway.

The canonical API is [assistant-service.yaml](../../contracts/openapi/assistant-service.yaml).
The booking completion fact is declared by
[logistics-events.yaml](../../contracts/events/logistics-events.yaml).

## Request, tool and booking flow

    public gateway /api/assistant/**
            |
            v
    local JWT and rentalAccess check -> conversation/message persistence
            |
            +--> private logistics REST with the current user bearer
            |        -> exact cabin facets/searches and rental inquiry state
            |
            +--> configured OpenAI-compatible LLM provider
                     -> bounded tool allow-list -> persisted tool result

    logistics.rental-inquiry.booked.v1
            |
            v
    local event inbox -> archive only the matching local conversation

The executor is a thin dispatcher over separate facet/search, reference,
clarification and selection collaborators. It accepts only the documented
tools, validates arguments, rejects unsupported result fields and turns
upstream failures into safe tool outcomes. Tool results and completed
conversation history are replayed to the configured provider for later turns;
treat tool payload sanitization and provider data handling as a production
boundary, not as browser presentation data.

A caller-supplied conversation ID is the stable idempotency key forwarded when
creating the delegated rental inquiry. The ID is optional in the public
contract. Local preflight and finalization use separate short transactions; the
remote call runs without an assistant database transaction or advisory lock.
The finalization lock creates or validates one local row when same-key calls
race. A `newClient` replay revalidates the idempotent remote result, while an
existing-client replay may return the already validated local row. Remote
success and local persistence failure are not one atomic cross-service
transaction, so recovery uses the same conversation ID rather than a new key.

An optional `rentalOrderId` links the conversation to an existing order and
requires its existing `clientId`. Assistant-service forwards both identities to
logistics-service and does not duplicate the logistics-owned editable-order,
client or warehouse checks. `GET /api/assistant/v1/conversations?rentalOrderId=...`
returns the owner-scoped active link. A partial unique database index permits
only one non-archived conversation for an order: an active conversation is
reopened, while archiving it permits a new linked conversation. The local
order-scoped finalization lock resolves concurrent creates to that one active
link. Before an order-filtered list or create reuses that link, assistant-service
reads its exact inquiry/client/order context from logistics outside a local
transaction. `ACTIVE` is reusable; `BOOKED` or `ARCHIVED` archives only that
exact local conversation under the existing order lock, after which create can
open a fresh linked inquiry while list returns no terminal chat. A different
winner observed under the lock is rechecked outside the transaction; bounded
churn, an unknown state, an identity mismatch or a dependency failure fails
closed. The unfiltered history list performs one local query and no per-row
logistics reconciliation.

For a cabin search, the already-persisted `AssistantToolCall.id` is the durable
attempt root. A direct search uses that UUID; each bounded logical probe and the
final selection derive a distinct deterministic UUID from the same root and
stable step name. `HttpLogisticsClient` never invents a search key and retries
only one lost-response `IOException` with the byte-identical request and caller
key. A newly created client can reuse a caller-provided durable key, but the
assistant persists no Bearer token and performs no automatic post-restart
search resumption.

## Interactive cabin selection and reference

An availability result is impossible until every group has an exact current
`cabinType` and `finish`. Current logistics facets also provide exact
type-to-dimension relations. One related dimension is selected
deterministically; several related dimensions produce buttons and no search or
hold. The phrase “6 metres” is resolved only to an exact `6x2.4`-equivalent
facet related to the chosen type. When the type is missing, dimension-compatible
types alone become the type buttons; unrelated modules or security posts are
never invented.

Clarification questions have stable question and option identities and a
durable conversation-wide sequence. One batch may contain several questions,
but only its oldest `PENDING` head is actionable and visible; later `QUEUED`
questions remain hidden. A button turn accepts only the head's persisted
`questionId` and `optionId`. Answering an intermediate head activates and emits
the next question without calling the provider; provider/tool execution resumes
exactly once after the batch's last answer. Answered and superseded history
remains reloadable in sequence. The visible stored user message contains the
question and selected label, not its technical branch key.

`lookup_cabin_catalog` is a separate read-only help path. It can explain the
current type, finish, characteristic and size relations or request one bounded
warehouse-scoped fact page by exact number/text, including linoleum, without
running availability search or acquiring a hold. After a successful search,
`filterSuggestions` contains only values returned by the current warehouse
facets.

Missing or null search `resultMode` is `REPLACE`; `APPEND` is honored only when
explicit. Allocation probes are never used for APPEND. The authoritative held
IDs and `FREE` item snapshots come from logistics. The public selection PUT and
`remove_selected_cabins` tool keep exact current IDs; removal immediately
replaces the logistics selection and therefore resets/releases the removed
asset holds. Assistant-service stores no parallel hold truth.

Before each tool execution the service reads the current logistics inquiry
context. A logistics-fixed warehouse scopes the returned facet list and every
search, clarification, catalog and selection mutation; a mismatched model
argument is rejected before the downstream operation. The warehouse is never
cached or persisted by assistant-service, so later order warehouse fixation is
observed on the next tool call and logistics still revalidates every mutation.

## Public API, authorization and isolation

Interactive clients call the public gateway routes below, never a private
service origin:

| Public route | Meaning | Authorization |
| --- | --- | --- |
| POST /api/assistant/v1/conversations | Create or replay a conversation and delegated inquiry | Authenticated JWT with rentalAccess |
| GET /api/assistant/v1/conversations | List owner history or locate the active conversation by optional `rentalOrderId` | Authenticated JWT with rentalAccess |
| GET or DELETE /api/assistant/v1/conversations/{conversationId} | Read or archive one owner-scoped conversation | Same owner-scoped rental access |
| POST /api/assistant/v1/conversations/{conversationId}/turns | SSE stream for one persisted user turn | Same owner-scoped rental access |
| PUT /api/assistant/v1/conversations/{conversationId}/selection | Keep exact current cabin IDs and immediately release removals | Same owner-scoped rental access plus Idempotency-Key |

Conversation creation accepts exactly one of `clientId` or `newClient`.
Supplying `rentalOrderId` additionally requires `clientId`; logistics-service
decides whether that current order is in any state its current rules declare
editable and rejects a client mismatch.
`newClient` contains `clientType`, `displayName`, `phone`, optional `email`,
`comment` and `source`, plus required `contactPerson` for a legal entity. The
responsible manager remains logistics-owned and cannot be
chosen through this request.

The service requires a UUID JWT subject and rentalAccess claim before it reads
or changes conversation state. A private logistics request forwards the current
Bearer token so logistics retains its own user and warehouse authorization
decision. The service never sends that token to the LLM provider and never logs
request bodies or provider credentials.

## Persistence, event delivery and failures

Flyway owns the service-local schema; Hibernate validates it only. V6 adds the
nullable order link, the active-order partial uniqueness constraint and the
ordered clarification sequence. Existing questions are ranked without loss;
when old data has several `PENDING` rows, only the oldest stays actionable and
the rest become `QUEUED`. Conversation records use optimistic versioning; only
the short local creation and clarification transitions take transaction-scoped
advisory locks for one order/conversation identity. The booking listener is
registered by Boot Kafka as `assistantRentalInquiryBookedListener`. Once archived, a
conversation remains readable as history but never re-reads live holds or exposes live
clarifications. If a terminal inquiry is read before its Kafka booking fact reaches the inbox,
the same local conversation is reconciled to archived instead of reporting an upstream outage.
The reconciliation fence includes both conversation and inquiry identity, so a late booking event
for the archived inquiry is idempotent and cannot archive a newer conversation for the order.
The booking listener accepts only the exact `DomainEventEnvelopeV2` booking shape from
`rwms.logistics.rental-inquiry.events.v1`: duplicate or additional object
fields, changed producer/type/version, invalid coordinates, a non-UUID
`aggregateId`, or a Kafka key/correlation ID different from
`payload.conversationId` are rejected before persistence. `aggregateId` is the
rental-inquiry identity; the exact payload contains only `conversationId` and
`orderId`.

Every valid envelope is object-key canonicalized and SHA-256 bound to both its
event ID and durable topic/partition/offset receipt. The same event ID with a
different canonical hash, or the same source receipt with different evidence,
is a conflict and is never reported as a successful duplicate. Pre-V4 inbox
rows remain `LEGACY_PROCESSED` with an explicitly unknown canonical hash and
cannot be silently rebound.

Processing has four lifetime-persisted attempts at t=0/1/3/7 seconds; Kafka
redelivery never resets `attempt_count`. After exhaustion, the inbox transition
and sanitized DLT evidence commit atomically. Rejected raw values, Bearer data
and exception text are never retained: evidence contains only a message hash,
safe failure code, optional staged event ID and topic/partition/offset. A valid
failed envelope can be approved/rejected with a fenced review version and
replayed only from the canonical inbox copy that was staged before failure.
`AssistantDltRecoveryService` deliberately has no public API and currently no
production caller; it provides only an internal, test-proven reviewed-replay
seam, not automatic recovery. Interrupted retry waits and DLT write failures
propagate to Kafka.

Durable tool calls that remain `STARTED` are observable through two lazy,
fixed-name gauges: `rwms.assistant.recovery.tool-calls.started` reports their
count and `rwms.assistant.recovery.tool-calls.started.oldest.age.seconds`
reports the oldest age. Both execute read-only JPA queries at scrape time,
publish no conversation or turn IDs, tool names, failure codes or payload
labels, clamp future timestamps to zero and report `NaN` when database access
fails.
`COMPLETED` and `FAILED` rows are terminal history and are excluded; scraping
never changes or retries a tool call.

Provider and logistics failures are surfaced as safe API/SSE failure codes.
The asynchronous turn executor is bounded; callers must not interpret a
connection failure or an SSE failure event as a successful availability result.
Health, Prometheus, tracing and structured logs are available through Spring
configuration.

## Local development and production configuration

The ignored local .env.local file may be loaded from the service directory or
the repository root launch. Environment variables override it in production.

| Setting | Purpose |
| --- | --- |
| ASSISTANT_DB_URL, ASSISTANT_DB_USERNAME, ASSISTANT_DB_PASSWORD | Service-owned PostgreSQL connection |
| AUTH_ISSUER, AUTH_AUDIENCE | Local JWT issuer and audience validation |
| PANEL_ORIGIN | Explicit browser CORS origin |
| LLM_BASE_URL, LLM_API_KEY, LLM_MODEL | OpenAI-compatible provider endpoint, credential and model |
| LOGISTICS_BASE_URL | Private logistics-service base address |
| ASSISTANT_KAFKA_ENABLED, ASSISTANT_KAFKA_BROKERS | Booking event consumption |
| ASSISTANT_SSE_TIMEOUT | Maximum server-sent-event turn lifetime |

Startup fails closed when LLM_API_KEY is required but blank. Use development
values only locally; managed environments must supply real secrets and private
service addresses. Do not place credentials in source, READMEs or event data.

## Verification and safe changes

Run the root module suite:

    bash ./gradlew :services:assistant-service:test

The standalone service build is also supported:

    bash ./gradlew -p services/assistant-service test

Before changing a conversation command, tool, provider, booking event or
logistics request:

1. trace the public OpenAPI, gateway route, logistics owner and all active
   consumers;
2. preserve owner authorization, stable idempotency, local persistence and
   replay-safe event handling;
3. make any remote effect/recovery policy explicit rather than adding a browser
   rollback; and
4. test provider timeout, upstream failure, duplicate booking delivery,
   archived inquiry and concurrent/retried turn paths.

Primary implementation references: [AssistantConversationService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java),
[AssistantTurnService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantTurnService.java),
[AssistantToolExecutor](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantToolExecutor.java),
[AssistantCabinSearchTool](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantCabinSearchTool.java),
[AssistantCabinReferenceTool](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantCabinReferenceTool.java),
[AssistantClarificationService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantClarificationService.java),
[AssistantSelectionService](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantSelectionService.java),
[AssistantConversationCreationStore](src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationCreationStore.java),
[HttpLogisticsClient](src/main/java/dev/buhanzaz/rwms/assistant/integration/HttpLogisticsClient.java),
[RentalInquiryArchiveService](src/main/java/dev/buhanzaz/rwms/assistant/eventing/RentalInquiryArchiveService.java),
[AssistantDltRecoveryService](src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantDltRecoveryService.java),
and [AssistantRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantRecoveryMetrics.java).
