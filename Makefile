.PHONY: dev build logs down seed

dev:
	docker compose up --build

build:
	docker compose build

logs:
	docker compose logs -f

down:
	docker compose down

seed:
	@echo "Seeding local development data..."
	@TOKEN=$$(curl -sf -X POST http://localhost:8080/api/v1/auth/local \
		-H "Content-Type: application/json" \
		-d '{"email":"dev@example.com","password":"conductor"}' \
		| grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4); \
	if [ -z "$$TOKEN" ]; then echo "Login failed — is the stack running?"; exit 1; fi; \
	curl -sf -X POST http://localhost:8080/api/v1/projects \
		-H "Content-Type: application/json" \
		-H "Authorization: Bearer $$TOKEN" \
		-d '{"name":"Demo Project","description":"Auto-seeded demo project"}' \
		> /dev/null && echo "Seed complete (dev@example.com / conductor)" || echo "Project may already exist (idempotent)"
