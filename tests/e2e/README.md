# Browser verification

The suite has 40 cases: twenty each for desktop Chromium and Pixel 7. The
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

The independent M3-05 `dispatch-ui.spec.ts` adds one complete workbench scenario
per viewport and retains all preceding 26 cases. It accepts a real saved order
through the order UI, opens the resulting TMS task, selects resources and confirms
assignment, reassigns with the other management role, and resolves a stale-generation
conflict explicitly. The scenario checks blocked predecessors and occupied or
unapproved candidates, current reservations, paged history, and the driver's
read-only task page. Each of assign, reassign, and revoke commits at the real
backend before a controlled HTTPS relay drops delivery; only an explicit retry
with the original key/body/generation retrieves that result. The assign retry is
first rejected by real CSRF middleware and must remain unresolved. Public receipts
are historical results; the UI must explicitly read current dispatch state before
another operation. No request keys or authentication headers are attached to reports.

Driver UI checks cover removal of route/contact DOM after revocation, 404/unbinding,
rebinding, and 401/account disable. A real logout response is briefly held after
server completion to verify sensitive task content is unmounted while the client
is still waiting. The same account can then log in normally again. Current state,
history, driver tasks, confirmation, and uncertain-result screenshots contain only
synthetic visible data; keyboard activation, field labels and horizontal overflow
are checked on desktop and mobile. Runtime startup remains an external explicit step.

The independent M4-01 `execution.spec.ts` retains all 28 existing cases and adds
one API-only scenario per viewport. The execution UI remains in M4-06. Each new
scenario signs in through real protected HTTPS pages and calls only the frozen
current-person detail/departure operations through the browser's same-origin
`fetch`. It checks the new exact fifteen-field detail and three-field departure
receipt, while the original my-tasks list retains its exact fourteen fields.
Virtual WAITING/0 detail preserves accepted/assignment snapshot decimal strings,
raw NUL/NFD/whitespace, original date-time offset/microseconds and
absent/all-null/populated contact distinctions. The original TOM API may canonicalize
input decimal trailing zeros; execution must preserve its stored source value,
not restore the original POST spelling.
The case covers anonymous, management-role, temporary-password, unbound,
cross-person, current-binding and disabled-account refusals; strict body/query,
media/method/key/CSRF failures; independent execution versions; canonical-body and
canonical-path replay, case-sensitive keys, reuse on a different assignment path,
and simultaneous same-key departure. HEAD refusal is checked using only status
and the public replay header; its body is not read or counted as JSON evidence. A real TLS relay
commits the first departure then aborts browser delivery. Explicit retries keep
the original raw body/key, first receive a real CSRF403, then recover the immutable
successful receipt. These are API checks, not claims about an execution UI.

M3 coexistence checks use both deliberate orderings and real concurrent browser
requests for departure versus revoke/reassign. Either serialized race winner is
allowed, but both operations cannot succeed. A successful departure leaves task
generation, assignment status/version, dispatch snapshot/history, reservations,
TOM data and predecessor readiness unchanged. New terminal manager commands are
then refused in the original E16 namespace; legitimate old manager success
replays remain historical. Current detail and receipt are refused after binding
changes, even for the same account with the original successful key. Deterministic
lock scheduling, insert-failure rollback, zero-GET-write and production-policy
checks belong to the backend integration suite; HTTP/schema checks do not claim
to establish those database properties alone.

The independent M4-02 `progress-evidence.spec.ts` retains the preceding 30
cases and adds one API scenario per viewport, using five new actors rather than
the execution group's intentionally disabled/rebound accounts. It creates all
business data with the real APIs, follows DEPARTED → IN_PROGRESS →
AWAITING_COMPLETION, and verifies the current seven-field policy view. No next
transition is advertised for unsupported phases; requirements stay null rather
than implying a photo waiver. Normal unconfirmed policy and unavailable provider
behavior, including replay after policy changes, are verified by controlled
backend integration tests and the normal-runtime checks. No HTTP test toggle,
client approval field, production provider or photo rule is introduced.

