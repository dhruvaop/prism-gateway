# Prism deployment guide (plain language)

This guide explains how to build Prism, try it on your computer, and put the demo online. You do not need a paid database or AI account to follow the local steps.

## What is running?

Prism runs as five small services in the Docker deployment:

1. **Gateway** — the Java/Spring Boot service that receives app requests, chooses a model, meters usage, and writes records to the database.
2. **Operations console** — the React website used to view activity and send test prompts.
3. **Alpha mock provider** — a free fake model service used as the first provider.
4. **Beta mock provider** — a second free fake model service used when Alpha fails.
5. **PostgreSQL database** — the shared store for usage, cache, and request records.

The two mock providers return predictable sample responses. They demonstrate the gateway flow; they are not real AI models.

## Easiest way to run all services together

Install Docker Desktop (or Docker Engine with the Compose plugin), open a terminal in the project folder, and run:

~~~sh
docker compose up --build
~~~

The first build downloads Java, Node, Python, and PostgreSQL images and the app libraries. It may take a few minutes. Compose waits for PostgreSQL, applies the database migration, and then starts the gateway. When the services finish starting, open:

- Operations console: http://localhost:8081
- User chat: http://localhost:8081/chat.html
- Gateway health check: http://localhost:8081/health
- OpenAI-compatible API: http://localhost:8081/v1/chat/completions

The deployment database is PostgreSQL 17. Its internal Docker endpoint is `db:5432`, database name `prism`; the gateway connects with `jdbc:postgresql://db:5432/prism`. Flyway applies SQL migrations from `backend/src/main/resources/db/migration/postgresql/` at startup. Those migrations create request logs, monthly usage, cache entries, and persistent seeded virtual keys. Keep PostgreSQL private; application callers use the HTTP gateway endpoint on port 8081, not the database port.

The first page load asks you to **Sign up** or **Sign in**. Sign-up saves your account in `auth_users`, stores only a BCrypt password hash, and returns a signed JWT. The browser keeps that token for the current session and sends it to `/admin/**` endpoints; tokens expire after 24 hours. The OpenAI-compatible `/v1/chat/completions` endpoint continues to use virtual keys. Set a unique random `JWT_SECRET` (at least 32 characters) in the root `.env` before deployment; keep it private and stable across gateway restarts. `JWT_TTL_SECONDS` can change the token lifetime.

Workspace administrators can open **Access** in the operations console to create plans, teams, and users. Each user gets an individual virtual key from their team's plan. Chat users sign in at `/chat.html`; their API key stays on the server. API-only accounts can use the OpenAI-compatible API but cannot sign in to chat.

To use hosted Supabase Postgres instead of the Compose database, open the project and select **Connect → Session pooler**. Use the host, port, database, and username shown there. In the root `.env`, set `DATABASE_URL=jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require`, `DATABASE_USERNAME` to the displayed pooler username, and `DATABASE_PASSWORD` to the database password created for the project. The project URL and publishable/secret API keys are not database credentials. Compose lets these values override its local PostgreSQL defaults; Flyway creates Prism's schema in the hosted database at gateway startup.

To preserve the current local SQLite runtime data, start the gateway once against Supabase so Flyway creates the tables and the seeded keys are loaded, then run `python -m pip install -r requirements-migrate.txt` and `python scripts/migrate_sqlite_to_postgres.py` from the repository root. The one-time importer copies tenant keys, request logs, monthly usage, and cache entries from `prism-java.db`, and leaves the SQLite file unchanged. It refuses to import if the destination already contains runtime history. Set `PRISM_SQLITE_PATH` if your local database uses a different file name.

The browser goes to the console, and the web server forwards API calls to the Java gateway. That is why the same URLs work without setting up a separate API address.

Leave that terminal open while using Prism. To stop the services, press Ctrl+C. The PostgreSQL database is in a Docker volume named prism-postgres, so restarting the containers does not erase usage history. To deliberately erase that demo database, run:

~~~sh
docker compose down -v
~~~

