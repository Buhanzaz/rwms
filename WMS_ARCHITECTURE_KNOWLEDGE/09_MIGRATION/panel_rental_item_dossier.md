# Panel Rental Item Dossier

Date: 2026-07-12.

## Current Target

- `/warehouse/:rentalItemId` is a frontend dossier composed through
  `RentalItemDossierClient` over existing browser adapters. No new Spring, Go,
  MinIO, RabbitMQ, or production history/media service was introduced.
- The dossier aggregates only facts proven by current inventory, estimate,
  repair, shipment, accepted return-receipt, and manual dossier records.
  Historical gaps remain gaps.
- Photo folders are event-centric. General dossier and history consumers receive
  only thumb/preview URLs; a separate lazy media command resolves originals only
  inside estimate, inspection, or repair-work viewers after the user preference
  is enabled.
- Cabin versioning protects manual status, photo, and general-comment commands.
  Manual status is restricted to the approved canonical set and requires a
  reason.
- Estimate and direct-repair creation use additive typed React Router state to
  preselect the cabin without changing the existing editor contracts for return
  estimates or repair rework.
- Logistics links filter a specific shipment/receipt and visually identify the
  requested return line. Accepted inbound lines are inspection and movement
  evidence; pending lines are excluded.

## Legacy Mapping

- Legacy `RentalItemEvent`/`RentalItemEventPhoto` prove the event-centric history
  and media grouping shape.
- Current target activity DTOs are not a claim that a production event service
  exists. They are adapter contracts designed so a future service can replace
  browser storage without rewriting the dossier UI.
- Legacy IO/RabbitMQ/Go/MinIO processing proves semantic media variants. The
  current browser adapter only models that lifecycle and stores one source Blob.

## Verification

- Final frontend suite: 15 files / 66 tests passed.
- Typecheck, ESLint, Prettier check, production build, and `git diff --check`
  passed. Build emitted only the known large-chunk warning.
- Browser QA covered desktop, tablet, and mobile; URL tabs, empty states, typed
  seeds, Back behavior, photo-folder selection, fullscreen keyboard navigation,
  real mouse drag, and responsive non-overlap. Console errors/warnings were zero.

## 2026-07-12 Dossier Presentation Refinement

- The target deliberately does not restore legacy hardcoded overview facts.
  Current warehouse/status, dossier projections, client/shipment snapshot,
  characteristics, and contents are rendered; unknown location/readiness,
  planned inspection, and reservation history remain absent.
- The dossier hero is capped at 420px from tablet upward. A flexible photo and
  internally scrolling complete passport replace the former unbounded hero.
- Projection tabs share one responsive register rule: `OperationsListGrid` at
  `lg`, cards below `lg`. Photos additionally provide a wide-screen
  table/gallery toggle. This differs from narrower registry pages because the
  persistent sidebar leaves insufficient table width at a 900px viewport.
- Reservation and return contracts remain empty. Their filters and empty grid
  headers are presentation scaffolding only and must not be promoted into DTOs
  or backend schema without an approved service contract.
- Final verification passed 16 frontend files / 69 tests, typecheck, ESLint,
  production build, and diff checks. Browser measurements at 900x1000 showed a
  420px hero, tabs in the first viewport, and exact viewport/document width.
  At 390x844 the document matched the viewport width, desktop grids were hidden
  in favor of folders/cards, and the console reported no errors or warnings.

## 2026-07-12 Mobile Register Refinement

- Mobile projection emptiness is now represented by muted text rather than a
  framed Card; desktop empty OperationsListGrid headers are unchanged.
- The shared register filter supports an exact displayed day or an inclusive
  date range through labelled `Дата с`/`Дата по` fields with calendar icons.
  Filtering derives local calendar components from each Date so events around
  midnight match the date rendered to the operator.
- Photo folders use one full-width mobile column. Filters use the full content
  width and no longer share a shrinking flex row with the add-photo action.
- This is presentation/filter-state behavior only. It changes no dossier DTO,
  backend contract, media ownership, or historical data claim.

## 2026-07-12 Fullscreen Photo Viewer Alignment

- Fullscreen media slides use equal horizontal insets to keep the contained
  image centered on a mobile viewport.
- The live `N / total` counter is immediately above the grouped previous, next,
  and rotate controls, so it remains visible while the operator changes photos.

## 2026-07-12 Register Search, Density, And Contents Transfer

- Dossier projections now use warehouse-style smart search and compact filter
  chips without changing the browser DTO ownership boundary. Search is local
  fuzzy matching, not evidence of a production search endpoint.
- Photo density and the warehouse grid share the current 1/3/5 responsive
  helper. The older wider tablet/desktop caps are superseded.
- The raw version field was removed from the passport; the newest proven
  activity supplies the displayed date and actor. Missing proof remains
  `Не зафиксировано`.
- Cabin contents transfer now coordinates the existing task-board service with
  browser rental storage through persistent attempt phases and dual-cabin CAS.
  This is migration scaffolding, not a production distributed transaction or
  a new backend ownership claim.
- New `CONTENTS_TRANSFERRED` activity rows are derived only from `APPLIED`
  attempts. A dead task-board deep link was deliberately omitted until the
  operational board consumes service-owned standalone tasks.

## 2026-07-12 Dossier Comments And Passport Layout

- At desktop width, the editable cabin comment and the append-only history-note
  entry form occupy a single two-card row. The registered comments remain a
  separate lower projection; phone layout stacks the two forms.
- Supersede the former fixed 420px desktop hero cap. The desktop hero gives its
  photo region 560px of height when needed, while the passport remains a single
  compact column with no internal scroll. This keeps the complete right-side
  identity, actions, and information visible as one surface rather than hiding
  facts or splitting them into two columns.
- This is a presentation-only refinement. No dossier DTO, browser-adapter
  command, history mutability, or media contract changes.
