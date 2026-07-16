# Unified Browser MOCK Task Board And Group Schedules

Date: 2026-07-12

## Scope And Authority

This document describes the current development browser MOCK. It is an approved
target prototype behind feature ports, not proof of a production
`task-board-service` contract. `DEV_AUTH_BYPASS_ENABLED` selects the browser
client; production retains authenticated HTTP clients and fails closed.

The mock intentionally unifies the development-only operational projection that
was previously split across task settings, repair tasks, and logistics task
producers. Browser storage is adapter-private and must not be read from React
components.

## Aggregate And Concurrency

`BrowserTaskBoardClient` owns one schema-versioned envelope containing:

- queue registry and order;
- worker classes, workers, qualifications, groups, and memberships;
- group schedules;
- tasks, routes, assignments, and independent pause reasons;
- interruption links and per-worker notifications;
- a visible demonstration clock and append-only mock audit entries.

Updates and destructive commands require `expectedVersion` and expose a conflict
equivalent to HTTP 409. Initial task registration is idempotent by
`externalTaskId`. Repair tasks use an explicit synchronization command and
stable source-step IDs; completed route prefixes keep execution metadata while
valid future amendments are reconciled. This browser recovery behavior does not
define a distributed transaction.

No password material is persisted. Mock worker credential actions may update
only credential status and audit state.

## Additive Development Seed

Seed values use stable IDs and are merged only when records are absent. User
changes are not overwritten.

Global classes are `DRIVER`, `GENERAL_WORKER`, `RIGGER`, `ELECTRICIAN`,
`PLUMBER`, `WELDER`, and `SES`. SPB contains:

- drivers Алексей Соколов and Дмитрий Орлов;
- two groups of two general workers;
- two groups of two electricians;
- two groups of two plumbers;
- one two-person welder group;
- one two-person SES group.

General workers also receive `RIGGER`. Drivers are a logistics directory and do
not take operational-board assignments. MSK initially has no workforce.

SPB and MSK receive the same ordered queue registry: movement, internal work,
external work, electricity, plumbing, welding, SES/sanitary work, and HOLDING.
Movement is bound to `GENERAL_WORKER` with `stopTaskOnTake=true`; the specialist
queues bind to their corresponding classes. HOLDING stays last and is not
manually assigned.

Stable marked DEMO tasks exercise movement, general work, specialist work, and a
future HOLDING stage. They reference existing cabin numbers but do not mutate
cabin state or create dossier history.

## Schedules And Time

The sixth development-only settings tab, `График`, edits one group schedule per
warehouse. The initial schedule is:

- Monday-Friday, 09:00-18:00;
- smoke breaks 11:00-11:10 and 16:00-16:10;
- lunch 13:00-14:00;
- warning five minutes before each rest;
- automatic rest pause enabled;
- Saturday/Sunday disabled;
- movement return grace three minutes.

Days may follow the shared template, detach on individual edit, relink, or copy
to selected days/groups. Rest periods move/resize in five-minute steps and are
validated against overlap, shift bounds, zero duration, and invalid time zones.
The timeline supports pointer, keyboard, and horizontally scrollable mobile use.

The MOCK clock supports real time and a visible simulation at x1, x60, or x300.
It is a testing surface, not production scheduling authority.

## Pauses, Movement, And Notifications

Pause causes are separate records: `MANUAL`, `SCHEDULE`, and `INTERRUPTION`.
Execution resumes only after every cause is removed.

Taking a configured movement task interrupts active work that shares the
selected workers. The client creates interruption links and per-worker notices
and removes interrupted time from productive work. Completing movement changes
the affected work to `RETURNING`; the timer remains stopped until the group
confirms `Вернулась бригада` or its grace expires. Schedule/manual pause causes
remain effective across return.

Schedule warnings, starts, and ends are deduplicated per worker. Auto-pause is
applied only for periods that request it. The local worker selector/inbox and
sonner messages are MOCK UI; no operating-system Browser Notification API is
used.

## Repair And Logistics Integration

Development repair tasks are registered and reconciled into the unified board by
stable `repair:<id>` external identity. All non-cancelled source subtasks remain
in the synchronized route so a completed prefix does not shift later stages.

Development shipment preparation, warehouse transfer, and cabin-content
transfer use browser task adapters. They select the first ordered active visible
`MOVEMENT` queue that is bound to `GENERAL_WORKER`. Production continues through
the existing authenticated HTTP clients.

## Evidence

- `panel/src/features/task-board/mock/model.ts`
- `panel/src/features/task-board/mock/seed.ts`
- `panel/src/features/task-board/mock/store.ts`
- `panel/src/features/task-board/mock/browser-task-board-client.ts`
- `panel/src/features/task-board/api/task-board-api.ts`
- `panel/src/features/settings/task-board/api/task-board-settings-api.ts`
- `panel/src/features/settings/task-board/schedule-settings.tsx`
- `panel/src/features/settings/task-board/schedule-timeline.tsx`
- `panel/src/features/task-board/task-board-page.tsx`
- `panel/src/features/task-board/task-board-mock-toolbar.tsx`
- `panel/src/features/repair-tasks/api/repair-tasks-api.ts`
- `panel/src/features/logistics/adapters/browser-logistics-preparation-task-client.ts`
- `panel/src/features/logistics/warehouse-transfers/adapters/browser-warehouse-transfer-task-client.ts`
- `panel/src/features/rental-items/contents-transfer/adapters/browser-contents-transfer-task-client.ts`

## Production UNKNOWNs

- schedule evaluator ownership, durable clock/time-zone/DST behavior, downtime
  catch-up, and distributed scheduler coordination;
- final schedule/interruption/notification HTTP DTOs, persistence, authorization,
  optimistic conflict, and migration contracts;
- worker-app push/polling, offline delivery, acknowledgement, and deduplication;
- repair/logistics/task-board outbox, retry, and reconciliation boundaries;
- any production migration of browser seed data or DEMO tasks.