This command deletes the saved Prism database volume. Before making the site public, copy .env.example to .env and replace the demo database password with a long random value.

## Run the individual services without Docker

You need Java 17 or newer, Maven, Node.js, and Python 3.9 or newer.

Open separate terminals in the project folder.

Start the mock providers:

~~~sh
python scripts/mock_provider.py --port 9001 --name alpha
python scripts/mock_provider.py --port 9002 --name beta
~~~

Start the Java gateway:

~~~sh
cd backend
mvn spring-boot:run
~~~

Start the website:

~~~sh
cd frontend
npm install
npm run dev
~~~

Visit http://localhost:5173. The data plane is at http://localhost:8080. The first Java build may take longer because Maven downloads libraries.

## Build the services

To build and check the Java service:

~~~sh
cd backend
mvn test
mvn package
~~~

The runnable Java file is created at backend/target/prism-gateway-1.0.0.jar.

To build the website files:

~~~sh
cd frontend
npm ci
npm run build
~~~

The static website files appear in frontend/dist. The Docker Compose build packages these files into an Nginx web server automatically.

## Put it on a server

For a small class demo, use a single Linux server with Docker and Docker Compose installed:

1. Copy or clone the project onto the server.
2. Keep the Compose file, Dockerfiles, deploy folder, scripts/mock_provider.py, and data folder together.
3. Run docker compose up --build -d from the project folder.
4. Point a domain name to the server and put HTTPS in front of port 8081 with a reverse proxy such as Caddy or Nginx.
5. Create the root .env file from .env.example and set a strong database password.
6. Open the HTTPS site and check its /health page.

The included compose file is for demonstration with mock providers. Keep mock provider ports private in a public deployment; remove their host port mappings after your demo. The console and Java API are intentionally served together through the web server. Set both `DATABASE_PASSWORD` and `JWT_SECRET` to strong private values in `.env` before starting Compose.

## Before showing it publicly

The seed virtual keys are examples for local demos. Replace them before inviting outside users. Admin endpoints require a signed-in JWT. New sign-ups currently receive workspace administrator access, so only enable public registration for people you trust. Keep the PostgreSQL volume on persistent disk and back it up. PostgreSQL is shared by service replicas; the in-memory requests-per-minute limiter is still per gateway instance, so use Redis or a database-backed limiter before running multiple gateway replicas.

The default configuration follows the problem statement's Alpha/Beta mock demo and fallback order. OpenRouter is optional: `OPENROUTER_API_KEY` adds it after the configured mock providers. Set `OPENROUTER_PREFER_AS_PRIMARY=true` only if you deliberately want OpenRouter ahead of Alpha/Beta. Keep the key in the gateway environment; the React app never receives it.

## Demo keys and request example

Use one of the preserved seed keys:

- search: prism-sk-search-1a2b3c (fast)
- research: prism-sk-research-4d5e6f (fast, smart, auto)
- free-tier: prism-sk-free-7g8h9i (fast; 10 requests per minute)
- budget demo: prism-sk-budget-demo-0j1k2l (fast; tiny monthly budget)

Example request:

~~~sh
curl -X POST http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer prism-sk-search-1a2b3c' \
  -H 'Content-Type: application/json' \
  -d '{"model":"fast","messages":[{"role":"user","content":"What is a message queue?"}]}'
~~~

For the Docker setup, replace localhost:8080 with localhost:8081; the web server forwards the API request to Java.

## How the gateway decides where to send prompts

With model auto, Prism checks the prompt for task clues before contacting a provider. Proofs, deep systems questions, and estimation requests go to the smart model. Short facts, text extraction, and simple rewrites go to the fast model. The rule is checked against all 20 provided examples with:

~~~sh
python scripts/eval_spring_router.py --url http://localhost:8080
~~~

When Alpha is down or returns a retryable error, Prism retries and then tries Beta. The response says which provider answered and whether it was a fallback. The demo providers can be controlled at ports 9001 and 9002 when run directly or through Docker Compose.