The case generates real synthetic 2×2 PNG bytes from deterministic RGBA pixels,
standard deflate, valid chunks and CRCs. The browser independently decodes the
fixture before upload. It verifies raw image/png uploads, exact ten-field VERIFIED
metadata, byte size and SHA-256, the strict execution-version header, MIME/CRC/
truncation/pixel/byte/hard-cap rejection, paged metadata and private content.
Successful binary bodies are compared, hashed and decoded only in browser memory;
no PNG/base64/headers/filename is saved in JSON artifacts. Runtime assertions cover
content length, no-store/private, nosniff, fixed safe disposition, absent public
URLs/cache validators/ranges and refusal of HEAD/Range/foreign/expired requests.

Both upload and progress really commit before controlled response loss. Their
original bytes or JSON, execution-version metadata and keys remain in memory;
manual retries first encounter a real CSRF403, then retrieve the stored receipt.
Canonical UUID path and sorted unique UUID-set replay, differing-body conflicts,
later-phase metadata receipts and historical departure/progress receipts are
checked without replacing current state. Upload never increments execution
version; only progress advances it. An upload racing different-key progress commands cannot attach a photo to a
later stage or duplicate a transition.
At AWAITING_COMPLETION, unsupported next-phase policy still permits authorized
private reads of earlier evidence. Rebinding the same physical driver to another
normal account preserves current read access without treating historical uploader
identity as an extra authorization rule; command receipts remain actor-scoped.
Unbinding, logout and account disabling must still refuse old data.
Every step preserves the original fourteen-field list, fifteen-field detail,
three-field departure receipt, TOM/FMS versions, task generation and reservations.
It neither completes a task, releases resources nor unlocks a predecessor.

The M4-03 exception scenario adds independent manager UI coverage per viewport,
retaining all earlier 32 cases. ADMIN and DISPATCHER use the real list, status and
assignment filters, detail, raw report, explicit request/resolve confirmation,
paged history and exception-linked photo preview. DRIVER report, raw PNG upload
and explicit supplement submission are setup/API checks only; no DRIVER execution
product UI is introduced before M4-06. Original report, NFD/NUL text and outer
whitespace remain distinct from later request, submission and resolution facts.

Both management commands commit at the real HTTPS backend before controlled
response loss. A real invalid-CSRF retry preserves the frozen reason, version,
path and key; the next explicit retry retrieves the historical receipt. A driver
can fulfill a requested supplement before the manager retrieves that original
request receipt; the UI must show it as history and explicitly reload current GET
state before another action. Real stale-version UI handling retains the local
reason until explicit adoption. Legal races are request versus resolve on OPEN
after fulfilled evidence, or upload versus submit on NEEDS_SUPPLEMENT after a
valid linked photo exists. Resolve is never treated as legal on NEEDS_SUPPLEMENT.
No exception operation completes execution, releases resources or unlocks a
predecessor; old M4-02 uploads still reject AWAITING_COMPLETION while the new
request-scoped supplement route may accept it under the isolated test policy.

Private exception PNG content is read only through the new manager route;
metadata links cannot authorize another exception's or an ordinary task photo.
Actual bytes, SHA-256, browser decode, cache/privacy headers and objectURL
revocation are checked in memory. Manager historical access remains independent
of a driver's current binding; self routes retain the stricter current scope.
Revoking a manager's account while previewing must clear all protected facts and
revoke the objectURL. This file disables automatic failure screenshots to avoid
capturing private previews; it explicitly saves safe manager screenshots only
after closing previews and checking no blob image remains. No original PNG,
base64, request headers, cookies, CSRF or key is saved as an artifact.

The M4-04 completion API scenario adds one independent case per viewport and
preserves all prior 34. It uses real departure, both controlled progress steps,
durable PNGs and the exact selected readiness-event evidence set. An unresolved
exception blocks completion; its independent request/upload/submit/resolve loop
does not itself release resources or change execution. The new completion policy
exposes only a confirmed synthetic rule and exact canonical proof IDs, never a
production waiver or an empty evidence requirement. Confirming safe release is
an explicit test attestation plus a server decision, not an approved real-world
DRIVER responsibility rule.

