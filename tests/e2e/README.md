# Browser verification

The suite has 18 cases: nine each for desktop Chromium and Pixel 7. The
original twelve service, UPMS, ADM, CRM, and TOM API cases remain. The independent
`order-ui.spec.ts` adds three order-interface scenarios per project, using a
separate synthetic account group.

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
refusal. TOM requests run as same-origin HTTPS `fetch` calls in real browser
contexts after protected login. They verify all five original order operations,
the fifteen role/operation combinations, strict `OK`/`traceId` success and
`fieldErrors` error envelopes, incomplete and complete acceptance checks without
operational acceptance, stable child references, full replacement, optimistic
versions, canonical-body replay, actor scoping, failed-write retry, customer-name
snapshots, disabled-customer rules, and refusal to replay after revocation/logout.
They also verify that `XKSESSION` and legacy `JSESSIONID` represent the same
protected session, rotate on login, and support a real JSESSIONID-only browser
read and CSRF-protected write. Cookie comparisons expose only boolean results;
the suite never constructs a cookie with a real session value in action arguments.
The order-interface scenarios use the real application, backend and PostgreSQL.
They cover both management roles, all three order types, multiple items/tasks and
dependencies, completeness checks without operational acceptance, effective
customer selection and disabled-customer history, preserved local drafts on
version conflicts, and explicit replay after a committed response is lost. The
fault injector validates TLS with the generated local DEV public certificate
(system trust for remote hosts). It lets the original request finish at the real
backend before aborting delivery to the browser, compares retry key/body only in memory
and verifies the same persisted order/version. A first retry is also sent to the
real backend with an invalid synthetic CSRF token; its 403 must keep the original
uncertain operation frozen until a later explicit replay confirms that result.
DRIVER has neither the order
navigation nor order API access. Both viewports check accessible field labels,
keyboard operation and horizontal overflow. The three cases run sequentially
within each viewport so they cannot evict one another's sessions; other suites
and viewports retain independent credentials and can run concurrently.
No CRM or TOM records are inserted by the fixture runner.

Use a fresh, isolated PostgreSQL database named `xk_logistics_e2e`. The fixture
runner creates 30 synthetic login accounts: independent UPMS, ADM, CRM, TOM API,
and order UI ADMIN/DISPATCHER/DRIVER sets for each browser project. It accepts only
that database on the local machine and refuses a populated account table. It lives under `backend/src/test`, is absent from the production
JAR, generates random synthetic credentials, and writes a private file outside
report directories. Its ADM definition fixtures use six `SYNTHETIC_*` keys, one
TEXT/NUMBER/BOOLEAN set per browser project. These exist only in the test runner;
the production parameter-definition catalog remains empty.

```sh
umask 077
cp .env.example /tmp/xk-e2e.env
# In /tmp/xk-e2e.env set POSTGRES_DB=xk_logistics_e2e and a local-only password.
# Optional separate ports: POSTGRES_PORT=5433, BACKEND_PORT=8086,
# FRONTEND_PORT=5174, HTTPS_PORT=8444. Defaults remain supported.
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
export E2E_BASE_URL="https://localhost:${HTTPS_PORT:-8443}"
DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT:-5432}/$POSTGRES_DB" DB_USER="$POSTGRES_USER" DB_PASSWORD="$POSTGRES_PASSWORD" \
  java -cp "target/test-classes:target/classes:$(cat target/e2e-classpath.txt)" com.xksystem.logistics.support.E2eFixtureMain
cd ..
npm run test:e2e
python3 tests/e2e/assert_report_privacy.py
```

Validate the original order contract and captured HTTP responses with the
test-only, hash-pinned CPython 3.12 Linux x86-64 tooling. No production or npm
dependencies are added:

```sh
python3 -m venv /tmp/xk-order-contract-env
/tmp/xk-order-contract-env/bin/python -m pip install --require-hashes -r tests/contracts/requirements.txt
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_order_contract.py \
  --responses 'test-results/**/order-contract-responses.json'
```

The validator keeps format checking enabled and verifies the immutable source
hashes, committed schema examples, graph fixtures, role matrix, and field
dictionary before checking the actual response bodies. CI runs the same formal
validation and scans the pinned Python requirements with OSV.

Run the tests immediately after creating the fixture because UPMS initial passwords
expire after 15 minutes. A full rerun requires a fresh isolated database and
fixture; the scenarios intentionally change passwords and disable accounts.
`E2E_BASE_URL` defaults to `https://localhost:8443`. Only localhost self-signed
certificates bypass browser certificate validation; remote targets retain
normal TLS checks. The test files never reset databases, seed accounts, or start
the runtime automatically; preparation and cleanup are explicit operations.

Authentication traces are disabled so passwords and session tokens stay out of
artifacts. Password values are also kept out of Playwright action titles.
Screenshots show the resulting workspace, account list, dictionaries, parameters,
synthetic customer details, and synthetic order screens. The order UI tests do
not attach intercepted requests, headers, keys, cookies or CSRF values. Each TOM API case and each order UI case additionally writes
`order-contract-responses.json` under its test output directory. It contains only
`{schema, value}` entries with original synthetic order/error response bodies for
independent validation against the unchanged OpenAPI 0.2.0 schemas. It excludes
request context, credentials, cookies, headers, and CSRF tokens. Only actual JSON bodies from the five order routes are collected; aborted or
unreadable responses are skipped and are not claimed as validated. The original
TOM API artifacts remain unchanged. These artifacts are
also covered by the existing privacy guard. Reports and
screenshots are ignored by Git; inspect the HTML report with
`npm run test:e2e:report`. Keep credential files out of report directories.

CI validates health/status, Flyway V1–V5 on an empty database, exact UPMS/ADM/CRM/TOM
tables, the fixed ORDER_TYPE enum and three empty configurable categories,
absence of seeded accounts, parameter definitions/values, CRM records, or TOM records, restart stability,
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
