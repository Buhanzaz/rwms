# Архитектурные тесты RWMS

[English version](README.md)

architecture-tests — executable policy module для dependency direction и
source-level architectural rules. Он не реализует runtime behavior и не
заменяет owning-service contract, authorization или persistence tests.

## Что проверяет module

Текущие rules проверяют framework neutrality technical-contracts, constructor
injection и placement/boundaries MapStruct во всех активных Java services. Они
защищают service-model ownership для inventory, logistics, dossier, assistant и
analytics; не допускают в gateway зависимости на service packages,
JPA/repositories, DataSource/JDBC, Spring Data и Kafka; и отклоняют POST, PUT,
PATCH, DELETE или method mappings без ограничения HTTP verb в boundaries
analytics и dossier. Source policies также защищают selected service boundaries
от direct dependencies на source trees других services, raw persistence
shortcuts и forbidden infrastructure.

`GodClassSourcePolicy` защищает выполненную структурную декомпозицию. Он
ограничивает именованные compatibility facades и поверхности их конструкторов,
отклоняет dependency-heavy или
слишком крупные `*Support`, `*UseCases` и coordinator collaborators и не даёт
manager Android-клиенту вернуть универсальный наследуемый coordinator или
late-bound registry координаторов. Большой декларативный DTO container не
отклоняется только по числу строк; новый executable hotspot должен одновременно
иметь сигналы размера и связанности/широкой поверхности методов.

Module импортирует services, явно объявленные в Gradle test dependencies и test
classes. Текущее broad ArchUnit coverage включает auth, task-board, warehouse,
asset, maintenance, inventory, logistics, dossier, assistant, analytics и
api-gateway. Добавление service в settings не добавляет coverage автоматически.

Отдельный canonical-contract integrity gate разбирает каждый YAML- и
JSON-source в `contracts/`, разрешает только ограниченные repository-local file
и JSON Pointer references и компилирует каждый объявленный JSON Schema draft.
Внутри каждого owning document он отклоняет пустые или дублирующиеся OpenAPI
operation IDs, дублирующиеся или неверно оформленные event message names,
неверные path-bound schema IDs, отсутствующие и remote references и выход за
repository path. Существующие schemas `events/technical` сохраняют точный
namespace `rwms.example`; остальные event schemas используют точный repository
path в `rwms.local`. Этот структурный gate дополняет, а не заменяет focused
producer/consumer compatibility tests.

## Использование

Запустите module из repository root:

    bash ./gradlew :platform:architecture-tests:test

Запустите deterministic canonical-contract gate из repository root:

    bash ./gradlew verifyCanonicalContracts

Изменение policy по возможности должно включать safe fixture и unsafe fixture,
чтобы test доказывал и intended rule, и failure mode. Не используйте
architecture test, чтобы разрешить cross-service database dependency, shared
domain model, gateway-owned workflow или projection-owned command.

## Безопасные изменения

Перед расширением rule:

1. определите canonical contract и owning-service boundary, который он защищает;
2. добавьте target module dependency и explicit package import, если нужно
   покрыть новый service;
3. оставьте rules независимыми от application runtime configuration и external
   infrastructure; и
4. запустите focused service tests вместе с этим module, когда rule защищает
   persistence, event или security invariant.

Основные references: [PlatformArchitectureTest](src/test/java/dev/buhanzaz/rwms/architecture/PlatformArchitectureTest.java),
[ArchitectureRules](src/test/java/dev/buhanzaz/rwms/architecture/ArchitectureRules.java),
[DossierSourcePolicy](src/test/java/dev/buhanzaz/rwms/architecture/DossierSourcePolicy.java)
[GodClassSourcePolicy](src/test/java/dev/buhanzaz/rwms/architecture/GodClassSourcePolicy.java)
и [CanonicalContractIntegrityGate](src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityGate.java).
