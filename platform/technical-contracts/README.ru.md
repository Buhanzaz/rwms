# Технические контракты RWMS

[English version](README.md)

technical-contracts содержит immutable framework-neutral Java records для небольших cross-cutting wire shapes. Это не shared domain model: service сохраняет собственные aggregate, persistence entity, command, status и transport adapter.

## Граница

У модуля нет Spring, JPA, Kafka, broker-client или business-domain dependency. Он может представлять technical identity, correlation, paging и validated event envelope, но никогда не должен давать одному сервису in-memory способ изменить state другого.

Handwritten schemas в [contracts](../../contracts/) остаются каноническими для каждого domain API и integration event. Services маппируют schema в service-local types на своей границе; generated clients и эти records никогда не являются JPA entities.

## Event records

DomainEventEnvelopeV2 — строгий technical envelope для новых canonical event families. Он валидирует V2 marker, event naming/version relation, aggregate identity/version, correlation, opaque actor reference и object payload shape. Payload meaning, compatibility и producer ownership остаются в event schema, а не в этом module.

В модуле также остаются EventEnvelope и ActorSnapshot. Они не заменяют V2 schema boundary: EventEnvelope не имеет V2 envelope marker и recorded time, а ActorSnapshot содержит display name. Не выбирайте эти shapes для нового integration event без explicit compatibility decision и обновлённого canonical contract.

## Безопасное использование

- Сохраняйте records immutable, а validation ограничивайте technical wire safety.
- Не добавляйте domain enum, aggregate, repository, command, secret, URL или broker implementation.
- Развивайте domain event через его schema, producer и каждого active consumer, а не только изменением shared Java type.
- Сохраняйте actor references opaque и не помещайте personal data в technical metadata.

## Проверка

Запустите module checks из repository root:

    bash ./gradlew :platform:technical-contracts:test
    bash ./gradlew :platform:technical-contracts:compileJava

Основные references: [DomainEventEnvelopeV2](src/main/java/dev/buhanzaz/rwms/platform/contracts/DomainEventEnvelopeV2.java),
[contracts technical notes](../../contracts/technical-contracts.md) и
[event contract rules](../../contracts/events/README.md).
