# Panel Rental Logistics

## Scope

The target panel exposes two warehouse-scoped routes:

- `/logistics/returns` — `Возврат из аренды`;
- `/logistics/shipments` — `Отгрузка в аренду`.

These workflows are approved target product behavior. They are not a direct copy of one complete Jmix logistics aggregate.

## Legacy Evidence

Legacy provides reusable domain evidence:

- rental statuses include `IN_RENT`, `NEED_INSPECTION`, and `READY`;
- rental event types include `BEFORE_RENT` and `AFTER_RENT`;
- rental events support photos;
- reservations contain a company, multiple rental items, and accessories;
- workers can have the `DRIVER` classification.

No complete legacy return/shipment screen, tenant-match command, shipment driver aggregate, mandatory-photo rule, or atomic lifecycle coordinator was found. Those target contracts remain mock-only until a service contract is approved.

## Current Target Mapping

Return registration validates one unique current `RENTED` cabin and its tenant snapshot, records date/from/author, and changes the cabin to `AFTER_RENT`. Acceptance requires 1-20 photos and changes the cabin to `FREE`. Estimate handoff uses the standard estimate editor and independent copies of the audit photos.

Shipment creation selects an active movement worker as driver and one or more `FREE` cabins. It records company/date/driver and a contents snapshot, then changes the cabins to `RENTED` with tenant and shipment date. It does not mutate accessory stock.

The browser adapters serialize rental lifecycle writes with one shared lock and source-status CAS. Return-to-estimate saves use a versioned claim, exact linkage idempotency, and a mock-only ten-minute stale-claim recovery window.

## UI

Both routes reuse `PageToolbar`, `OperationsListGrid`, shadcn fields/dialogs, the existing warehouse scope, and the standard header/sidebar. Empty desktop grids fill the available workspace and keep the header visible; mobile uses natural flow and cards/dialogs.

## V2 Grouping And Preparation

Schema v2 migrates every legacy return into a one-item receipt and preserves its media, estimate IDs, claim fields, and audit snapshots. New receipts contain multiple lines and expose no header status. The UI derives each cabin stage and renders damage/estimate facts separately.

Shipment editing stores `contentsBefore`, `contentsPlanned`, and deterministic `BRING/TAKE` changes. A changed cabin must have a standalone preparation task. The panel resolves a real active task-board queue with a `GENERAL_WORKER` binding and posts a stable external task UUID. `BoardEntryDto.externalTaskId` is additive and supports exact 409 recovery.

The current browser coordinator persists a `PREPARING` shipment and a `PENDING` attempt before network dispatch. A 30-second mock lease permits recovery from a crashed attempt. Success and failure use exact external/attempt CAS, and only a fully dispatched current plan can become `SHIPPED` and move its cabins to `RENTED`. This is a target mock/saga prototype, not the final distributed transaction contract.

## Soft Company Entry

During the current mock/test phase, return and shipment company fields are
creatable comboboxes: existing suggestions may be selected, while arbitrary
trimmed text is applied with Enter and retained as the document snapshot. The
dropdown portal is hosted inside the modal Dialog so pointer and focus handling
remain compatible. A future strict directory-backed mode is approved only as a
direction; its settings contract and backend owner remain `UNKNOWN`.

## Return Correction And Equipment Quarantine

An unprocessed return receipt is versioned and may be corrected after creation.
Sender and return date remain editable; cabin membership is editable only while no
inspection/estimate/repair child workflow or equipment-resolution history exists.
Removing a pristine line restores its cabin from `AFTER_RENT` to `RENTED`, while an
added line must still satisfy the original rented-cabin validation.

Contents attached to a returned cabin create deterministic quarantine cases in the
existing `Списание -> Доп. оборудование` operational grid. Each remaining quantity
must be returned to warehouse stock, written off with a reason, or transferred to an
eligible cabin. Acceptance without damage, shipment, and lower-level inventory moves
fail closed until the source/target cabin has no unresolved quarantine. Browser-store
commit/rollback and recovery are mock saga evidence only and must not be promoted into
a production service contract.

## Return cabin card parity (2026-07-11)

`Возврат из аренды -> Добавить возврат` now mirrors shipment creation's cabin-card interaction: numbered cards, a single cabin Combobox per card, remove controls for added cards, and `Добавить ещё бытовку`. The return-specific candidate groups distinguish the selected client's cabins from other rented cabins, and the dialog preserves `CLIENT_LIST` versus `MANUAL` when submitting.

Evidence: `panel/src/features/logistics/logistics-returns-page.tsx`.

## Factual return contents and shipment-form parity (2026-07-11)

New returns use the same visual and input composition as shipment creation:
party, return date, an active movement driver, numbered cabin cards, and a
shared furniture/quantity editor. A return records factual received contents,
not a preparation plan; it never generates a shipment-preparation task.

Each factual line stores an expected snapshot plus a received snapshot. On a
fully received undamaged return, the received snapshot becomes the cabin
contents. A short received snapshot requires a return estimate; when linked,
the received snapshot becomes the cabin contents and the expected-minus-received
delta is recorded as an equipment loss. This is approved target browser-adapter
behavior, not a proven legacy workflow or production service contract.

Previously persisted lines continue in `LEGACY_QUARANTINE` mode and retain the
existing disposition queue. No legacy browser record is silently transformed.

Evidence:

- `panel/src/features/logistics/logistics-cabin-contents-editor.tsx`
- `panel/src/features/logistics/logistics-returns-page.tsx`
- `panel/src/features/logistics/api/logistics-api.ts`
