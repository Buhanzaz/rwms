# RWMS Spring Boot Starter

[Русская версия](README.ru.md)

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

The optional RwmsProblemDetailFactory produces the
`platform:technical-contracts` ApiProblem wire shape with nested correlation
metadata and immutable validation violations. The starter does not install an
exception handler or force a service's public error serialization.

Production JPA safety cannot be disabled. With a `prod` or `production` profile,
`spring.jpa.hibernate.ddl-auto` must be `validate`. Schema-mutating modes are
accepted only with an explicit `dev`, `development`, or `test` profile.
The guard is classpath-conditional and remains absent from stateless applications
that do not include Jakarta Persistence.

## RabbitMQ compatibility boundary

The target Spring starter has no Spring AMQP dependency, auto-configuration,
topology factory, listener policy, or Rabbit test runtime. New and existing
Spring services use the Kafka/Cloud Stream integration below.

An explicit Compose media compatibility profile may run a broker for the
historical Go photo-worker evidence. No target Spring service depends on that
profile, and it is not a reusable business-integration path. Its local runtime
values belong in local environment configuration, never in source or a README.

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

## Verification and safe changes

    bash ./gradlew :platform:spring-boot-starter:test
    bash ./gradlew :platform:spring-boot-starter:compileJava

Do not add business-domain ownership to the starter. When changing an event
publishing or security convention, update the canonical contract and focused
owning-service tests; the service continues to own outbox, inbox, authorization,
retry and recovery behavior.
