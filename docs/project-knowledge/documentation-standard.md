# Documentation Standard

Status: Confirmed repository documentation policy as of 2026-08-09.

Primary evidence and examples:

- [`AGENTS.md`](../../AGENTS.md)
- [`auth-service/README.md`](../../services/auth-service/README.md)
- [`auth-service/README.ru.md`](../../services/auth-service/README.ru.md)
- [`api-gateway-service/README.md`](../../services/api-gateway-service/README.md)
- [`api-gateway-service/README.ru.md`](../../services/api-gateway-service/README.ru.md)

This page defines the durable format for current RWMS documentation. It does
not replace canonical contracts, service code, Flyway migrations, tests, or the
domain owner. Comments and READMEs explain verified intent and operating rules;
they must not invent behavior.

## Language policy

Every active deployable and interactive client has a paired root README:

- `README.md` is the English version and starts with a link to `README.ru.md`;
- `README.ru.md` is the Russian version and starts with a link to `README.md`;
- both versions use the same section order and describe the same facts,
  warnings, commands, and references;
- identifiers, paths, configuration keys, endpoint names, status values, and
  code remain unchanged between translations;
- a changed fact is updated in both versions in the same task.

The maintained architecture knowledge pages remain English so repository links
and architectural terms have one searchable form. A product lifecycle guide
may additionally have an explicitly linked `.ru.md` companion when both files
are maintained as one pair; the English page remains the knowledge-base index
entry. A service README may explain the same fact in both languages, but the
canonical contract or owner source is still authoritative.

## README structure

Use the following order, omitting only a section that is genuinely irrelevant:

1. service/client name and link to the paired language;
2. one-paragraph purpose and explicit owner boundary;
3. why the component exists and what it deliberately does not own;
4. request, command, event, or UI flow;
5. internal application/component structure when it is not obvious from one
   cohesive owner;
6. public/internal API or navigation surface with links to canonical contracts;
7. security and warehouse-isolation rules;
8. persistence, messaging, caching, concurrency, and recovery behavior;
9. observability and operational failure behavior;
10. local development using dev-only defaults;
11. required production configuration without secret values;
12. focused verification commands;
13. safe-change checklist and primary implementation references.

Prefer ownership and failure semantics over a directory dump. Tables are useful
for route maps, statuses, dependencies, and mappings; a short flow diagram is
useful when it clarifies transaction or message order. Do not copy complete
schemas that will drift.

## JavaDoc and KDoc

Every newly added real Java or Kotlin type declaration has a documentation
comment. This includes package-private, private, nested and local classes,
interfaces, records, enums and objects, not only top-level API types. Existing
production source keeps documentation current for every public or
architecture-significant type. Applications, controllers, request/response
boundaries, services, domain aggregates and status enums, ports, adapters,
repositories with non-obvious semantics, configuration and safety validators,
event producers/consumers, outbox/inbox/replay components, security policies,
and mappers are always architecture-significant.

A useful type comment answers the applicable questions:

- what responsibility does this type own;
- which invariant, transaction, ordering, idempotency, or security boundary it
  preserves;
- whether it is a command owner, projection, adapter, or transport type;
- which failures are retried, quarantined, surfaced, or deliberately rejected;
- which type or canonical contract is the primary companion reference.

Public methods need JavaDoc/KDoc when the name and type signature do not fully
explain domain effects, authorization, fencing, idempotency, time semantics,
or recovery. Use `@param`, `@return`, and `@throws` where they add information;
do not restate the signature. Record components and straightforward accessors
do not need one comment per generated accessor when the type and component
names are already precise.

Source comments must not:

- describe an obsolete migration stage as current product behavior;
- promise a retry, ordering, authorization, or contract guarantee that code and
  tests do not enforce;
- call a projection or gateway the owner of a producer aggregate;
- expose credentials, secret material, customer data, or private endpoints;
- contain filler such as "handles data", "service class", or "gets the value";
- preserve dead code by labelling it legacy instead of removing it in the
  authorized implementation task.

## GoDoc and TypeScript documentation

Exported Go declarations use standard GoDoc sentences beginning with the
declaration name. Document ownership, persistence, storage, retry, and worker
lifecycle where relevant.

TypeScript does not require a comment on every component or helper. Use TSDoc
for exported architectural ports, adapters, security/session boundaries,
cache-key policy, streaming/reconnect behavior, and non-obvious domain effects.
UI text and component names are not a substitute for canonical contract links.

## Contract and schema comments

OpenAPI descriptions, JSON Schema annotations, and AsyncAPI descriptions are
part of the boundary contract. They must use current product language, identify
the owner, and describe compatibility, security, versioning, ordering, and
replay behavior accurately. Historical wave, stage, or cutover markers belong
in history documents, not in a current canonical schema unless a live
compatibility path still consumes them.

Changing contract wording is not always documentation-only: a description may
define security, ownership, status, retry, or compatibility meaning. Verify the
producer, every active consumer, and contract tests before changing it.

## Evidence and links

- Link to repository-relative canonical sources.
- Cite the smallest stable source: contract operation, owner service class,
  migration family, or focused test.
- Use exact `path:line` evidence in an audit, but avoid durable prose that
  depends on line numbers alone.
- Mark implemented facts as `Confirmed`, intended work as `Proposed`, and
  unresolved product choices in [`open-questions.md`](open-questions.md).
- Historical documents must be labelled as evidence only.

## Verification gate

For a documentation change:

1. verify that every local link resolves;
2. compare paired README headings, commands, warnings, and references;
3. run `git diff --check`;
4. compile or run Javadoc/KDoc/Go tests for changed source comments when
   comments touched production code;
5. run the narrow architecture or contract test when documentation records a
   boundary guarantee;
6. update [`change-log.md`](change-log.md) when the documented durable
   architecture, owner, invariant, or contract map changes.

For newly added Java/Kotlin sources, verification scans actual declarations,
including nested/local types, and proves that each is immediately documented.
Merely finding one `/**` block somewhere in a file is not coverage. Synthetic
source embedded inside a test string is not counted as a repository type.

The documentation is complete only when it is consistent with current code and
contracts. A generated comment count is a useful coverage signal, not proof of
quality.