The case completes assignments with two and three resource reservations, verifies
all effective reservations become available, exercises the post-release FMS guard
and reuses those resources for a real newly-ready dependent assignment. The new
sixteen-field completed read preserves the original safe fourteen-field source,
including raw NUL/NFD/whitespace/decimal/offset data; it never follows a reused
resource to another task. The original assignment, reservation and manager receipt
JSON remain unchanged recorded facts, with no invented COMPLETED assignment or
legacy RELEASED event. Effective release is proved by availability and successful
reuse; body-only schemas cannot prove the SQL partial index or deferred seals.

A real completion commits before controlled HTTPS response loss. The first
explicit retry gets a real CSRF refusal and the next same-body/key retry retrieves
the immutable success even after FMS availability changes. Historical receipt
replay after resource reuse must not release the new owner's reservations. A
zero-exception report-versus-complete race admits only a report with blocked
completion, or completion followed by opaque refusal of the old current report.
No unsupported progress or completion with an unresolved exception is treated as
a legal positive race.

Older current-only M4-01/02/03 reads and receipts remain opaque404 after effective
release. The old fourteen-field task list deliberately retains the recorded
assignment, including completion, while its existing UI now says assignment
records and makes no claim that resources remain occupied. Safe desktop/mobile
screenshots show that actual legacy record, not a fabricated completion form;
full DRIVER operation UI remains M4-06. New read/replay authorization follows the
current binding to the original physical driver, with actor-scoped receipts,
foreign-person refusal and revocation on account loss. No new photo endpoint,
completed-photo history grant or persisted original PNG is introduced.

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
runner creates 133 synthetic login accounts: independent UPMS, ADM, CRM, TOM API,
order UI, acceptance, and FMS ADMIN/DISPATCHER/DRIVER sets, plus independent TMS
ADMIN/DISPATCHER/two-DRIVER sets for the API and UI scenarios, and independent
five-actor execution/progress/exception/completion/closure/execution-UI groups in each browser
project. It accepts only
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
The separate `dispatchUi` group adds another four normal accounts per viewport,
reuses only the API group's immutable synthetic type IDs, and creates driver codes
`SYNTHETIC_TMS_<scope>_D1001` and higher. It never shares credentials or changes
those definitions, so API-group role revocation and UI-group binding changes
cannot invalidate each other's scenarios. Order numbers and resource searches
isolate each UI scenario from concurrent test records.

The M4-01 `execution` group adds five separate accounts per viewport: ADMIN,
DISPATCHER, two normal DRIVER actors and one valid temporary DRIVER. It shares
only immutable synthetic vehicle-type IDs with the TMS groups. Driver codes use
`SYNTHETIC_TMS_<scope>_D2001` and higher; new plates, customers, orders and task
kinds isolate all data from both previous API/UI scenarios and the other viewport.
All business records, assignments and departures are created through real APIs;
the runner never inserts an execution, departure time, event or command receipt.
Departed resources remain occupied; later sub-scenarios create fresh resources
and explicitly change only their own binding, rather than bypassing release gates.

The M4-02 `progressEvidence` group adds five independent accounts per viewport,
bringing the private fixture to 90 accounts. It reuses only the immutable
DESKTOP/MOBILE synthetic type IDs. Physical driver codes use D3001 and above,
with unique plates, customers, orders and assignments; the runner seeds no
execution, progress, evidence, blob, event link or command receipt. Its positive
policy/private bytea provider is strictly a test-classpath, database-name-guarded
arrangement. PostgreSQL is not selected as the formal production photo provider.

The M4-03 `exception` group adds another five separate accounts per viewport,
bringing the private fixture from 90 to 100 accounts. It reuses only immutable
synthetic classifications; driver codes start at D4001, with fresh plates,
customers, accepted orders and assignments created through real APIs. The runner
creates no exception, supplemental request, image/link, submission or disposition
fact. Its independently named exception policy is test-only, same-scope and
actual-database-name guarded; no HTTP/profile/parameter flag approves a formal
exception or provider.

