# Spring Boot Starter RWMS

[English version](README.md)

Technical auto-configuration, общая для Spring services RWMS. Starter предоставляет technical-contracts, но не содержит domain DTOs, persistence models, repositories, security chains, inbox/outbox entities, secrets или service URLs.

## Использование

Добавьте dependency в service module:

    dependencies {
        implementation(project(":platform:spring-boot-starter"))
    }

Core configuration предоставляет UTC Clock, UUID correlation headers, optional Problem Details factory и JWT audience-validator factory. Отдельная Jackson auto-configuration добавляет UTC mapper defaults только когда Jackson 3 есть в runtime classpath, поэтому non-JSON consumer не загружает Jackson-linked configuration. Beans отступают, если service предоставляет соответствующий RWMS bean. Starter никогда не создаёт SecurityFilterChain.

Когда доступен Micrometer Observation, starter предоставляет no-op-capable ObservationRegistry только если его ещё не определило application или Spring Boot. Он не добавляет Actuator, tracer, exporters, sampling или naming policy. Во время HTTP processing UUID correlation ID доступен в MDC под key correlationId и восстанавливается или очищается в finally.

Optional RwmsProblemDetailFactory производит platform:technical-contracts ApiProblem wire shape с nested correlation metadata и immutable validation violations. Starter не устанавливает exception handler и не принуждает service к определённой public error serialization.

Production JPA safety нельзя отключить. При profile prod или production spring.jpa.hibernate.ddl-auto должен быть validate. Schema-mutating modes допускаются только с явным dev, development или test profile. Guard classpath-conditional и отсутствует у stateless applications без Jakarta Persistence.

## RabbitMQ compatibility boundary

Target Spring starter не имеет Spring AMQP dependency, auto-configuration, topology factory, listener policy или Rabbit test runtime. Новые и существующие Spring services используют Kafka/Cloud Stream integration ниже.

Explicit Compose media compatibility profile может запускать broker для historical Go photo-worker evidence. Ни один target Spring service от этого profile не зависит, и он не является reusable business-integration path. Его local runtime values должны оставаться в local environment configuration, а не в source или README.

## Kafka и Cloud Stream opt-in

Kafka support выключен по умолчанию. Consuming service добавляет Cloud Stream Kafka binder и объявляет каждый aggregate-family destination, который ему разрешено публиковать:

    rwms:
      platform:
        kafka:
          enabled: true
          destinations:
            - rwms.task-board.board-task.v1
            - rwms.task-board.queue-entry.v1

Allow-list принимает только exact versioned names вида rwms.<domain>.<aggregate>.vN. Wildcards, implicit topics и malformed names останавливают startup или publication. Starter не объявляет business topics, consumer groups, inbox/outbox tables или domain retry policy.

RwmsKafkaOutboundEventPublisher принимает framework-neutral DomainEventEnvelopeV2 или его serialized JSON из outbox. Serialized input разбирается с rejection duplicate fields и unknown envelope fields до binder. Publisher сохраняет canonical V2 JSON body, публикует aggregate ID как Kafka key и копирует в headers только non-PII technical metadata. Actor data и payload fields не становятся headers.

Dynamic Kafka destinations используют synchronous broker acknowledgement, acks=all, producer idempotence и Zstd compression как non-overridable platform minimums. Service-owned NewDestinationBindingCallback может добавить более строгие settings, но starter повторно применяет platform minimums после callback. False от StreamBridge.send и binder exception вызывают RwmsKafkaPublishException; outbox relay должен оставить row unpublished в обоих случаях.

Local Kafka запускается Compose core profile как single-node Kafka KRaft broker. Topic auto-creation — только local-development convenience и должен быть выключен в managed environments.

## Проверка и безопасные изменения

    bash ./gradlew :platform:spring-boot-starter:test
    bash ./gradlew :platform:spring-boot-starter:compileJava

Не добавляйте business-domain ownership в starter. При изменении event publishing или security convention обновите canonical contract и focused owning-service tests; service продолжает владеть outbox, inbox, authorization, retry и recovery behavior.
