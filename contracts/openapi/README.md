# HTTP API Contracts

This directory will contain canonical OpenAPI specifications for synchronous
service APIs. One service owns one specification family. Do not create a single
cross-domain RWMS API schema.

## Planned Names

Specifications are added only during their owning stage:

1. `warehouse-service.yaml`
2. `task-board-service.yaml`
3. `media-service.yaml`
4. no business HTTP API is required for the stateless Go processing worker
5. `asset-service.yaml`
6. `maintenance-service.yaml`
7. `inventory-service.yaml`
8. `logistics-service.yaml`
9. `dossier-service.yaml`
10. `analytics-service.yaml`

The existing `auth-service` standards-based OAuth2/OIDC endpoints remain owned
by that service. Internal administration endpoints must be described in its own
contract family rather than copied into another service specification.

## Specification Rules

Each OpenAPI source must define:

- API version, owning service, and stability state;
- audience, OAuth scopes, and warehouse-access requirements;
- opaque identity formats without database relationships;
- request idempotency and retry behavior for mutable commands;
- optimistic concurrency through expected versions or fencing tokens;
- stable error codes for validation, authorization, not found, conflict, and
  unavailable dependencies;
- pagination, sorting, filtering, business timezone, and units where relevant;
- lifecycle status values from the owning service's status registry;
- deprecation metadata and compatibility notes.

Use problem details or another stage-approved common error envelope, but do not
invent one independently per endpoint. Examples must use synthetic identifiers
and must not contain credentials or production data.

## Generation Policy

The YAML file is the canonical handwritten source unless the owning stage
explicitly records another source-of-truth decision. Generated Java/TypeScript
interfaces are outputs:

- regenerate them in a deterministic task;
- verify that regeneration produces no drift in CI;
- keep adapters around generated transport types;
- never annotate generated transport types as JPA entities;
- never publish generated types as a shared domain dependency between services.

The panel consumes APIs through feature ports/adapters. Cutover from a browser
mock happens only after contract, authorization, integration, and UI tests pass;
production must not silently fall back to browser persistence.