The M4-04 `completion` group adds another five separate accounts per viewport,
bringing the private fixture from 100 to 110. Only immutable synthetic types are
shared. Driver codes start at D5001; vehicles, customers, orders, progress,
exceptions and completion/release facts are created only through actual APIs.
The fixture runner never seeds those business tables. The completion policy is
another explicit test-only, actual-database-name-guarded decision; normal
production remains UNCONFIRMED even when the caller sends confirmation=true.

The M4-05 `closure` group adds five independent accounts per viewport, bringing
the private fixture from 110 to 120. It shares only immutable synthetic types;
D6001 and fresh plate/customer/order namespaces isolate the management flow. No
completion confirmation, closure witness, task manifest, business record or
receipt is seeded. Normal management-confirmation and order-closure policies
remain UNCONFIRMED. Only named test-classpath policies, guarded by the actual
isolated database name, enable the synthetic all-persisted-task cohort.

The M4-06 `executionUi` group adds five independent actors per viewport,
bringing the fixture from 120 to 130. D7001 and higher plus unique plates,
customers, accepted orders and task kinds isolate its real DRIVER interface
scenario from every previous actor group. Additional browser sessions reuse only
that new actor to establish legal stale-version orderings; no old credentials or
mutable business records are shared. The runner seeds no operational facts or
receipts. No new production policy, provider, migration or approval flag exists.

M4-07 adds exactly three global UPMS actors once, bringing the total from 130
to 133. The private JSON root contains `m4Integration` with exact keys `admin`,
`controlAdmin` and `driver`; each credential is exactly `{id, username, password}`.
The first two are distinct normal ADMIN accounts and the third is a normal
DRIVER account. They are independent of both browser projects and are not
repeated per viewport. TypeScript exposes `m4IntegrationFixture()` with no project
parameter; the test-only backend drill reads the same root paths directly.

The existing encoder/helper generates all three identities and passwords. No
FMS binding, vehicle, customer, order, dispatch, photo, history or receipt is
seeded. The drill creates its own IT-scope dictionary entries and source cohorts
through real HTTPS APIs, rather than reusing browser actors or DESKTOP types.
The original forty browser cases and every prior fixture field stay unchanged.
The runner retains its local exact-database JDBC URL restriction and exclusive
empty-account-table check: start a fresh isolated database; never append these
accounts to an already populated 130-account run. The runner does not claim an
actual-name SQL check; existing synthetic-policy factories retain that separate
guard. Base credentials and any combined drill secret inventory remain private,
separate files outside reports; the final privacy gate must cover all 133 initial
passwords and every later generated drill secret before upload.

The combined private inventory must exist before any browser starts, while
`E2E_FIXTURE_PATH` continues to identify the original fixture. Set
`M4_SECRET_INVENTORY_PATH` to its private 0600 copy. `syntheticPassword()` retains
its original 24 random bytes/base64url generation, but synchronously registers
each value under `_generatedSecrets: [{password: value}, ...]` before returning
it. Missing inventory configuration fails closed. Two browser workers serialize
atomic 0600 writes through an exclusive `.lock`, bounded to five seconds, and a
fixed `.pending` file; file and directory synchronization precede return. Existing
base fields, including `m4Integration`, remain unchanged. The Python drill reads
the latest combined file and preserves this array alongside its own secrets.
The original UPMS browser scenario makes six calls per viewport (twelve for the
full suite); runtime inventory counts remain the actual evidence. Retain the
inventory through the final privacy gate, then remove its private `.lock` and
`.pending` remnants with the owned file. Never replace the original browser
fixture with this growing inventory.

