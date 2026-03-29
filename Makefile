.DEFAULT_GOAL := help
SHELL         := /bin/bash

# ── Variables ─────────────────────────────────────────────────────────────────
COMPOSE       := docker compose
APP_SERVICE   := app
VENV          := .venv
PYTHON        := $(VENV)/bin/python3
PIP           := $(VENV)/bin/pip
PYTEST        := $(VENV)/bin/pytest
UVICORN       := $(VENV)/bin/uvicorn

.PHONY: help build up down restart dev test lint clean logs shell \
	setup db-reset env-check docker-nuke

# ── Help ──────────────────────────────────────────────────────────────────────
help: ## Show available commands
	@echo ""
	@echo "  PS Recommender — build & run commands"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ── Docker ────────────────────────────────────────────────────────────────────
build: ## Build all Docker images (no cache)
	$(COMPOSE) build --no-cache

up: ## Start all services in the background
	$(COMPOSE) up -d
	@echo ""
	@echo "  ✓  App running  →  http://localhost:8000"
	@echo "  ✓  API docs     →  http://localhost:8000/api/docs"
	@echo ""

down: ## Stop and remove containers
	$(COMPOSE) down

restart: ## Restart the app service only
	$(COMPOSE) restart $(APP_SERVICE)

logs: ## Follow app container logs
	$(COMPOSE) logs -f $(APP_SERVICE)

shell: ## Open an interactive shell inside the app container
	$(COMPOSE) exec $(APP_SERVICE) bash

# ── Local dev (no Docker) ─────────────────────────────────────────────────────
setup: ## Create virtualenv and install all dependencies
	python3 -m venv $(VENV)
	$(PIP) install --upgrade pip -q
	$(PIP) install -r requirements.txt -q
	@echo ""
	@echo "  ✓  Virtualenv ready. Run  make dev  to start the server."
	@echo ""

dev: ## Run the FastAPI dev server locally (hot-reload, MOCK_MODE=true)
	MOCK_MODE=true \
	DATA_DIR=./data \
	PYTHONPATH=. \
	$(UVICORN) app.main:app \
	  --reload \
	  --host 0.0.0.0 \
	  --port 8000

# ── Tests ─────────────────────────────────────────────────────────────────────
test: ## Run the full test suite
	MOCK_MODE=true \
	DATA_DIR=./data \
	PYTHONPATH=. \
	$(PYTEST) tests/ -v

test-ci: ## Run tests with JUnit XML output (for CI)
	MOCK_MODE=true \
	DATA_DIR=./data \
	PYTHONPATH=. \
	$(PYTEST) tests/ -v --junitxml=test-results.xml

# ── Cleanup ───────────────────────────────────────────────────────────────────
clean: ## Remove containers, volumes, caches, and compiled Python files
	$(COMPOSE) down -v --remove-orphans
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete
	rm -rf .pytest_cache test-results.xml
	@echo "  ✓  Cleaned."

docker-nuke: ## Force-remove all running containers/images and reclaim Docker disk space
	-docker rm -f $$(docker ps -aq)
	-docker rmi -f $$(docker images -aq)
	docker system prune -af --volumes
	@echo "  ✓  Docker containers/images/cache removed."

db-reset: ## Delete the local SQLite database (forces re-init)
	rm -f data/ps_recommender.db
	@echo "  ✓  Database reset. Restart the server to re-create tables."

# ── Utilities ─────────────────────────────────────────────────────────────────
env-check: ## Print resolved environment configuration
	@echo ""
	@$(PYTHON) -c "\
from app.core.config import get_settings; \
s = get_settings(); \
[print(f'  {k:<28} {v}') for k, v in s.model_dump().items()]"
	@echo ""
