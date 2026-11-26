# Browser verification

The suite has 36 cases: eighteen each for desktop Chromium and Pixel 7. The
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
runner creates 110 synthetic login accounts: independent UPMS, ADM, CRM, TOM API,
order UI, acceptance, and FMS ADMIN/DISPATCHER/DRIVER sets, plus independent TMS
ADMIN/DISPATCHER/two-DRIVER sets for the API and UI scenarios in each browser project. It accepts only
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
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_execution_contract.py \
  --responses 'test-results/**/execution-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_progress_evidence_contract.py \
  --responses 'test-results/**/progress-evidence-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_exception_contract.py \
  --responses 'test-results/**/exception-contract-responses.json'
/tmp/xk-order-contract-env/bin/python tests/contracts/validate_completion_contract.py \
  --responses 'test-results/**/completion-contract-responses.json'
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
guard recursively checks all 110 fixture passwords and rejects traces; it is not a
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
The privacy guard recursively checks all 110 fixture passwords and refuses traces;
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

CI validates health/status, Flyway V1–V13 on an empty database, exact UPMS/ADM/CRM/TOM/FMS/TMS
tables (42 including Flyway), the fixed ORDER_TYPE enum and four empty configurable categories,
absence of seeded accounts, parameter definitions/values, CRM records, TOM records,
vehicles, drivers, dispatch assignments/events/reservations, execution/event/command
records, evidence/objects/event-links/upload-ledgers, restart stability,
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
