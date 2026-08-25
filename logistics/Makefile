.DEFAULT_GOAL := help

.PHONY: help up down logs ps reset-db index-truck-restrictions test test-backend test-frontend test-valhalla-truck typecheck lint openapi e2e

help:
	@awk 'BEGIN {FS = ":.*## "; printf "Logistics simulator commands:\n"} /^[a-zA-Z_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

up: ## Build and start the database, backend and frontend.
	docker compose up --build -d

down: ## Stop the simulator without deleting its database volume.
	docker compose down

logs: ## Follow application logs.
	docker compose logs -f backend frontend

ps: ## Show container and health status.
	docker compose ps

reset-db: ## Delete only this simulator's Compose volume and rebuild demo data on next start.
	docker compose down --volumes

index-truck-restrictions: ## Import the configured OSM_DATA_VERSION into the PostGIS map index.
	docker compose run --rm truck-restrictions-indexer

test: test-backend test-frontend ## Run all unit and component checks.

test-backend: ## Run backend unit/property tests and static checks in the image.
	docker compose run --rm backend-test sh -ec 'alembic upgrade head && python -m pytest && ruff check app tests && mypy app'

test-frontend: ## Run frontend unit tests, typecheck, lint and production build.
	docker compose run --rm frontend-test sh -ec 'npm run test:run && npm run typecheck && npm run lint && npm run build'

test-valhalla-truck: ## Build a disposable tagged OSM graph and verify real truck restrictions.
	./scripts/test-valhalla-truck-graph.sh

typecheck: ## Run both Python and TypeScript type checks.
	docker compose run --rm backend-test mypy app
	docker compose run --rm frontend-test npm run typecheck

lint: ## Run Ruff and frontend lint.
	docker compose run --rm backend-test ruff check app tests
	docker compose run --rm frontend-test npm run lint

openapi: ## Regenerate OpenAPI JSON and strict frontend declarations.
	docker compose run --rm backend-test env PYTHONPATH=/app python scripts/export_openapi.py
	docker compose run --rm frontend-test npm run generate:api

e2e: ## Run the Playwright smoke suite against the running Compose application.
	cd frontend && npm run e2e
