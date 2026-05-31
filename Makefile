BACKEND_URL ?= http://localhost:8090
DEV_PASSWORD ?= conductor
EMAIL       ?= dev@example.com

.PHONY: dev build logs down restart reset ps seed account e2e e2e-ui setup cli-install

setup:
	git config core.hooksPath .githooks
	@echo "Git hooks installed."

dev:
	@( until curl -sf $(BACKEND_URL)/api/v1/health > /dev/null 2>&1; do sleep 3; done; \
	   $(MAKE) -s seed && \
	   printf '\n  → http://localhost:3000   $(EMAIL) / $(DEV_PASSWORD)\n\n' ) & \
	docker compose up --build

build:
	docker compose build

logs:
	docker compose logs -f $(SERVICE)

down:
	docker compose down

restart: down dev

reset:
	docker compose down -v --remove-orphans
	$(MAKE) dev

ps:
	docker compose ps

e2e:
	docker compose up -d --wait || true
	cd conductor-frontend && npx playwright test

e2e-ui:
	docker compose up -d --wait || true
	cd conductor-frontend && npx playwright test --ui

cli-install:
	cd conductor-tools && npm run build && npm link

seed:
	@echo "Seeding local development data..."
	@TOKEN=$$(curl -sf -X POST $(BACKEND_URL)/api/v1/auth/local \
		-H "Content-Type: application/json" \
		-d '{"email":"$(EMAIL)","password":"$(DEV_PASSWORD)"}' \
		| grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4); \
	if [ -z "$$TOKEN" ]; then echo "Login failed — is the stack running? (make dev)"; exit 1; fi; \
	curl -sf -X POST $(BACKEND_URL)/api/v1/projects \
		-H "Content-Type: application/json" \
		-H "Authorization: Bearer $$TOKEN" \
		-d '{"name":"Demo Project","description":"Auto-seeded demo project"}' \
		> /dev/null && echo "Done. Login at http://localhost:3000  email=$(EMAIL)  password=$(DEV_PASSWORD)" \
		|| echo "Project may already exist — account ready: $(EMAIL) / $(DEV_PASSWORD)"

account:
	@if [ -z "$(EMAIL)" ] || [ "$(EMAIL)" = "dev@example.com" ]; then \
		echo "Usage: make account EMAIL=you@example.com"; exit 1; fi
	@TOKEN=$$(curl -sf -X POST $(BACKEND_URL)/api/v1/auth/local \
		-H "Content-Type: application/json" \
		-d '{"email":"$(EMAIL)","password":"$(DEV_PASSWORD)"}' \
		| grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4); \
	if [ -z "$$TOKEN" ]; then echo "Failed — is the stack running? (make dev)"; exit 1; fi; \
	echo "Account ready. Login at http://localhost:3000  email=$(EMAIL)  password=$(DEV_PASSWORD)"
