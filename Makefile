.DEFAULT_GOAL := help

MVN     ?= ./mvnw
COMPOSE ?= docker compose -f deploy/docker-compose.yml

# Testcontainers needs to find the Docker daemon. Docker Desktop puts its socket where
# the library already looks; Colima, Rancher and podman do not, and the resulting error
# ("Could not find a valid Docker environment") says nothing about the real cause.
# Resolving it from the active docker context makes `make test` work on all of them.
DOCKER_SOCK := $(shell docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)
ifneq ($(DOCKER_SOCK),)
export DOCKER_HOST := $(DOCKER_SOCK)
# Containers that mount the daemon socket (Ryuk, the resource reaper) still expect it at
# the conventional path inside the container regardless of where it lives on the host.
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE := /var/run/docker.sock
endif

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Compile and package (skips tests)
	$(MVN) -B clean package -DskipTests

.PHONY: test
test: ## Run the full suite, including Testcontainers integration tests
	@echo "using DOCKER_HOST=$${DOCKER_HOST:-<default>}"
	$(MVN) -B test

.PHONY: verify
verify: ## Full build with tests
	$(MVN) -B clean verify

.PHONY: run
run: ## Run the app locally against the compose Postgres and Redis
	$(COMPOSE) up -d postgres redis
	$(MVN) -B spring-boot:run

.PHONY: up
up: ## Start the whole stack in Docker
	$(COMPOSE) up --build -d
	@echo "gateway    http://localhost:$${GATEWAY_PORT:-8080}"
	@echo "health     http://localhost:$${GATEWAY_PORT:-8080}/actuator/health"
	@echo "metrics    http://localhost:$${GATEWAY_PORT:-8080}/actuator/prometheus"
	@echo "prometheus http://localhost:$${PROMETHEUS_PORT:-9091}"

.PHONY: down
down: ## Tear down the stack and its volumes
	$(COMPOSE) down -v

.PHONY: logs
logs: ## Follow gateway logs
	$(COMPOSE) logs -f gateway

.PHONY: smoke
smoke: ## Send real requests through a running gateway
	@bash scripts/smoke.sh

.PHONY: format
format: ## Check formatting
	$(MVN) -B spotless:check || true

.PHONY: clean
clean: ## Remove build output
	$(MVN) -B clean
