# Free deployment options

Two practical $0 strategies. Both use the existing Supabase PostgreSQL database
instead of a free database that expires.

## Option A — fastest: Render + Cloudflare Pages + Supabase

| Piece | Service | Free limits | Notes |
|---|---|---|---|
| Backend | Render Free Web Service (Docker) | 512 MB RAM, 750 instance-hours/month, sleeps after 15 minutes idle, ~1 minute cold start | Runs the root `Dockerfile`; no card required |
| Frontend | Cloudflare Pages | Unlimited static requests/bandwidth | Admin at `/`, chat at `/chat.html` |
| Database | Supabase Free PostgreSQL | 500 MB, pauses after a week of inactivity | Already used by this project; keep the cache caps enabled |

### 1. Deploy the gateway

1. Push the repository to GitHub.
2. In Render, create **New → Web Service**, connect the repo, and choose:
   - Runtime: **Docker**
   - Dockerfile: `./Dockerfile`
   - Plan: **Free**
   - Region: Singapore (closest to the Supabase `ap-southeast-1` database)
   - Health check: `/health`
3. Add these environment variables:

```text
SPRING_PROFILES_ACTIVE=enterprise
DATABASE_URL=jdbc:postgresql://<supabase-pooler-host>:5432/postgres?sslmode=require
DATABASE_USERNAME=<supabase-pooler-user>
DATABASE_PASSWORD=<supabase-database-password>
JWT_SECRET=<at-least-32-random-characters>
JAVA_TOOL_OPTIONS=-Xms96m -Xmx300m -XX:MaxMetaspaceSize=128m
DATABASE_POOL_MAX=5
PRISM_CACHE_MEMORY_MAX_ENTRIES=300
PRISM_CORS_ORIGINS=https://<your-pages-project>.pages.dev
OPENROUTER_API_KEY=<optional>
GEMINI_API_KEY=<optional>
RAZORPAY_PAY_KEY_ID=<live key id>
RAZORPAY_PAY_KEY_SECRET=<live key secret>
RAZORPAY_MERCHANT_ID=<merchant id if used>
RAZORPAY_WEBHOOK_SECRET=<webhook secret>
```

The root `Dockerfile` copies `data/` into the image, because the gateway needs
`seed_keys.json`, `gateway_config.sample.json`, and `model_pricing.json` at
startup. Flyway applies migrations when the container starts.

The mock alpha/beta providers are not running on Render. Set at least one real
provider key (`OPENROUTER_API_KEY` or `GEMINI_API_KEY`), or change the seeded
routing so requests do not point at `localhost:9001/9002`.

### 2. Deploy the console and chat

1. In Cloudflare Pages, connect the same repository.
2. Build command:

```sh
cd frontend && npm ci && npm run build
```

3. Output directory: `frontend/dist`
4. Environment variable:

```text
VITE_API_URL=https://<your-render-service>.onrender.com
```

Do not add a trailing slash. Rebuild after changing it.

5. Copy the Pages URL into Render's `PRISM_CORS_ORIGINS` and redeploy the
   backend. Multiple origins are comma-separated.

### 3. Finish the live integrations

- In Razorpay Dashboard, set the webhook URL to
  `https://<your-render-service>.onrender.com/webhooks/razorpay` and use the
  same secret as `RAZORPAY_WEBHOOK_SECRET`.
- The admin console and chat must both be served over HTTPS for live Checkout.
- Open `https://<pages-project>.pages.dev/` and sign in. Chat is at
  `https://<pages-project>.pages.dev/chat.html`.

### Caveat: the 17:00 purge and Render sleep

Render free services sleep after 15 minutes without traffic, so the in-process
`@Scheduled` purge will not run at 17:00 while the service is asleep. The
per-write cache caps still keep PostgreSQL bounded, but the daily full purge is
not guaranteed on Render Free. For a guaranteed daily purge, use Option B, or
call a protected purge endpoint from a free scheduler such as cron-job.org or a
GitHub Actions schedule.

## Option B — best free long-term: Oracle Cloud Always Free VM

Oracle gives a permanently free ARM VM (currently 2 OCPU / 12 GB RAM, 200 GB
block storage, 10 TB egress/month). A card is required for identity
verification, and A1 capacity can be temporarily unavailable in some regions.

1. Create an **Ubuntu 24.04 ARM** VM with the `VM.Standard.A1.Flex` shape
   (2 OCPU, 12 GB is enough).
2. Open only ports 22, 80, and 443 in the OCI security list and the host
   firewall.
3. Install Docker and Compose:

```sh
sudo apt update
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
```

4. Clone the repo, create `.env` from `.env.example`, and set at least
   `DATABASE_PASSWORD`, `JWT_SECRET`, `PRISM_CORS_ORIGINS`, and any provider or
   Razorpay keys. To keep Supabase, also set `DATABASE_URL`,
   `DATABASE_USERNAME`, and `DATABASE_PASSWORD` to the Supabase pooler values.
5. Run:

```sh
docker compose up --build -d
```

6. Put Caddy in front of port 8081 for automatic HTTPS:

```caddyfile
prism.example.com {
    reverse_proxy 127.0.0.1:8081
}
```

If you do not own a domain yet, use an `sslip.io` hostname that resolves to the
VM's public IP and Caddy will still obtain a Let's Encrypt certificate:

```caddyfile
203-0-113-10.sslip.io {
    reverse_proxy 127.0.0.1:8081
}
```

7. Point the Razorpay webhook at the same public HTTPS host:

```text
https://prism.example.com/webhooks/razorpay
```

The console's nginx config proxies `/auth`, `/v1`, `/admin`, `/health`, `/app`,
and `/webhooks` to the Spring Boot gateway, so the webhook works through the
same domain as the app.

This runs the frontend, backend, PostgreSQL, and both mock providers on one
always-on machine. The 17:00 purge, Razorpay webhooks, and streaming requests
work normally, with no cold starts. The Compose database is optional when
Supabase is configured; it is harmless to leave it running, or you can remove
the `db` service and its `depends_on` entry to save VM memory.

## Not recommended for a free deployment

- **Railway** — free plan is only $1 of usage; not enough for an always-on JVM.
- **Koyeb** — new accounts are pushed toward a paid plan and require a card.
- **Fly.io** — no free tier for new accounts.
- **Render free Postgres** — deleted after 30 days. Use Supabase/Neon instead.
- **Vercel/Netlify** — excellent for the static frontend, but not for a
  long-running Spring Boot server.

## Files added for deployment

- `Dockerfile` — root build context; includes `data/` in the runtime image.
- `.dockerignore` — keeps the image build small.
- `render.yaml` — optional Render Blueprint for the backend and static site.
- `PRISM_CORS_ORIGINS` — configurable allowlist in `CorsConfig.java`.
