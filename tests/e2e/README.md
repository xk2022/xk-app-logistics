# Browser verification

The suite has 26 cases: thirteen each for desktop Chromium and Pixel 7. The
original twelve service, UPMS, ADM, CRM, and TOM API cases remain. The independent
`order-ui.spec.ts` adds three order-interface scenarios per project, using a
separate synthetic account group.
`vehicle.spec.ts` adds one independent FMS scenario per viewport; the original
18 cases remain unchanged.
`driver.spec.ts` adds one independent driver scenario per viewport; the previous
20 cases and their order/vehicle response collectors remain unchanged.

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
FMS uses its own normal ADMIN, DISPATCHER, and DRIVER accounts. Its synthetic
vehicle types are isolated by viewport, so disabling a type does not affect the
other project's records or the existing suites. The scenario verifies management
roles, lists and pagination, creation and editing, administrative status filters,
available master-data options, real version conflicts, inactive-type history,
decimal and date boundaries, and all six DRIVER API refusals. AVAILABLE describes
the master record; no test treats it as proof of dispatch availability or creates
an IN_USE state. Both viewports check labels, keyboard access, and overflow.
The driver scenario uses separate management accounts, three normal DRIVER
accounts, and one valid temporary DRIVER account per viewport. It verifies
management and literal searches, filters and pagination, independent employment
and duty states, terminal LEFT behavior, archived license types, real version
conflicts, paged binding candidates and their boolean bound flag, optional
binding, unbinding and reassignment, exact minimal self responses, cross-person
refusal, and the temporary-password barrier. Administrative options require only
ACTIVE and ON_DUTY; expired licenses, archived types, and absent accounts do not
silently add dispatch or legal-eligibility rules. License references and account
metadata stay out of self responses. No CRM, TOM, vehicle, or driver records are
inserted by the fixture runner.

The acceptance scenario adds independent ADMIN/DISPATCHER/DRIVER actors for each
viewport and runs all three original order graphs through the real POST accept
operation. It checks incomplete drafts, explicit confirmation, immutable accepted
views and waiting-for-dispatch display, actor restrictions, stale versions,
disabled-customer retry, historical customer names, and a committed response lost
before delivery. An explicit retry preserves the original key/body/version;
a CSRF refusal does not unlock the uncertain request. That acceptance scenario
itself creates no dispatch operation or resource reservation. Its 24-case baseline
includes all 22 preceding cases. Acceptance artifacts use their independent incremental
schemas; accepted GET/list/check and rejected PUT also pass the original schemas.

The TMS API scenario adds one case per viewport and retains all preceding 24.
It signs in through the normal protected page and calls the real seven TMS API
operations with same-origin HTTPS requests. It verifies initial and optimistic
task generations, assign/revoke/reassign, atomic occupancy visible in current
state and history, same-key historical replay, resource conflict, predecessor
blocking, guarded FMS changes, and current-binding DRIVER isolation. No dispatch
workbench is introduced by M3-04; the full management/mobile UI belongs to M3-05.

Production eligibility stays fail-closed. Positive TMS browser checks explicitly
use a separately mounted, test-only launcher and synthetic policy, never a
production profile or approval flag. Every policy construction checks
`SELECT current_database()` equals `xk_logistics_e2e`. Synthetic driver codes,
vehicle-type codes, and task kinds must belong to the same IT/DESKTOP/MOBILE
scope, and those synthetic vehicle-type items must currently be enabled. MAIN/TRAILER/SHARED are explicit test labels; SHARED exercises the common
vehicle-occupancy namespace, not a legal pairing approval. Every successful
synthetic assignment is marked `SYNTHETIC_TEST_ONLY`. No weight, inspection,
license, work-hour, or real operating qualification is inferred.