The complete suite uses `compose.e2e.yaml` only after `compose.yaml` and
`compose.dev.yaml`. The override explicitly runs `TmsE2eApplication` through
Spring Boot `PropertiesLauncher`. `prepare-tms-runtime.py` copies only the
explicitly allowed synthetic policies/private provider, their compiler-generated
inner classes, and the launcher from test output;
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
# Linux headed verification also requires xvfb-run and xauth on PATH.
command -v xvfb-run
command -v xauth

cd backend
./mvnw test-compile org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath -Dmdep.outputFile=target/e2e-classpath.txt -Dmdep.includeScope=test
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
export M4_SECRET_INVENTORY_PATH=/tmp/xk-m4-report-secrets.json
python3 - <<'PY_INVENTORY'
import json, os
from pathlib import Path
source = Path(os.environ['E2E_FIXTURE_PATH'])
target = Path(os.environ['M4_SECRET_INVENTORY_PATH'])
data = source.read_bytes()
assert set(json.loads(data)['m4Integration']) == {'admin', 'controlAdmin', 'driver'}
pending = Path(str(target) + '.pending')
descriptor = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, 'wb') as stream:
    stream.write(data)
    stream.flush()
    os.fsync(stream.fileno())
os.replace(pending, target)
assert target.stat().st_mode & 0o777 == 0o600
PY_INVENTORY
xvfb-run --auto-servernum npm run test:e2e -- --headed --workers=2 --retries=0
E2E_FIXTURE_PATH="$M4_SECRET_INVENTORY_PATH" python3 tests/e2e/assert_report_privacy.py
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
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_execution_contract.py \
  --responses 'test-results/**/execution-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_progress_evidence_contract.py \
  --responses 'test-results/**/progress-evidence-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_exception_contract.py \
  --responses 'test-results/**/exception-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_completion_contract.py \
  --responses 'test-results/**/completion-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_closure_contract.py \
  --responses 'test-results/**/closure-contract-responses.json'
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

Each execution case additionally writes `execution-contract-responses.json`.
Its exact schema allowlist is `MyTaskExecutionEnvelope`, `DepartMyTaskEnvelope`
and `ErrorEnvelope`, validated against the independent execution OpenAPI. It
contains only original synthetic bodies from the two new routes, including actual
error responses. Setup and competing manager operations continue to produce
separate original order, acceptance and TMS artifacts; execution E17 errors never
enter the old TMS validator. The real lost-response witness records only its
actual upstream response body, never the request or headers. The existing privacy
guard recursively checks all 133 initial fixture passwords and rejects traces; it is not a
claim that every possible cookie, CSRF or key leak has been independently scanned.
Helpers therefore keep those values only in memory, avoid sensitive-header HTTP
request logs, use generic forwarding failures and compare retry identities as
booleans. The new API scenario takes no execution UI screenshot.

The progress/evidence case adds `progress-evidence-contract-responses.json`,
restricted to original bodies using `ProgressPolicyEnvelope`, `ProgressEnvelope`,
`EvidenceEnvelope`, `EvidencePageEnvelope` and `ErrorEnvelope`. Successful content
responses are binary and explicitly excluded from the collector; failed content
responses are JSON errors. Runtime byte/hash/decode assertions do not pretend to
be JSON Schema validation. Original order/acceptance/TMS/execution responses remain
in their own artifacts and validators; new E18 codes never widen old contracts.
The privacy guard recursively checks all 133 initial fixture passwords and refuses traces;
raw photos, private storage information, request metadata and authentication
values are never serialized by this case. Storage/transaction failure injection,
zero-write GET and immutable DB history constraints are proved by backend IT,
not inferred from a JSON receipt or a browser hash alone.

The exception case writes `exception-contract-responses.json` containing only
original public synthetic bodies in its eight independent envelope schemas.
Successful private content is excluded; JSON refusals use only the new error
schema. Setup responses remain separated into the original order, acceptance,
TMS, vehicle, driver, execution and progress/evidence artifacts. New E19 errors
never widen old schemas. Its Node forwarding witness records only the actual
upstream response body, while retry identities are compared as booleans in
memory. The original four source pins and independent M4-01/M4-02 schema pins
remain checked by the independent validator. Browser schema validation is not a
claim of database transaction, provider or legal-policy correctness.

