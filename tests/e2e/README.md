# M1 browser smoke tests

Start the isolated development stack from the repository root:

```sh
cp .env.example .env
# Set a local-only POSTGRES_PASSWORD in .env.
docker compose -f compose.yaml -f compose.dev.yaml up --build -d --wait
npm ci
npx playwright install --with-deps chromium
npm run test:e2e
```

The desktop Chromium and Pixel 7 projects verify the real same-origin
`/api/v1/status` response, visible service state, and viewport overflow. The
failure test aborts browser status requests, checks the error message, then
removes interception and retries against the real backend.

Set `E2E_BASE_URL` to override `http://127.0.0.1:5173`. Tests use no customer
records and create no business data. Reports, screenshots, and failure traces
are ignored by Git; inspect the report with `npm run test:e2e:report`.

CI also validates `/actuator/health`, Flyway V1 on an empty PostgreSQL database,
and migration stability after a backend restart. CI removes its disposable
containers and database volume after the run.
