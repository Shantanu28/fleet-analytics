# Thin wrapper over Maven, npm and Compose. Every target propagates failure.
# No target deletes a database or drops data.
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.PHONY: setup seed dev test e2e db-up db-wait

# Colima puts the Docker socket under ~/.colima/..., but Testcontainers' Ryuk must
# bind-mount the in-VM path (/var/run/docker.sock). Detect that one case from the active
# Docker context so the documented entry points work without editing a shell profile.
# Other VM-backed runtimes are not detected; set DOCKER_HOST and
# TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE yourself if you use one.
DOCKER_ENDPOINT := $(shell docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)
ifneq ($(findstring .colima,$(DOCKER_ENDPOINT)),)
export DOCKER_HOST := $(DOCKER_ENDPOINT)
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE := /var/run/docker.sock
endif

db-up:
	docker compose up -d postgres

db-wait: db-up
	@echo "waiting for PostgreSQL readiness..."
	@for i in $$(seq 1 60); do \
	  if docker compose exec -T postgres pg_isready -U fleet -d fleet >/dev/null 2>&1; then \
	    echo "postgres ready"; exit 0; fi; sleep 1; done; \
	echo "postgres did not become ready" >&2; exit 1

## setup: database ready -> migrations -> jOOQ generation -> frontend deps
setup: db-wait
	./mvnw -pl backend flyway:migrate
	./mvnw -pl backend generate-sources
	cd frontend && npm ci

## seed: explicit M4 installer. Require an intentional target; migrate it separately first.
## The seed launcher imports only datasource configuration and needs no JWT/HMAC secrets.
seed:
	@test -n "$${DB_URL:-}" || { echo "seed: set DB_URL to the intended isolated database" >&2; exit 1; }
	./mvnw -pl backend -Ddb.url="$$DB_URL" -Ddb.user="$${DB_USER:-fleet}" -Ddb.password="$${DB_PASSWORD:-fleet}" spring-boot:run -Dspring-boot.run.main-class=com.fleet.analytics.seed.SeedApplication -Dspring-boot.run.profiles=seed

## dev: run the backend (dev profile, generated keys) and the Vite dev server together.
## Seeds nothing, changes no migrations and deletes nothing: run `make setup` first, and
## `make seed` before using demo accounts. Ctrl-C stops both; either one exiting stops the other and
## fails the target. The launcher signals only the process groups it started itself — never
## group 0, and never a process matched by name or by the port it holds.
dev:
	node scripts/dev.mjs

## test: non-browser checks - backend unit + PostgreSQL integration,
## React component tests, frontend type-check and production build
test:
	./mvnw verify
	cd frontend && npm run test && npm run type-check && npm run build

## e2e: M6. Not implemented yet.
e2e:
	@echo "e2e: not implemented until M6" >&2; exit 1
