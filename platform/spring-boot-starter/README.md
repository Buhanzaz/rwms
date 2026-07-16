# RWMS Spring Boot Starter

Technical auto-configuration shared by RWMS Spring services. The starter exposes
`platform:technical-contracts`, but it does not contain domain DTOs, persistence
models, repositories, security chains, outbox/inbox entities, secrets, or service
URLs.

## Usage

```kotlin
dependencies {
    implementation(project(":platform:spring-boot-starter"))
}
```

Core configuration provides a UTC `Clock`, UUID correlation headers, Problem
Detail construction, and a JWT audience-validator factory. A separate top-level
Jackson auto-configuration adds UTC mapper defaults only when Jackson 3 is on the
consumer runtime classpath, so non-JSON consumers never load a Jackson-linked
configuration class. Beans back off when the service supplies the corresponding
RWMS bean. The starter never creates a `SecurityFilterChain`.

When Micrometer Observation is present, the starter provides a no-op-capable
`ObservationRegistry` only if the application or Spring Boot has not already
defined one. It does not add Actuator, a tracer, exporters, sampling, or naming
policy. During HTTP processing the UUID correlation ID is available under the
`correlationId` MDC key and is restored or cleared in a `finally` block.

HTTP errors use the single `platform:technical-contracts` `ApiProblem` wire
shape. Correlation metadata remains nested as `correlation`, and validation
violations use the canonical immutable list; the starter does not emit a second
flat Spring `ProblemDetail` payload.

Production JPA safety cannot be disabled. With a `prod` or `production` profile,
`spring.jpa.hibernate.ddl-auto` must be `validate`. Schema-mutating modes are
accepted only with an explicit `dev`, `development`, or `test` profile.
The guard is classpath-conditional and remains absent from stateless applications
that do not include Jakarta Persistence.

## RabbitMQ retirement

The target Spring starter has no Spring AMQP dependency, auto-configuration,
topology factory, listener policy, or Rabbit test runtime. New and existing
Spring services use the Kafka/Cloud Stream integration below.

RabbitMQ remains available only through the explicit Compose `media-compat-rabbit`
profile for the unchanged legacy Go photo worker until its Stage 4 cutover. The
broker retains the `rabbitmq` network alias expected by the worker, but no target
Spring service depends on that compatibility service. It is not a reusable
business-integration path.

Start only that compatibility broker with:

```shell
docker compose --profile media-compat-rabbit up -d media-compat-rabbitmq
```

The unchanged worker connects with
`PHOTO_RABBITMQ_URL=amqp://rwms:rwms_dev@rabbitmq:5672/`, consumes
`PHOTO_PROCESS_QUEUE=repair.media.process`, and publishes results to
`PHOTO_RESULT_QUEUE=repair.media.processed`. Credentials shown here are local
Compose defaults only; managed environments supply external secrets. The worker
itself remains legacy evidence and is not started by the root Compose file.

## Kafka and Cloud Stream opt-in

Kafka support is disabled by default. The consuming service adds the Cloud
Stream Kafka binder and declares every aggregate-family destination it is
allowed to publish:

```yaml
rwms:
  platform:
    kafka:
      enabled: true
      destinations:
        - rwms.task-board.board-task.v1
        - rwms.task-board.queue-entry.v1
```

The allow-list accepts exact versioned `rwms.<domain>.<aggregate>.vN` names only.
Wildcards, implicit topics and malformed names fail startup or publication. The
starter does not declare business topics, consumer groups, outbox/inbox tables or
domain retry policy.

`RwmsKafkaOutboundEventPublisher` accepts either a framework-neutral
`DomainEventEnvelopeV2` or its serialized JSON from an outbox. Serialized input
is parsed with duplicate-field and unknown-envelope-field rejection before it
can reach the binder. The publisher preserves the canonical V2 JSON body,
publishes with the aggregate ID as the Kafka key and copies only non-PII
technical metadata into headers. Actor data and payload fields never become
headers.

Dynamic Kafka destinations use synchronous broker acknowledgement, `acks=all`,
producer idempotence and Zstd compression as non-overridable platform minimums.
A service-owned `NewDestinationBindingCallback` may add stricter settings, but
the starter reapplies the platform minimums after that callback. A `false`
result from `StreamBridge.send` and any binder
exception raise `RwmsKafkaPublishException`; an outbox relay must leave its row
unpublished in either case.

Local Kafka runs from the Compose `core` profile as a single-node Kafka 4.3.1
KRaft broker. Topic auto-creation is a local-development convenience only and
must be disabled in managed environments.
