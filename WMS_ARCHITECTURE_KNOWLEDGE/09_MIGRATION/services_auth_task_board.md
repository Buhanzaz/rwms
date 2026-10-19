# Auth And Task-Board Microservice Migration

Date: 2026-07-11

## Runtime

- Panel: React/Vite, `8080`.
- Auth service: Spring Authorization Server, `9000`, dedicated PostgreSQL/Liquibase.
- Task-board service: Spring Resource Server and OAuth2 client, `8081`, dedicated PostgreSQL/Liquibase.
- Future worker client dev origin: `8082`.

## Panel Authentication

`panel/src/features/auth` uses `oidc-client-ts` with Authorization Code + S256 PKCE, `sessionStorage`, `/auth/callback`, protected application rendering, safe return URLs, Bearer clients, access-token expiry redirect, and RP-initiated logout. `/settings/users` calls auth-service directly. Logout clears local OIDC state before redirect and terminates the server session through `/connect/logout`.

`services/auth-service/ui` is a separate Vite/shadcn login-04 application. Gradle builds it into the auth boot JAR. It contains only login/password/error controls and a generated warehouse/modular-cabin cover; the image is hidden on mobile. The form fetches a masked CSRF token for every mount and posts to Spring Security `/login` on the same origin.

## User Administration

- USER and WORKER remain separate subjects.
- User list/profile/password/access mutations use `version`/`expectedVersion`; stale changes return 409.
- Create includes warehouse accesses atomically. Access replacement force-increments the parent user version.
- UI and backend prevent self-disable, last-system-admin disable, and WMS_ADMIN management of SYSTEM_ADMIN.
- Physical USER deletion remains fail-closed because absence of history and active sessions is not yet provable.

## Task-Board Settings

- Queue settings include type, visibility/collapse flags, HOLDING fields, order, active state, and class bindings with per-binding `stopTaskOnTake`.
- Worker classes are globally managed by system/WMS administration; warehouse managers with MANAGE edit warehouse queues, workers, and groups.
- Workers have explicit qualifications and separate auth credentials. A failed auth call leaves a truthful retryable state; changing/clearing a login publishes the local login only after auth success.
- Groups belong to one warehouse and one class; workers may belong to multiple groups within their warehouse.
- React uses legacy UUIDs `...0001` and `...0002` as `WarehouseInfo.serviceId` while preserving `spb`/`msk` mock IDs for the remaining prototype stores.

## Verification

- Clean root Gradle build passed for both modules with 38 tests total, including PostgreSQL 17 Testcontainers.
- Panel typecheck, full ESLint, and production build passed; auth UI typecheck/lint/build passed.
- Real-browser E2E used isolated PostgreSQL containers and actual services: PKCE login/callback, current user, user create/profile/access/password/deactivate, worker credential provisioning, class/group/worker/queue CRUD, `stopTaskOnTake` binding, dnd order persistence, HOLDING-last, fail-closed deletes, cleanup, logout, and mandatory re-login. Bearer/CORS responses were verified and browser console errors were zero.

## Residual UNKNOWN

- The owner client for internal `queue-registry.write` is not selected.
- Worker initial-password delivery is not designed.
- HOLDING notifications and live board push are not implemented.
- Operational repair-task board cutover from the browser adapter to task-board-service is not implemented.
- Distributed outbox/reconciliation beyond the implemented worker credential/deletion intents remains future work.