Use a fresh, isolated PostgreSQL database named `xk_logistics_e2e`. The fixture
runner creates 62 synthetic login accounts: independent UPMS, ADM, CRM, TOM API,
order UI, acceptance, and FMS ADMIN/DISPATCHER/DRIVER sets, plus independent TMS
ADMIN/DISPATCHER/two-DRIVER sets for each browser project. It accepts only
that database on the local machine and refuses a populated account table. It lives under `backend/src/test`, is absent from the production
JAR, generates random synthetic credentials, and writes a private file outside
report directories. Its ADM definition fixtures use six `SYNTHETIC_*` keys, one
TEXT/NUMBER/BOOLEAN set per browser project. These exist only in the test runner;
the production parameter-definition catalog remains empty.
It also inserts six synthetic ADM `VEHICLE_TYPE` items, three per viewport, solely
for these isolated tests, with clearly synthetic tractor, trailer, and truck
labels. General application startup still has no approved
vehicle-type items or vehicle seed records.
The driver group adds ADMIN/DISPATCHER, three normal DRIVER users and one
temporary DRIVER user per viewport, with a distinct username search prefix for
isolated candidate pagination. Four synthetic `DRIVER_LICENSE_TYPE` items, two
per viewport, exist only in this private arrangement. V7 itself initializes only
the empty category and never inserts accounts, drivers, or formal license types.

The TMS group adds four normal accounts per viewport and three isolated
`SYNTHETIC_TMS_<scope>_(MAIN|TRAILER|SHARED)` vehicle-type items per viewport.
Drivers, vehicles, CRM customers, orders and assignments are still created only
through the real APIs. The fixture never seeds their business tables.

The complete suite uses `compose.e2e.yaml` only after `compose.yaml` and
`compose.dev.yaml`. The override explicitly runs `TmsE2eApplication` through
Spring Boot `PropertiesLauncher`. `prepare-tms-runtime.py` copies only the plain
policy, its compiler-generated inner classes, and the launcher from test output;
it excludes the Boot test configuration, JUnit classes and fixture runner.
Normal DEV uses no E2E override and continues to reject unapproved dispatch
combinations. The production JAR contains none of these test-only classes.

```sh
umask 077
cp .env.example /tmp/xk-e2e.env
# In /tmp/xk-e2e.env set POSTGRES_DB=xk_logistics_e2e and a local-only password.
# Optional separate ports: POSTGRES_PORT=5433, BACKEND_PORT=8086,
# FRONTEND_PORT=5174, HTTPS_PORT=8444. Defaults remain supported.
export COMPOSE_FILE=compose.yaml:compose.dev.yaml:compose.e2e.yaml
export COMPOSE_PROJECT_NAME=xk-logistics-e2e
./infrastructure/generate-dev-tls.sh
npm ci
npx playwright install --with-deps chromium

cd backend
./mvnw test-compile dependency:build-classpath -Dmdep.outputFile=target/e2e-classpath.txt -Dmdep.includeScope=test
cd ..
python3 tests/e2e/prepare-tms-runtime.py
docker compose --env-file /tmp/xk-e2e.env up --build -d --wait
cd backend
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
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_acceptance_contract.py \
  --responses 'test-results/**/acceptance-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_vehicle_contract.py \
  --responses 'test-results/**/vehicle-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_driver_contract.py \
  --responses 'test-results/**/driver-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_tms_contract.py \
  --responses 'test-results/**/tms-contract-responses.json'
```

The validator keeps format checking enabled and verifies the immutable source
hashes, committed schema examples, graph fixtures, role matrix, and field
dictionary before checking the actual response bodies. CI runs the same formal
validation and scans the pinned Python requirements with OSV.

Run the tests immediately after creating the fixture because UPMS initial passwords
and the driver's temporary fixture credential
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
Each FMS case separately writes `vehicle-contract-responses.json`, using the same
body-only `{schema, value}` format for its independent FMS OpenAPI schemas. It
includes real permission, validation, uniqueness, and version-conflict responses;
no authentication response or request metadata is captured.
Each driver case writes `driver-contract-responses.json` with the seven
independent driver envelope schemas in the same body-only format. The separate
driver validator checks this evidence; the order and vehicle validators and
their artifacts are preserved.

Each TMS case writes `tms-contract-responses.json` with only the six independent
TMS envelope schemas in the same body-only format. The TMS validator checks real
manager and self responses, errors, and historical command results. HTTP status,
`Idempotency-Replayed`, generation increments and current-state isolation are
asserted directly by the API scenario; body-only schema validation is not claimed
as proof of a database transaction. No authentication body or request headers
are collected.

CI validates health/status, Flyway V1–V9 on an empty database, exact UPMS/ADM/CRM/TOM/FMS/TMS
tables (27 including Flyway), the fixed ORDER_TYPE enum and four empty configurable categories,
absence of seeded accounts, parameter definitions/values, CRM records, TOM records,
vehicles, drivers, dispatch assignments/events/reservations, restart stability,
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