The completion case writes `completion-contract-responses.json` only for its
three new routes with `CompletionDetailEnvelope`, `CompletionPolicyEnvelope`,
`CompleteMyTaskEnvelope` and `ErrorEnvelope`. All eight older setup/history
artifact buckets retain their own schemas and validators; E20 errors are never
inserted into them. Only original public synthetic JSON is serialized. The
forwarding witness saves the actual upstream success body, not its request,
headers, cookies, key or PNG bytes. New conditional DTOs and seven source pins
are independently validated; same-transaction release, evidence-set sealing,
zero-case root locking and SQL rollback remain direct IT/runtime proof obligations.

The independent M4-05 `closure.spec.ts` adds one real management UI scenario per
viewport, preserving all previous 36 cases. ADMIN and DISPATCHER use the actual
pending/confirmed completion lists, explicit raw-reason confirmation, order
closure worklist, current facts, paged task review and immutable histories. The
accepted source has two persisted tasks: one `requiredTask=true` and one
`requiredTask=null`. Both genuinely depart, upload in-memory PNGs, progress,
complete and receive separate administrative confirmations. NULL is displayed as
unspecified, never an approved optional-task waiver. Unfinished or unconfirmed
tasks block closure; no task is silently skipped, cancelled or released.

Administrative version 0→1 leaves execution version 4, dispatch history,
reservations and TOM unchanged. Closure uses the distinct accepted TOM version
2→3, persists real CLOSED+ACCEPTED status and preserves child identities, raw
source fields and historical customer snapshots. Disabled/renamed CRM records
are not treated as new customer selections. Real stale UI submissions expose the
correct administrative/order version units and retain local reasons until
explicit fresh-data adoption. Both management commands actually commit at the
HTTPS backend before controlled response loss; manual retries encounter a real
CSRF refusal, retain the same frozen request/key and recover historical receipts.
A receipt is kept separate from fresh current detail.

After closure, the original order GET/list still use their original TOM envelopes;
create/PUT/accept replays retain their original OPEN snapshots. The narrow M4-04
completed read/receipt survives closure and physical resource reuse, while older
current-only driver APIs remain opaque 404. Manager historical facts are separate
from current physical-driver binding, and fresh role revocation refuses even an
original receipt. Explicit desktop/Pixel 7 screenshots contain the truthful
management records, with no private photo preview or original binary saved.
Normal-policy refusal, legacy CLOSED with no invented audit, populated upgrades,
root locking, full manifest/set seals and SQL rollback are backend integration
proofs, not claims inferred from these browser requests.

The new `closure-contract-responses.json` contains only `{schema, value}` entries
from the nine new operations, with `CompletionConfirmationSummaryPageEnvelope`,
`CompletionConfirmationDetailEnvelope`, `ConfirmCompletionEnvelope`,
`ManagementConfirmationHistoryPageEnvelope`, `ClosureSummaryPageEnvelope`,
`OrderClosureEnvelope`, `ClosureTaskReviewPageEnvelope`,
`OrderClosureHistoryPageEnvelope`, `CloseOrderEnvelope` and `ErrorEnvelope`.
The nine older artifact buckets retain their own validators. Ordinary CLOSED
GET/list bodies enter the original order bucket; `/accept` historical OPEN
receipts enter the acceptance bucket. Successful binary responses are excluded.
No request reason/body, headers, auth values, keys, cookies or private bytes are
serialized as request metadata. The saved loss witness is only the actual public
synthetic upstream response. JSON validation does not claim transaction proof.

M4-06 `execution-ui.spec.ts` preserves the previous 38 cases and adds one full
DRIVER UI flow each on desktop and Pixel 7. It opens the original fourteen-field
assignment record into the sixteen-field live/completed detail and performs all
seven command types through the actual product: departure, ordinary PNG upload,
progress, raw exception report, supplemental PNG upload, explicit supplement
submission and safe-release completion. ADMIN/DISPATCHER use their separate
existing interfaces for supplement requests, resolution, administrative
confirmation and real order closure. Setup and later physical-resource reuse use
the normal manager APIs, never business-table fixtures or a policy toggle.

