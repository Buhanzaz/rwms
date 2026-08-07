# Open Product And Architecture Questions

Use this file only for unresolved contradictions or product decisions that
block a safe implementation. This is not a backlog and does not authorize
work.

## Resolved: Return Estimate Furniture Loss Accounting

- Status: `Resolved`
- Affected owner and consumers: logistics-service, maintenance-service and
  asset-service; panel and manager Android app return/estimate flows.
- Requested behavior: the return action should open a new linked estimate
  without collecting shortages first. Furniture selected in that individual
  estimate must enter a separately approved loss decision; for a legacy cabin
  with no recorded composition, an operator may explicitly proceed without
  decrementing warehouse additional-equipment stock.
- Conflicting contract or invariant: resolved without changing ownership.
  Logistics owns the per-line return workflow and maintenance owns estimate
  completion and loss decisions; asset-service remains the only owner of a
  normal custody/balance effect.
- Evidence:
  [`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
  [`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
  [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml),
  [`LogisticsDocumentService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java),
  [`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
  [`PropertyDispositionApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java).
- Smallest decision needed: none.
- Resolution and date: 2026-08-07 — start one `DRAFT` estimate per return
  line from photo proof only. A normal cabin uses fenced asset custody and a
  later approval; an empty legacy cabin requires an explicit completion retry,
  then creates `UNACCOUNTED` maintenance decisions that become effective
  without an asset-service or warehouse-balance effect.

## Retention And Archive Policy For Immutable Operational Evidence

- Status: `Open`
- Affected owner and consumers: warehouse, asset, maintenance, inventory,
  logistics and task-board services; operations/compliance readers.
- Requested behavior: bound database growth without weakening reconstructable
  write-off, correction, recovery, movement and warehouse-lifecycle history.
- Conflicting contract or invariant: current event, outbox, inbox, snapshot and
  recovery-audit rows are intentionally immutable, while no approved retention
  duration, legal hold, archive target or deletion authority exists.
- Evidence:
  [`20260804-property-warehouse-equipment-write-off-audit.md`](../reviews/20260804-property-warehouse-equipment-write-off-audit.md),
  [`V33__administrative_asset_corrections.sql`](../../services/asset-service/src/main/resources/db/migration/V33__administrative_asset_corrections.sql),
  [`V6__warehouse_outbox_reviewed_recovery.sql`](../../services/warehouse-service/src/main/resources/db/migration/V6__warehouse_outbox_reviewed_recovery.sql).
- Smallest decision needed: retention period per record family, legal/audit hold
  rules, archive storage and verification format, restore procedure, and the
  role allowed to authorize irreversible removal.
- Resolution and date: none. Operational backlog/age metrics are present, but
  this implementation deletes or archives no production evidence.

## Question Template

### Short question title

- Status: `Open` or `Resolved`
- Affected owner and consumers:
- Requested behavior:
- Conflicting contract or invariant:
- Evidence:
- Smallest decision needed:
- Resolution and date:
