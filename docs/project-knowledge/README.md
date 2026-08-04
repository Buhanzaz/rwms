# RWMS Current Project Knowledge

This folder is the maintained navigation layer for the current RWMS product.
It records durable architecture, ownership, business invariants, contract
locations and significant changes so future tasks can find authoritative
sources quickly.

It is not a replacement for OpenAPI/event schemas, Flyway migrations, domain
code or tests. Every statement here must be traceable to those sources.

## Authority

Use the source order defined in [`AGENTS.md`](../../AGENTS.md). In particular:

- canonical HTTP and event boundaries live under [`contracts/`](../../contracts/);
- business transitions belong to the owning service under
  [`services/`](../../services/);
- database shape and evolution live in each service's Flyway directory;
- this folder explains and indexes confirmed facts;
- [`docs/plans/`](../plans/) and
  [`WMS_ARCHITECTURE_KNOWLEDGE/`](../../WMS_ARCHITECTURE_KNOWLEDGE/) are
  historical evidence only.

When this folder conflicts with a current authoritative source, correct this
folder. When authoritative sources conflict with one another or with the user
request, record the conflict in `open-questions.md` and ask for a decision.

## Contents

- [`architecture.md`](architecture.md) — deployables, owners and dependency
  boundaries.
- [`domain-logic.md`](domain-logic.md) — confirmed business responsibilities
  and cross-domain invariants.
- [`contracts.md`](contracts.md) — canonical contract index and safe evolution
  procedure.
- [`change-log.md`](change-log.md) — append-only record of durable
  architecture, logic and contract changes.
- [`open-questions.md`](open-questions.md) — unresolved contradictions and
  product decisions.

## Task Usage

1. Read only the relevant knowledge pages.
2. Verify their claims against current contracts, code, migrations and tests.
3. Implement and test the requested change.
4. Update the relevant page when architecture, ownership, domain logic or a
   contract changed.
5. Append a concise change-log row with evidence and verification.

Use `Confirmed` only for behavior backed by current repository evidence. Mark
unimplemented designs as `Proposed`, and do not treat them as permission to
build. Put unresolved choices in `open-questions.md`.

Do not store secrets, credentials, production endpoints, customer information,
full schema copies, generated output or transient debugging notes here.

Last structure review: 2026-08-04.
