# RWMS Legacy Architecture Knowledge

This folder is the project memory for migration from `wms-panel-old` to `panel`.
The source of truth is the legacy Jmix application under `wms-panel-old`.

Audit date: 2026-07-09.
Scope: read-only audit of `wms-panel-old`.
No legacy source files were changed during this audit.

## How To Use

Future migration agents must read this folder before making migration decisions.
Use `UNKNOWN` in migration plans when a behavior is not documented here and is not evident in legacy source.

Recommended reading order:

1. `01_SYSTEM/README.md`
2. `02_ARCHITECTURE/README.md`
3. `03_DATABASE/README.md`
4. `04_ENTITIES/README.md`
5. `06_BUSINESS/README.md`
6. `05_UI/README.md`
7. `07_SECURITY/README.md`
8. `08_INTEGRATIONS/README.md`
9. `09_MIGRATION/README.md`
10. `10_AGENT_MEMORY/history.md`, `decisions.md`, `unknowns.md`

## High-Level Findings

- Legacy app: Jmix 2.8.1, Java 21, Spring Boot/Jmix FlowUI, EclipseLink/Jmix Data, file HSQLDB by default.
- Legacy is a monolith plus an external Go photo worker in `photo-worker-go`.
- Main domains: warehouses, numbered rental assets, rental classifier dictionaries, dynamic attributes, accessories, reservations, repair estimates, repair processes, work queues, workers, media/history, AI search.
- There are 49 concrete Jmix entities, 31 enum types, 38 service classes, 68 view classes, and 2 REST controllers.
- Mobile API under `/api/mobile/**` is public in legacy and runs as `admin` through `SystemAuthenticator`. This is a migration-critical security risk.
- Formal receiving, picking, shipping, bin/location movement workflows were not found. Mark them `UNKNOWN`/not implemented until proven otherwise.

## Source Evidence

Primary source paths:

- `wms-panel-old/build.gradle`
- `wms-panel-old/src/main/resources/application.properties`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog.xml`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/view`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/web`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/security`
- `wms-panel-old/photo-worker-go`

## 2026-07-16 Asset-service implementation record

The current target implementation adds the asset boundary under
`services/asset-service` and its canonical HTTP/event contracts. Its ownership,
schema, UI cutover and unresolved integration limits are recorded in the
numbered sections below. This is an implementation record, not reconstructed
exit evidence for an earlier stage or a statement that the current gate passed.

## 2026-07-17 Stage 8 logistics implementation record

The backend-only `logistics-service` now owns returns, shipments and transfers
through service-local JPA entities and Flyway V1--V7. Lombok is limited to safe
boilerplate and MapStruct maps read/event projections only. The service records
external saga attempts, source evidence and reconciliation requests locally; it
does not acquire another service's aggregate, table or canonical movement
ownership. Its details and unresolved correction policy are recorded in the
numbered sections below.

The stateless gateway has an explicit `/api/logistics/**` route, but no panel
cutover was performed. Stage 7 is complete in `51460a3`. Stage 8 implementation
is recorded in `08c262f`; its containing closure/fix commit records the final
JPA boundary, V7, replay/outbox recovery evidence and pointer transition. Stage
8 is complete, and Stage 9 dossier service-side implementation is active.
