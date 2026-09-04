# Контракты HTTP API

[English version](README.md)

Этот каталог содержит канонические handwritten OpenAPI specifications для
синхронных API RWMS. У каждого сервиса-владельца своё семейство specification;
общей cross-domain aggregate API schema нет.

## Текущие specifications

- `auth-service.yaml`
- `warehouse-service.yaml`
- `task-board-service.yaml`
- `media-service.yaml`
- `asset-service.yaml`
- `maintenance-service.yaml`
- `inventory-service.yaml`
- `logistics-service.yaml`
- `logistics-planner-service.yaml`
- `dossier-service.yaml`
- `analytics-service.yaml`
- `assistant-service.yaml`

`api-gateway-service` — stateless router. Он не переопределяет downstream
business contracts.

## Правила specification

Каждый OpenAPI source определяет, где применимо:

- API version, сервис-владелец и stability state;
- audience, OAuth scopes и требования warehouse access;
- opaque identity formats без database relationships;
- request idempotency и retry behavior mutable-команд;
- optimistic concurrency через expected versions или fencing tokens;
- стабильные validation, authorization, not-found, conflict и dependency errors;
- pagination, sorting, filtering, timezone и units;
- lifecycle status values, которыми владеет сервис;
- deprecation и compatibility notes.

Используйте канонические conventions RWMS Problem Details. Примеры используют
синтетические identifiers и никогда не содержат credentials или production
data.

## Generation policy

YAML-файл является каноническим handwritten source, если действующий документ
компонента явно не определяет другой source-of-truth. Generated Java/TypeScript
interfaces — выходные данные: генерируйте их детерминированно, проверяйте drift,
сохраняйте adapters вокруг transport types и никогда не используйте их как JPA
entities или shared domain dependency.

Клиенты используют API через feature ports/adapters и публичный gateway. При
ошибке production-запроса они не переходят на browser persistence.

## Gate изменения

До редактирования specification проследите producer и каждый активный web,
Android, service или integration consumer. Изменение breaking meaning,
ownership, status, identity, money или time semantics требует явного
продуктового решения. После изменения запустите schema validation и focused
producer/consumer compatibility tests.
