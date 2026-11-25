# Browser verification

The suite has 10 cases: five each for desktop Chromium and Pixel 7. The
original service, UPMS, and ADM checks remain, with one CRM scenario added per
browser project.

The desktop Chromium and Pixel 7 projects preserve the M1 service-state success,
request failure, retry, and overflow checks. UPMS verification uses the same
local TLS origin as the application and covers all three roles, first-password
changes, cookie protection, CSRF refusal, administrator account creation,
authorization denial, session revocation, and logout. ADM scenarios use separate
normal test accounts per browser project, so UPMS password changes and account
disabling cannot affect dictionary and parameter checks. They verify category
and item creation, stable codes, sorting, enable/disable behavior, real optimistic
locking conflicts and reload, typed allowlisted parameters, audit display, and
non-administrator refusal. CRM adds a separate normal account set per browser
project. Its scenarios create synthetic customers through the UI, verify real
search and pagination with a five-row page, multiple contacts and addresses,
stable codes and parent ownership, history-preserving disable/enable, active
options, version conflict recovery, ADMIN/DISPATCHER management, and DRIVER
refusal. No CRM records are inserted by the fixture runner.

Use a fresh, isolated PostgreSQL database named `xk_logistics_e2e`. The fixture
runner creates 18 synthetic login accounts: independent UPMS, ADM, and CRM
ADMIN/DISPATCHER/DRIVER sets for each browser project. It accepts only that database on the local machine and refuses a populated
account table. It lives under `backend/src/test`, is absent from the production
JAR, generates random synthetic credentials, and writes a private file outside
report directories. Its ADM definition fixtures use six `SYNTHETIC_*` keys, one
TEXT/NUMBER/BOOLEAN set per browser project. These exist only in the test runner;
the production parameter-definition catalog remains empty.

```sh
umask 077
cp .env.example /tmp/xk-e2e.env
# In /tmp/xk-e2e.env set POSTGRES_DB=xk_logistics_e2e and a local-only password.
export COMPOSE_FILE=compose.yaml:compose.dev.yaml
export COMPOSE_PROJECT_NAME=xk-logistics-e2e
./infrastructure/generate-dev-tls.sh
docker compose --env-file /tmp/xk-e2e.env up --build -d --wait
npm ci
npx playwright install --with-deps chromium

cd backend
./mvnw test-compile dependency:build-classpath -Dmdep.outputFile=target/e2e-classpath.txt -Dmdep.includeScope=test
set -a
source /tmp/xk-e2e.env
set +a
export E2E_FIXTURE_PATH=/tmp/xk-m2-e2e-fixture.json
DB_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB" DB_USER="$POSTGRES_USER" DB_PASSWORD="$POSTGRES_PASSWORD" \
  java -cp "target/test-classes:target/classes:$(cat target/e2e-classpath.txt)" com.xksystem.logistics.support.E2eFixtureMain
cd ..
npm run test:e2e
python3 tests/e2e/assert_report_privacy.py
```

Run the tests immediately after creating the fixture because UPMS initial passwords
expire after 15 minutes. A full rerun requires a fresh isolated database and
fixture; the scenarios intentionally change passwords and disable accounts.
`E2E_BASE_URL` defaults to `https://localhost:8443`. Only localhost self-signed
certificates bypass browser certificate validation; remote targets retain
normal TLS checks.

Authentication traces are disabled so passwords and session tokens stay out of
artifacts. Password values are also kept out of Playwright action titles.
Screenshots show the resulting workspace, account list, dictionaries, parameters,
and synthetic customer details. Reports and
screenshots are ignored by Git; inspect the HTML report with
`npm run test:e2e:report`. Keep credential files out of report directories.

CI validates health/status, Flyway V1–V4 on an empty database, exact UPMS/ADM/CRM
tables, the fixed ORDER_TYPE enum and three empty configurable categories,
absence of seeded accounts, parameter definitions/values, or CRM records, restart stability,
and these browser flows. Report upload requires the privacy check to confirm
that fixture credentials and trace archives are absent.
It removes its credential file, disposable containers, and database volume after
completion. When finished with a local test database, remove only that isolated
project:

```sh
rm -f "$E2E_FIXTURE_PATH"
docker compose --env-file /tmp/xk-e2e.env down --volumes --remove-orphans
rm -f /tmp/xk-e2e.env
```
