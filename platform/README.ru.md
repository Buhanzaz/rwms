# Платформенные модули RWMS

[English version](README.md)

Каталог platform содержит общие технические модули и исполняемую архитектурную policy RWMS. Эти модули намеренно узкие: domain ownership остаётся у owning service, а каноническая семантика HTTP/events остаётся в contracts.

## Модули и ответственность

| Module | Responsibility | Преднамеренно не отвечает за |
| --- | --- | --- |
| [technical-contracts](technical-contracts/README.ru.md) | Framework-neutral cross-cutting Java wire records | Shared business entities, Spring/JPA/Kafka dependencies и domain commands |
| [spring-boot-starter](spring-boot-starter/README.ru.md) | Optional technical Spring auto-configuration и Kafka publishing minimums | Service security chains, repositories, business topics, inbox/outbox tables и workflow |
| [architecture-tests](architecture-tests/README.ru.md) | Executable dependency, mapper и source-boundary policies | Runtime business authorization или service orchestration |

[settings.gradle.kts](../settings.gradle.kts) в корне repository включает эти modules и services, которые их используют. Platform module может поддерживать boundary, но не становится его business owner.

## Dependency и contract rules

- Handwritten OpenAPI и AsyncAPI/JSON schemas в [contracts](../contracts/) являются каноническими.
- Technical contracts должны оставаться immutable, framework-neutral и свободными от service domain models.
- Stateful service владеет одной database; cross-service repositories, JPA entities, joins и shared mutable models запрещены.
- Kafka — transport с семантикой at least once. Service-owned outbox/inbox, deduplication, aggregate ordering, DLT и replay behavior остаются локальными для owning service.
- Gateway является только transport layer и не заменяет service-owned command, saga или projection.

## Использование и изменение platform modules

Добавляйте platform dependency только для действительно общего technical concern. Держите generated clients и transport DTOs на service boundaries; не используйте их как persistence entities или путь пересечь domain boundary.

Если изменение меняет technical wire shape, проследите каждого producer и consumer, одновременно обновите canonical schemas и compatibility tests и сохраните service-local mapping. Не выбирайте молча obsolete compatibility record для нового event family.

Architecture tests сейчас импортируют services, названные в их test configuration. Их документированное покрытие намеренно явно; добавление service в root build не добавляет его автоматически в ArchUnit rule.

Текущий Java graph покрывает auth, task-board, warehouse, asset, maintenance,
inventory, logistics, dossier, assistant, analytics и api-gateway. Центральные
rules требуют constructor injection и одобренные MapStruct boundaries,
сохраняют service-owned models, удерживают gateway stateless и запрещают read
models analytics/dossier публиковать mutating HTTP mappings.

## Проверка

Для platform change запускайте narrow module checks:

    bash ./gradlew :platform:technical-contracts:test
    bash ./gradlew :platform:spring-boot-starter:test
    bash ./gradlew :platform:architecture-tests:test

Contract-boundary change дополнительно требует canonical schema validation и focused producer/consumer checks в owning services.

## References

- [Repository architecture knowledge](../docs/project-knowledge/architecture.md)
- [Contract evolution rules](../docs/project-knowledge/contracts.md)
- [Documentation standard](../docs/project-knowledge/documentation-standard.md)
- [Root Gradle settings](../settings.gradle.kts)
