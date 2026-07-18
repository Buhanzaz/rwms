# Stage 10 Analytics Service Contract Evidence

**Status:** `DEFERRED_BY_USER_2026-07-18`

## User deferral and authority

The user explicitly deferred KPI and Stage 10 implementation on 2026-07-18.
This instruction supersedes the earlier general request to work on Stages 9
and 10. Stage 10 has no approved metric baseline and no implementation
authority.

Until the user gives new explicit approval, it is forbidden to create or
modify:

- an `analytics-service` runtime or Gradle module;
- an analytics database, Flyway migration, JPA entity or repository;
- canonical analytics OpenAPI or event-consumer contracts;
- analytics Kafka consumers, projections, replay tooling or gateway routes;
- dashboard/KPI panel adapters, routes, exports or UI tests;
- producer deployables or source facts for analytics purposes.

No formula, rounding rule, timezone boundary, performance budget or KPI value
is approved by this document.

## Confirmed ownership boundary

The approved roadmap reserves `analytics-service` as an asynchronous,
read-only projection owner for future dashboard and KPI surfaces. It must never
participate in an operational command, call a source database, delay an
operational service or become a shared mutable read model.

Its future durable state may exist only in an isolated PostgreSQL database.
Cross-database foreign keys, joins, shared JPA entities and direct reads of a
producer outbox/inbox are forbidden. Kafka facts are transport; a future
analytics design requires its own retained replay evidence.

These ownership rules are evidence constraints, not permission to scaffold the
service.

## Confirmed source evidence

- Current canonical producer families exist for warehouse, asset, inventory,
  maintenance, logistics and task-board facts. They carry sanitized V2
  envelopes and producer-owned fields only.
- Stage 9 is intended to publish an approved sanitized cabin-activity fact for
  cross-domain cabin activity. A future analytics service must consume that
  contract and must never read dossier tables.
- Direct producer facts, rather than dossier-derived copies, remain the only
  valid candidate source for producer-owned warehouse, asset, inventory,
  maintenance, logistics and task-board metrics.
- Duplicate delivery, aggregate gaps, bounded retry, sanitized DLT and replay
  constraints remain platform requirements for any future consumer.
- Browser charts, legacy rows, seed values and generic event counts are not KPI
  evidence.

No listed source proves a formula by itself.

## Unresolved product and contract gaps

The following remain `UNKNOWN` and require a new explicit user decision before
any Stage 10 contract or implementation work:

1. the v1 metric catalog and exact business meaning of every metric;
2. formula inputs, inclusions/exclusions, denominators, null behavior,
   correction semantics and rounding;
3. reporting IANA timezone, daily/weekly/monthly boundaries and DST behavior;
4. dimensions, filters, cross-service correlation rules and whether any
   producer contract must evolve;
5. retention, historical backfill, correction and replay activation policy;
6. freshness SLA, stale-result behavior and performance reference budgets;
7. public API route/DTO/errors, authorization and warehouse visibility;
8. dashboard layout, panel cutover, responsive behavior and export scope;
9. worker/group capacity semantics and any task-to-repair relationship.

The absence of a canonical task-to-repair link forbids inventing a repair
utilization or repair-efficiency metric from task IDs. Worker schedules do not
prove capacity. The Stage 9 dossier projection is not an analytics database.

## Evidence-only model candidate

If Stage 10 is later reauthorized, evidence supports evaluating these isolated
technical record roles, but their exact schema is not approved:

- immutable sanitized source observation keyed by producer event identity;
- inbox deduplication and separate partition/aggregate checkpoints;
- service-owned replay evidence and inactive projection generations;
- formula-versioned materialized period and snapshot projections;
- sanitized quarantine/DLT metadata.

A future replay design must rebuild only analytics-owned projections and must
not destructively clear a live generation. This is an architectural safety
constraint, not approval of a schema or algorithm.

## Gate for resumption

Stage 10 work may resume only after all of the following:

1. Stage 9 is complete, reviewed, committed and the active pointer advances;
2. the user explicitly reauthorizes Stage 10/KPI work;
3. evidence is refreshed against the then-current canonical producer and
   dossier contracts;
4. the metric catalog, periods, authorization, correction/backfill, freshness,
   performance and UI/export decisions are approved;
5. a new approved Stage 10 contract replaces this deferred evidence record.

Until then, no test result, KPI value, schema, API or runtime may be claimed for
Stage 10.