Ordinary upload, supplemental upload, submit and complete each genuinely commit
at the HTTPS backend before the response is deliberately lost. Explicit retries
first reach a real CSRF refusal, then recover the same original bytes/body,
versions, path and key. True stale responses are produced by holding an already
user-confirmed request unchanged while another legal UI operation changes its
version; responses and tokens are not fabricated. The exception counter and
execution counter remain distinct. Raw NFD/NUL/whitespace text is preserved,
upload is not submission, submission is not resolution, and completion is not
whole-order closure. The completion set is exactly the previous successful
AWAITING transition's chosen ordinary evidence, excluding supplemental photos.

Private photo previews compare the real response bytes, hash, headers and decode
in memory. Close and actual tab visibility changes revoke URLs; a late genuine
binary response cannot recreate a preview after its context is hidden. Linux
verification uses Xvfb and xauth. Headed mode alone retains Playwright's focus
emulation, so only this real-visibility subcheck uses `native-browser.ts`: an
owned raw Chromium process on its own Xvfb display, attached through the public
`connectOverCDP({noDefaults:true})` existing default context. Its viewport matches
the project, while the original desktop/Pixel 7 contexts retain the complete
seven-command flow and device settings. No document-state override or synthetic
visibility event is used.

The native subcheck signs in normally as DRIVER A's third session, within the
existing three-session limit, and reads the same assignment/photo through the
actual UI. It genuinely logs out afterward without revoking the other sessions.
Its profile is a private 0700 temporary directory, CDP binds random loopback,
local TLS trusts only the generated DEV certificate public-key pin, and no global
certificate bypass or `context.request` is used. The helper closes only its owned
browser/Xvfb process group and removes its profile in `finally`. Its private
Xauthority file is 0600 inside the 0700 profile; other workers cannot steal its
real display focus, and the caller's DISPLAY is unchanged. Profiles, cookies
and browser-control endpoints never enter report artifacts. Native login first
requires actual foreground focus, then observes the real normal-DRIVER login
response after a keyboard submission. A failed native login clears its password
DOM and navigates away before the case asserts failure, so an automatic error
snapshot cannot retain the input. The unchanged privacy guard must still pass
before artifact upload. An incapable runner fails the genuine visibility
assertion; it is not silently skipped.

A deliberately failed logout never reaches the backend; authenticated actor
continuity is checked in memory while the same unknown note, original intent and
navigation guard survive. A successful logout or fresh authorization loss clears
private data, without claiming an earlier POST rolled back.

A complete receipt can remain unknown while managers genuinely confirm, close
the source order and reuse its human/vehicle resources. The original completed
read and explicit same-intent replay remain narrow historical facts; older
current-only endpoints remain opaque 404. Rebinding the same physical driver to
a different normal account grants the narrow read without transferring the old
actor's receipt or pending intent. The completed UI stops old progress/photo/
exception reads and exposes no manager, full-order, customer or resource details.
Explicit screenshots contain only safe source, recovered supplement and completed
views, with all private previews closed; automatic screenshots and traces are off.

No new schema or validator is introduced for M4-06. All ten existing response
buckets use their original schema allowlists; successful private binaries are
excluded before JSON collection. Failed-response witnesses serialize only actual
public synthetic response bodies. Original PNGs, Blob URLs, request bodies,
headers, keys, auth values and credentials stay out of artifacts. The existing
privacy check covers all 133 initial fixture passwords. Database atomicity, default
UNCONFIRMED policy, upgrade/seal/rollback and provenance matrices retain the
existing backend and meaningful client/model test obligations; browser evidence
is not a substitute for those checks.

