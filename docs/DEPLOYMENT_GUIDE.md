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
- Gateway health check: http://localhost:8081/health
- OpenAI-compatible API: http://localhost:8081/v1/chat/completions

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

The included compose file is for demonstration with mock providers. Keep mock provider ports private in a public deployment; remove their host port mappings after your demo. The console and Java API are intentionally served together through the web server.

## Before showing it publicly

The seed virtual keys are examples for local demos. Replace them before inviting outside users. Admin endpoints currently have no login, and the gateway is designed as a single-instance capstone service. Do not expose the admin endpoints to the public internet without adding authentication. Keep the PostgreSQL volume on persistent disk and back it up. PostgreSQL is shared by service replicas; the in-memory requests-per-minute limiter is still per gateway instance, so use Redis or a database-backed limiter before running multiple gateway replicas.

To use a real AI provider, connect an OpenAI-compatible provider through the provider adapter and configure its key as a server-side secret. Never put provider keys in the React app, browser requests, public source control, or request logs. This Compose setup uses only the free mock providers and does not send prompts to a paid model. Prism does not depend on OpenRouter; when it is unavailable, the configured provider chain can still use Alpha/Beta or another configured OpenAI-compatible provider.

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
