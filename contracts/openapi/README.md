# HTTP API Contracts

This directory contains the canonical handwritten OpenAPI specifications for
synchronous RWMS APIs. One owning service has one specification family; there
is no cross-domain aggregate API schema.

## Current Specifications

- `auth-service.yaml`
- `warehouse-service.yaml`
- `task-board-service.yaml`
- `media-service.yaml`
- `asset-service.yaml`
- `maintenance-service.yaml`
- `inventory-service.yaml`
- `logistics-service.yaml`
- `dossier-service.yaml`
- `analytics-service.yaml`
- `assistant-service.yaml`

`api-gateway-service` is a stateless router. It does not redefine downstream
business contracts.

## Specification Rules

Each OpenAPI source defines, where applicable:

- API version, owning service and stability state;
- audience, OAuth scopes and warehouse-access requirements;
- opaque identity formats without database relationships;
- request idempotency and retry behavior for mutable commands;
- optimistic concurrency through expected versions or fencing tokens;
- stable validation, authorization, not-found, conflict and dependency errors;
- pagination, sorting, filtering, timezone and units;
- lifecycle status values owned by the service;
- deprecation and compatibility notes.

Use the repository's canonical Problem Details conventions. Examples use
synthetic identifiers and never contain credentials or production data.

## Generation Policy

The YAML file is the canonical handwritten source unless a current component
document explicitly records another source-of-truth decision. Generated
Java/TypeScript interfaces are outputs: regenerate them deterministically,
check for drift, keep adapters around transport types and never use them as JPA
entities or a shared domain dependency.

Clients consume APIs through feature ports/adapters and the public gateway.
They must not fall back to browser persistence when a production request fails.

## Change Gate

Before editing a specification, trace the producer and every active web,
Android, service or integration consumer. Breaking meaning, ownership, status,
identity, money or time semantics require an explicit product decision. Run
schema validation and focused producer/consumer compatibility tests after the
change.