CI validates health/status, Flyway V1–V14 on an empty database, exact UPMS/ADM/CRM/TOM/FMS/TMS
tables (47 including Flyway), the fixed ORDER_TYPE enum and four empty configurable categories,
absence of seeded accounts, parameter definitions/values, CRM records, TOM records,
vehicles, drivers, dispatch assignments/events/reservations, execution/event/command
records, evidence/objects/event-links/upload-ledgers, exception/supplement facts,
completion/release ledgers, administrative confirmation facts and closure
witnesses/manifests/ledgers, restart stability,
and these browser flows. Report upload requires the privacy check to confirm
that fixture credentials and trace archives are absent.
It removes its credential file, disposable containers, and database volume after
completion. When finished with a local test database, remove only that isolated
project:

```sh
rm -f "$E2E_FIXTURE_PATH" "$M4_SECRET_INVENTORY_PATH" "$M4_SECRET_INVENTORY_PATH.lock" "$M4_SECRET_INVENTORY_PATH.pending"
docker compose --env-file /tmp/xk-e2e.env down --volumes --remove-orphans
rm -f /tmp/xk-e2e.env
```

## M4-07 required full integration drill

The short browser command above is not the full M4 acceptance. Run the twelve
stdlib CHECK comparison regression cases before integration, and the drill after
all forty cases, using the same source project, fixture and combined inventory:

```sh
PYTHONPATH=tests/e2e python3 -m unittest -v test_m4_integration_db
export M407_STATE_FILE=/tmp/xk-m407-owned.json
export M407_WORK_DIR=/tmp/xk-m407-private
python3 tests/e2e/m4-integration-drill.py \
  --source-env /tmp/xk-e2e.env --base-url "$E2E_BASE_URL" \
  --ca-cert infrastructure/dev-tls/localhost.crt \
  --fixture "$E2E_FIXTURE_PATH" --secret-inventory "$M4_SECRET_INVENTORY_PATH" \
  --state-file "$M407_STATE_FILE" --work-dir "$M407_WORK_DIR" --restore-port 8445
```

Use a fresh nonexistent private work directory and an unused loopback restore
port. The stdlib-only tool validates the source Compose project and gateway,
creates H/P/C/A cohorts using real HTTPS APIs, then verifies populated PG/backend
restart, all 47 tables and logical schema, private photo integrity, a full PG17
custom dump restored to a differently named database, and an unmodified normal
production JAR. The wrong-actual-database synthetic factory must refuse startup;
normal policy refuses fresh eligible operations while historical receipts and
narrow completed reads remain valid. Real PTY init/recover exercise the production
Console guard with independent account/audit allowlists and unchanged business
facts. The twelve unit cases retain literal values, order, operators, metadata
and unknown CHECK syntax while allowing only the two observed lossless enum
ARRAY deparses. No SQL repair, trigger disabling or product policy change occurs.

After the drill, run all ten existing response validators, collect diagnostics,
and only then run `assert_report_privacy.py` with `E2E_FIXTURE_PATH` temporarily
pointing to the combined inventory. It covers initial, browser-generated,
Console/changed and database secret values, including superseded values, across
reports, test-results, artifacts and embedded archives. Only a successful guard
permits retention. Fixture identity count, actual account rows and secret value
count are separate facts. Do not export private dumps, raw DB rows, password
hashes, Console transcripts or successful photo bytes.

Before the source Compose teardown, including on failure, run the idempotent
owned-resource cleanup while its external registry and source env still exist:

```sh
python3 tests/e2e/m4-integration-drill.py --cleanup-only \
  --state-file "$M407_STATE_FILE" --source-env /tmp/xk-e2e.env
```

Then remove private fixture/inventory/registry files and tear down only the
isolated source project. Keep inventory until the final privacy gate has run.
The CI performs this sequence as a required job, preserves cleanup failures,
and retains the ordinary development project. Cleanup is not a claim of physical
secure erasure or production RTO/RPO. The final mobile acceptance additionally
checks five actual action-button boxes are full-width, at least 180×44 CSS pixels,
ordered and nonoverlapping; all original forty case flows remain present.
