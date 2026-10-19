# RWMS Architecture Tests

[Русская версия](README.ru.md)

architecture-tests is the executable policy module for dependency direction and
source-level architectural rules. It does not implement runtime behavior and
does not replace owning-service contract, authorization or persistence tests.

## What the module verifies

The current rules check technical-contract framework neutrality, constructor
injection and MapStruct placement/boundaries across every active Java service.
They enforce service-model ownership for inventory, logistics, dossier,
assistant and analytics; keep the gateway free of service-package,
JPA/repository, DataSource/JDBC, Spring Data and Kafka dependencies; and reject
POST, PUT, PATCH, DELETE or verb-unbounded method mappings in analytics and
dossier HTTP boundaries. Source policies also protect selected service
boundaries from direct dependencies on other service source trees, raw
persistence shortcuts and forbidden infrastructure.

`GodClassSourcePolicy` protects the completed structural decomposition. It
caps the named compatibility facades and their constructor surfaces, rejects
dependency-heavy or oversized
`*Support`, `*UseCases` and coordinator collaborators, and prevents the manager
Android client from recreating a universal inherited coordinator or a
late-bound coordinator registry. A large declarative DTO container is not
rejected by line count alone; new executable hotspots require both size and
coupling/method-surface signals.

The module imports the services explicitly declared in its Gradle test
dependencies and test classes. Current broad ArchUnit coverage includes auth,
task-board, warehouse, asset, maintenance, inventory, logistics, dossier,
assistant, analytics and api-gateway. Adding a new service to settings does not
make it covered automatically.

The separate canonical-contract integrity gate parses every YAML and JSON
source under `contracts/`, resolves only bounded repository-local file and JSON
Pointer references, and compiles each declared JSON Schema draft. Within each
owning document, it rejects blank or duplicate OpenAPI operation IDs,
duplicate or malformed event message names, wrong path-bound schema IDs,
missing references, remote references and path escapes. The established
`events/technical` schemas keep their exact `rwms.example` namespace; every
other event schema uses its exact `rwms.local` repository path. This structural
gate complements rather than replaces focused producer/consumer compatibility
tests.

## How to use it

Run the module from the repository root:

    bash ./gradlew :platform:architecture-tests:test

Run the deterministic canonical-contract gate from the root with:

    bash ./gradlew verifyCanonicalContracts

A policy change should include a safe fixture and an unsafe fixture where
practical, so the test proves both the intended rule and its failure mode.
Do not use an architecture test to bless a cross-service database dependency,
shared domain model, gateway-owned workflow or projection-owned command.

## Safe changes

Before extending a rule:

1. identify the canonical contract and owning service boundary it protects;
2. add the target module dependency and explicit package import if a new service
   must be covered;
3. keep rules independent of application runtime configuration and external
   infrastructure; and
4. run focused service tests as well as this module when the rule protects a
   persistence, event or security invariant.

Primary references: [PlatformArchitectureTest](src/test/java/dev/buhanzaz/rwms/architecture/PlatformArchitectureTest.java),
[ArchitectureRules](src/test/java/dev/buhanzaz/rwms/architecture/ArchitectureRules.java),
[DossierSourcePolicy](src/test/java/dev/buhanzaz/rwms/architecture/DossierSourcePolicy.java),
[GodClassSourcePolicy](src/test/java/dev/buhanzaz/rwms/architecture/GodClassSourcePolicy.java),
and [CanonicalContractIntegrityGate](src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityGate.java).
