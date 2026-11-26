# Preserved order contract checks

The four original order contract files remain byte-for-byte snapshots of the
accepted 0.2.0 source. Their SHA-256 fingerprints use the standard `order-sources.sha256` manifest.
Their historical `not_implemented` / `not_run` annotations
are not edited into current execution evidence. The TOM validation document and
CI run identify the actual tested implementation.

`validate_order_contract.py` checks the source hashes, 16 positive and 25 negative
schema examples, 11 graph examples, 15 operation/role bindings, and the 40-field
CSV. It uses JSON Schema 2020-12 and the original OpenAPI components. Date and
RFC3339 calendar/offset/microsecond formats are checked explicitly; format
validation is not silently omitted. References resolve only in memory.

The optional HTTP evidence check validates unmodified synthetic response bodies
captured by the desktop and mobile Playwright flows. Evidence contains no cookie,
CSRF, password, request context, or authentication headers. A missing/empty file
match fails the check. Validation failures print schema, pointer, and keyword
without echoing submitted data.

Use CPython 3.12 on Linux x86_64 (verified locally with 3.12.14 and on GitHub ubuntu-24.04 with 3.12.3). The seven test-only
packages and compatible wheels are version- and SHA-256-pinned in
`requirements.txt`; no Maven/npm/production dependency was added.

```sh
python3 -m venv /tmp/xk-contract-env
/tmp/xk-contract-env/bin/python -m pip install --require-hashes -r tests/contracts/requirements.txt
/tmp/xk-contract-env/bin/python tests/contracts/validate_order_contract.py
# After the isolated HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_order_contract.py \
  --responses 'test-results/**/order-contract-responses.json'
```

CI installs the same hashed wheel set in a temporary virtual environment, checks
both the preserved examples and actual HTTP responses, and includes the pinned
Python requirements in the dependency vulnerability scan. The wheels deliberately
match the CPython 3.12 / Linux x86_64 acceptance environment. Other interpreter or
platform wheels require a separately verified lock update.

## Independent FMS vehicle contract checks

`validate_vehicle_contract.py` is separate from the preserved TOM checker. It reads
`docs/06_api/fms-vehicles-openapi.yaml`, requires the exact six FMS operations and
nineteen component schemas, checks schema metadata and local references, and runs
20 positive / 25 negative static fixtures plus three external-reference rejection
self-checks. These fixtures exercise response envelopes, nullable fields, precise
decimal strings, Gregorian dates including 1500/0001, partial PATCH, version bounds,
read-only metadata, and the exclusion of IN_USE.

Input `x-normalizedMinLength` / `x-normalizedMaxLength` are engineering annotations,
not raw-string length rules. The static positives deliberately include padded/NFD
plate input and blank registration text that may become canonical values; the tool
does not pretend to execute Java normalization or validate vehicle classification,
uniqueness, permissions, database locks, or dispatch eligibility. Product tests own
those checks. Output schemas enforce the canonical response bounds.

The tool uses the same existing pinned PyYAML/jsonschema/referencing packages,
an in-memory registry with a retrieval-denying handler, and explicit date/date-time
format checks. It rejects non-local references or resource-ID overrides before
validation. Evidence JSON rejects duplicates, non-JSON constants, invalid Unicode,
and unknown envelope names. Decimal parsing keeps fractional version violations
exact instead of allowing binary-float rounding through a safe-integer boundary.
Failure output prints schema/pointer/keyword, never response values or parser
diagnostics containing submitted data.

The desktop/mobile collector writes `vehicle-contract-responses.json` as a
nonempty array of `{schema,value}` records containing only public synthetic FMS
response bodies. Accepted schema names are `VehicleEnvelope`, `PageVehicleEnvelope`,
`VehicleTypesEnvelope`, `PageVehicleOptionEnvelope`, and `ErrorEnvelope`. No request
body, cookie, CSRF token, authentication header, credential or trace is captured.
The existing artifact privacy check still runs before upload.

```sh
# Static tooling checks only; explicitly reports HTTP acceptance as not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_vehicle_contract.py --static-only
# After the isolated real HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_vehicle_contract.py \
  --responses 'test-results/**/vehicle-contract-responses.json'
```

Without arguments the response glob above is required by default. Missing matches,
an empty evidence file/array, an unknown schema, extra capture metadata or a failed
schema all fail the command; they cannot be reported as successful HTTP validation.
`--static-only` and `--responses` are mutually exclusive. Static fixture success is
tooling evidence, not proof that the product or an HTTP flow has run.

## Independent FMS driver contract checks

`validate_driver_contract.py` independently reads
`docs/06_api/fms-drivers-openapi.yaml`. It requires exactly eight operations and
28 component schemas, checks each schema's metadata, manager versus DRIVER-self
role bindings and Session/CSRF declarations, and validates 27 positive / 40 negative
static fixtures plus three external-reference rejection self-checks. The existing
TOM and vehicle validators, source contracts and hashed requirements remain unchanged.

The fixtures cover the distinct 22-field detail, 17-field summary, 12-field self,
and nine-field option DTOs. Extra account, license-reference, audit or authentication
fields fail the privacy-specific schemas. Nullable metadata, historical disabled
license types, independent employment/duty combinations, minimal account candidates,
partial PATCH, safe-integer versions, descriptive contact text and Gregorian dates
including 1500/0001 are exercised. Raw padded/NFD input is intentionally permitted
by the schema: `x-normalizedMinLength` / `x-normalizedMaxLength` describe canonical
limits, not raw-string limits. The tool does not execute Java normalization, verify
UPMS binding eligibility, status transitions against stored state, database locks,
permissions or dispatch/legal eligibility; product tests own those checks.

The tool reuses the existing hash-pinned test-only packages, an in-memory registry,
a retrieval-denying handler and explicit date/date-time checks. All `$ref` and
`$dynamicRef` must point into the same OpenAPI document; resource-ID overrides and
network/file retrieval are forbidden. Evidence JSON rejects duplicate keys,
non-JSON constants, invalid Unicode and unknown response schemas. Decimal parsing
and exact mathematical-integer validation prevent fractional values near the safe
version boundary from passing through binary-float rounding. Diagnostics print
schema/pointer/keyword, without submitted values or parser exception messages.

The isolated browser collector writes `driver-contract-responses.json` as a
nonempty array of body-only `{schema,value}` records. Accepted schema names are
`DriverEnvelope`, `PageDriverSummaryEnvelope`, `DriverSelfEnvelope`,
`PageDriverOptionEnvelope`, `DriverLicenseTypesEnvelope`,
`PageDriverAccountOptionEnvelope` and `ErrorEnvelope`. No cookie, CSRF token,
request body, credential, authentication header or trace is captured. Existing
artifact privacy checks remain applicable.

```sh
# Static tooling only; explicitly reports HTTP acceptance as not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_driver_contract.py --static-only
# After the isolated real HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_driver_contract.py \
  --responses 'test-results/**/driver-contract-responses.json'
```

Without arguments the driver response glob above is mandatory by default. Missing
matches, empty files/arrays, extra capture metadata or a schema error fail. Static
success alone cannot be reported as actual HTTP validation; `--static-only` and
`--responses` are mutually exclusive. Commands use the same temporary virtual
environment and hashed install documented above.

## Incremental TOM acceptance contract checks

`validate_acceptance_contract.py` reads the independent
`docs/06_api/tom-acceptance-openapi.yaml`: exactly one new POST operation and four
incremental schemas (`AcceptOrderRequest`, `AcceptedOrderEnvelope`, `ErrorEnvelope`,
`IdempotencyReplayed`). It checks operation, manager-role, JSON, Session/CSRF,
required path/header, HTTP-status and response-schema declarations. The success
schema uses `allOf` with the **exact preserved** `OrderEnvelope`, narrowing only
OPEN, ACCEPTED and version >= 1. The error schema reuses the original envelope
and code enum, without adding metadata, timestamps or new business error codes.

The validator verifies the four immutable source SHA-256 fingerprints and checks
the schemas' metadata. Only three exact `openapi.yaml#/components/schemas/...`
references (`Uuid`, `OrderEnvelope`, `ErrorEnvelope`) are allowlisted in the new
document; they resolve through the already loaded, hash-verified original OpenAPI
in an in-memory registry. Original internal references stay in that resource.
Network and filesystem retrieval, alternative relative paths, resource-ID
overrides and arbitrary external references are rejected. The synthetic registry
URIs are identifiers, not fetched URLs. Existing order, vehicle and driver tools,
the source manifest and hash-pinned requirements remain unchanged.

Static checks contain 26 positives / 33 negatives and seven reference-rejection
self-checks. They cover all three order types, nullable/raw/NUL data, legacy
envelope exactness, accepted-state narrowing, original errors, Gregorian calendar
and offset/microsecond rules, exact safe mathematical integers, rejected actor/time
inputs and precise replay-header text. Request shape permits the maximum version;
the transaction's inability to increment it is a domain 409, not a schema 400.
These tests do not execute ORD completeness, authorization, normalization,
transactions, event immutability or a real HTTP request.

Standard JSON Schema cannot compare a response version with a separate request's
expectedVersion. The spec records `x-versionRule` and the client/IT/E2E must verify
identity, `version == originalExpectedVersion + 1`, HTTP 200 and the mandatory
replay header directly. Body-only evidence validates the response's own schema;
it does not claim to verify those cross-request or HTTP-header relationships.

The new collector writes `acceptance-contract-responses.json` as a nonempty array
of `{schema,value}`, allowing only `AcceptedOrderEnvelope` and `ErrorEnvelope`.
It contains public synthetic response bodies, never headers, cookies, CSRF,
credentials, request bodies or event internals. Accepted GET/list/check responses
and rejected PUT responses remain in `order-contract-responses.json`, validated
by the unchanged original tool. A post-acceptance replay of an old POST/PUT key
still returns its original historical DRAFT data; the new validator must not
rewrite or impose accepted-only schemas on those old operations.

```sh
# Static tooling only; explicitly reports HTTP acceptance as not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_acceptance_contract.py --static-only
# After the isolated real HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_acceptance_contract.py \
  --responses 'test-results/**/acceptance-contract-responses.json'
```

The same hashed temporary environment above suffices; no dependency is added.
Without arguments the acceptance glob is mandatory by default. Missing or empty
evidence, duplicate JSON properties, non-JSON constants, invalid Unicode, unknown
schema names, extra capture metadata or schema errors fail. Decimal parsing
prevents a fractional safe-boundary value from passing through float rounding.
Diagnostics print schema/pointer/keyword without submitted values or raw parser
messages. Static success is tooling evidence, not proof of product or HTTP
execution; `--static-only` and `--responses` are mutually exclusive.

## Independent TMS dispatch contract checks

`validate_tms_contract.py` reads the independent
`docs/06_api/tms-dispatch-openapi.yaml`. It requires exactly seven operations and
42 component schemas, manager versus DRIVER-self roles, Session/CSRF, strict query
rejection, write path/idempotency headers, exact JSON bodies and success/error
response declarations. TMS uses the company `0000`/`timestamp` envelope; the original
TOM `OK`/`traceId` contract and all existing validators remain unchanged. The four
original source hashes are independently verified using the existing, unchanged
`order-sources.sha256` manifest. No dependency or scanner exclusion is added.

Static checks contain 51 positives / 113 negatives and eight reference-rejection
self-checks. They cover initial/terminal assignment versions, two/three-resource
snapshots, raw TOM NUL/NFD/decimal/offset/null values, generation bounds and exact
mathematical integers, bounded pages, policy mode, detailed business errors, and
privacy-specific self DTO exclusions. Authenticated E0403 examples distinguish
`CSRF_INVALID` from `FORBIDDEN` using only `data.reason`; the existing null-data
positive remains for schema compatibility. Anonymous CSRF rejection is 401/null.
Reason-input canonical bounds are recorded
as `x-normalizedMinLength/MaxLength`: a padded/NFD raw string can be valid after
server normalization, so the schema does not impose a false raw maximum. Product
tests own NFC/strip, semantic no-op, same-resource comparison and eligibility.

All `$ref`/`$dynamicRef` values must resolve inside this OpenAPI document. Network,
filesystem retrieval, relative external references and resource-ID overrides are
forbidden before an in-memory retrieval-denying registry is created. The existing
hash-pinned PyYAML/jsonschema/referencing packages are reused. Evidence parsing
rejects duplicate keys, non-JSON numeric constants, invalid Unicode, unknown schema
names and extra capture metadata; Decimal parsing avoids binary-float rounding at
the safe generation boundary. Diagnostics print schema/pointer/keyword, never
submitted values, parser messages, headers or credentials.

The collector writes `tms-contract-responses.json` as nonempty arrays containing
only `{schema,value}` public synthetic response bodies. Accepted names are
`TaskPageEnvelope`, `DispatchDetailEnvelope`, `CommandResultEnvelope`,
`ResourcePageEnvelope`, `MyTaskPageEnvelope` and `ErrorEnvelope`. No request body,
key, Cookie, CSRF token, authentication header, password or trace is captured.
Existing recursive fixture-password and trace checks must pass before upload.

A body-only schema cannot establish HTTP status or `Idempotency-Replayed`, compare
result generation with a separate original request, verify current DRIVER binding,
or prove transaction/occupancy atomicity, legal eligibility, source locks, snapshot
immutability or rollback. IT/E2E must directly verify those relationships; the spec
records `x-versionRule`/`x-replayRule` without pretending that schema validation
executes the product. Static fixtures and tool fail-path checks are tooling
verification, not proof that real HTTP acceptance has run.

```sh
# Static tooling only; explicitly reports HTTP acceptance as not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_tms_contract.py --static-only
# After the isolated real HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_tms_contract.py \
  --responses 'test-results/**/tms-contract-responses.json'
```

Without arguments the response glob is mandatory. Missing matches, empty files or
arrays, unknown envelope names, extra metadata and schema failures all fail the
command. `--static-only` and `--responses` are mutually exclusive. The commands use
the existing temporary virtual environment and hashed install described above.

## Independent M4-01 driver execution contract checks

`validate_execution_contract.py` reads the frozen independent
`docs/06_api/tms-execution-openapi.yaml`: exactly two operations and 22 component
schemas, DRIVER-only roles (both management roles denied), Session/CSRF, required
assignment path and write idempotency header, strict query rejection, request JSON
and success/error declarations. The new GET is the original fourteen-field MyTask
projection plus a three-field execution member; the POST returns a minimal
three-field departure receipt. The old fourteen-field list and all M1–M3 source
contracts/validators remain unchanged. The four original source fingerprints are
independently verified using the existing unchanged SHA-256 manifest.

Static checks contain 32 positives / 111 negatives and eight reference-rejection
self-checks. They cover virtual WAITING/0/null, DEPARTED/1 and reserved later read
enum values, independent execution-version bounds, exact mathematical integers,
minimal receipt narrowing, raw TOM NUL/NFD/decimal/offset/microsecond/contact
semantics, structured E17 errors and privacy-specific field exclusions. Future
phase static examples validate the reserved read type; they do not mean that
progress, photos, completion, release or closure were implemented or exercised.

The tool reuses the existing hash-pinned PyYAML/jsonschema/referencing packages,
explicit Gregorian/date-time checks and a retrieval-denying in-memory registry.
External/relative/file/network references, unresolved local references and resource
identity overrides are rejected. Evidence parsing rejects duplicate keys, non-JSON
constants, invalid UTF-8/Unicode, extreme invalid Decimal input, unknown envelope
names and extra capture metadata. Exact Decimal arithmetic prevents a fractional
safe-version boundary from passing through binary-float rounding. Error diagnostics
show schema/pointer/keyword, never submitted values, parser messages, credentials,
request bodies, headers or private storage details.

New captures are nonempty `execution-contract-responses.json` arrays of public
synthetic `{schema,value}` response bodies only, with schema names
`MyTaskExecutionEnvelope`, `DepartMyTaskEnvelope` or `ErrorEnvelope`. E17 errors
belong only in this artifact; old setup/order/acceptance/TMS/FMS operations retain
their original collectors and validators. No Cookie, CSRF token, key, raw request,
authentication header, password or trace is recorded. Existing recursive fixture
credential/privacy checks remain required before upload.

A body-only schema cannot prove current-person/current-assignment authority,
source/occupation freshness, replay priority, transaction rollback, zero GET
mutation, or HTTP200/header/path/expectedVersion+1 witnesses. IT/E2E verify those
directly. Static tooling is not real HTTP or operational-policy acceptance; normal
UNCONFIRMED departure is still blocked and no formal evidence/release/closure
policy is inferred from a positive synthetic case.

```sh
# Static only; explicitly reports actual HTTP acceptance as not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_execution_contract.py --static-only
# After the isolated real HTTPS browser run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_execution_contract.py \
  --responses 'test-results/**/execution-contract-responses.json'
```

The default HTTP glob is mandatory without `--static-only`. Missing files, empty
files/arrays, malformed JSON/Unicode, unknown schemas, extra metadata and schema
failures fail the command. `--static-only` and `--responses` are mutually exclusive.
No new dependency or scanner exclusion is added.

## Independent M4-02 progress and private evidence checks

`validate_progress_evidence_contract.py` reads only the new
`docs/06_api/tms-progress-evidence-openapi.yaml`: exactly five operations and 25
schemas. It independently checks the unchanged four original source fingerprints
and pins the frozen M4-01 OpenAPI SHA-256; seven common schemas and Session/CSRF
schemes must equal their original definitions. No old validator, dependency,
manifest, M401 shape or original fourteen-field task list is changed.

Static tooling contains 59 positives / 291 negatives and eight local-reference
rejection self-checks. It covers exact policy7/evidence10/result3/page5 DTOs,
conditional blocked/null policy and adjacent stages, the full reserved read-phase
enum, PNG-only verified metadata, canonical lowercase response UUID/hash, privacy
field rejection, strict mathematical integers, E18/common413 errors and nullable
reason-only403 compatibility. The explicit custom `x-uniqueCanonicalUuids` keyword
also rejects mixed-case duplicate request UUID identities; product IT/E2E still
proves canonical sorted-set command replay. `execution-version-header` is a custom
format enforcing the exact ASCII digits grammar and MAX numeric bound, separate
from JSON 1/1.0/1e0 equivalence. Static reserved-phase metadata examples are type
coverage, not authorization to upload at WAITING or implement completion.

Only isolated test-source policy/provider beans may return count1, PNG, 65536-byte,
4096-pixel requirements after verifying the actual `xk_logistics_e2e` database and
same synthetic scope. Normal policy/provider remain UNCONFIRMED/unavailable, with
null requirements and no zero/empty waiver. The 1048576-byte stream cap and 32-ID
command cap are technical bounds, not approved D06 photo rules. Private content
read permission is independent of whether a next progress rule exists; these
schemas do not approve a formal storage provider, retention, release or closure.

All references are internal and checked before a retrieval-denying registry is
created. Existing hash-pinned packages are reused. Evidence parsing rejects extra
capture metadata, unknown schemas, duplicates, non-JSON constants, malformed
UTF-8/Unicode and invalid extreme Decimal input. Exact Decimal checks avoid
rounding a fractional safe-version boundary. Diagnostics show only schema,
pointer and validation keyword, never submitted values or parser messages.

The new nonempty `progress-evidence-contract-responses.json` arrays contain only
public synthetic `{schema,value}` JSON bodies. Names are `ProgressPolicyEnvelope`,
`ProgressEnvelope`, `EvidenceEnvelope`, `EvidencePageEnvelope`, `ErrorEnvelope`.
Content-route JSON failures use ErrorEnvelope; successful binary photos are never
captured as JSON/base64/files. Original execution/order/acceptance/TMS/FMS setup
responses retain their original artifacts and validators. No request, key, Cookie,
CSRF token, password, authentication header, original image or trace is captured;
existing recursive credential/privacy scans remain required before upload.

A body-only schema cannot prove actual bytes/CRC/pixels/hash, response status,
no-store/nosniff/length/replay headers, current-session/current-person scope,
canonical path identity, version+1, GET zero mutation, lock sequencing or atomic
blob/metadata/event/link/receipt rollback. Real PostgreSQL and HTTPS IT/E2E assert
those separately, including lost-response retry and reads after progress. Static
fixtures do not constitute actual HTTP or formal policy acceptance.

```sh
# Static tooling only; actual HTTP is explicitly not run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_progress_evidence_contract.py --static-only
# After the isolated real HTTPS run:
/tmp/xk-contract-env/bin/python tests/contracts/validate_progress_evidence_contract.py \
  --responses 'test-results/**/progress-evidence-contract-responses.json'
```

Without `--static-only` the response glob is mandatory. Missing matches, empty
files/arrays, malformed evidence, unknown envelopes, extra metadata and schema
failures fail the command. `--static-only` and `--responses` are mutually exclusive.
No new dependency or artifact-scanner exception is introduced.

## Independent M4-03 exception and supplement checks

`validate_exception_contract.py` reads the new `docs/06_api/tms-exceptions-openapi.yaml`
only: exactly 13 operations / 46 schemas, fresh role-specific self versus manager
routes, original Session/CSRF, exact query/header/body declarations, bounded actual
JSON16KiB/raw1MiB inputs, post-ledger miss guarantees and private binary headers.
The unchanged original four source hashes plus the exact frozen M401 and M402
OpenAPI pins are independently verified (six sources); fourteen reused component
schema constraints and Session/CSRF schemes must match preserved M402 definitions.
Only the new Version description explains exception versus execution field units;
its numeric/type keywords remain identical to the preserved source.
No old API, validator, manifest, dependency or source fingerprint is changed.

Static tooling contains 65 positives / 498 negatives and eight reference-rejection
self-checks. It covers raw NUL/NFD/leading/trailing/newline preservation, Java
whitespace/SpaceChar/FEFF nonblank checks and code-point limits; exact summary9,
record14, activeRequest5, detail3, actions6, event11/manager12, upload6 and evidence3
shapes; three reporting phases versus the full five-phase historical execution
view; status/blocking/nullable/availability conditions; canonical sorted UUID sets
and count matching; creation0 then exact event+1; separate safe exception/execution
version units and E1901 exactly-one actual token; privacy and E19/common errors.
Synthetic count1/PNG/65536-byte/4096-pixel fixtures are isolated policy examples,
not formal D04/D05/D06/provider/retention/safe-release approval.

Explicit independent custom keywords validate raw nonblank Unicode scalar text,
case-insensitive duplicate request UUIDs, canonical output-set order, bounded
active-request count equality and exact event version increment. Header grammar
uses the existing-style custom safe digits format; JSON retains mathematical
1/1.0/1e0 equivalence with exact Decimal arithmetic. Local reference preflight and a
retrieval-denying registry prevent network/file/foreign reference retrieval and
resource identity overrides. Parsing rejects duplicate keys, non-JSON constants,
malformed Unicode/UTF-8, extreme invalid Decimal input, unknown envelopes and extra
capture metadata. Diagnostics print schema/pointer/keyword without submitted values.

Only nonempty `exception-contract-responses.json` arrays of public synthetic
`{schema,value}` JSON bodies are accepted. Names are `ExceptionSummaryPageEnvelope`,
`ExceptionRecordEnvelope`, `ExceptionDetailEnvelope`, `DriverExceptionHistoryPageEnvelope`,
`ManagerExceptionHistoryPageEnvelope`, `SupplementUploadEnvelope`,
`SupplementEvidencePageEnvelope`, `ErrorEnvelope`. Manager binary success is
excluded; content errors use ErrorEnvelope. Earlier setup/order/acceptance/TMS/
execution/progress responses retain their original collectors. No raw PNG/base64,
request, credential, key, cookie, token, header, filename or trace is captured;
existing recursive privacy/credential scans remain required before upload.

Schema checks cannot prove current self binding or manager historical company
scope, original-session freshness, request belongs-before-ledger versus active
only-on-miss, actual streamed bytes/PNG/hash/cache/status, command receipt witness
against its original request, atomic rollback or zero-case blocker-query locking.
IT/E2E verify these directly. Metadata/history GET failures never settle a separate
unknown POST; the explicit E1901/02/03/07/08 miss whitelist applies only to the same
frozen POST attempt. E1906 and common errors are not rollback evidence. Successful
receipt and fresh current detail are distinct; RESOLVED does not authorize release.

```sh
# Static only; explicitly not real HTTP acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_exception_contract.py --static-only
# After isolated real HTTPS acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_exception_contract.py \
  --responses 'test-results/**/exception-contract-responses.json'
```

The default response glob is mandatory without `--static-only`; missing matches,
empty/malformed files or arrays, extra metadata, unknown schemas and body failures
are nonzero. `--responses` and `--static-only` are mutually exclusive. No new
package or scanner exception is introduced. Static tooling is not product HTTP or
operational policy acceptance.

## Independent M4-04 completion and effective-release checks

`validate_completion_contract.py` reads the new `docs/06_api/tms-completion-openapi.yaml`
only: exactly three operations / 29 schemas. It independently verifies seven
preserved sources (the original four manifest entries plus frozen M401/M402/M403
OpenAPI digests), seventeen copied component definitions and every original safe14
source-field definition in the new sixteen-field detail. Prior contracts/tools and
old14/15 DTOs remain unchanged. Public SHA-256 values use explicit YAML `!!str` and
parenthesized complete Python literals from the start; no scanner exclusion or
validation weakening is used.

Static tooling contains 57 positives / 368 negatives and eight reference-rejection
self-checks. It covers detail16/result4/witness5/policy8/requirements6, exact required
keys, raw TOM NUL/NFD/space/decimal-scale/offset-microsecond/contact semantics,
virtual WAITING and true COMPLETED nullability, literal safe confirmation, equal
server completion/release timestamps, current policy mode versus historical
witness mode, ALREADY_COMPLETED priority, no-null/empty-list policy waiver,
canonical sorted bounded readiness IDs and case-insensitive duplicate request
identities, exact mathematical integers and E20/common errors. Input numeric and
reserved read-type examples do not authorize an otherwise invalid operation.

The independent custom keywords enforce canonical UUID identity/order and matching
public timestamp fields. Exact Decimal arithmetic rejects fractional/unsafe
version boundaries while accepting JSON1/1.0/1e0. Safe local references are checked
before an in-memory retrieval-denying registry is built; network/file/foreign refs
and resource-ID overrides are rejected. Sanitized evidence parsing rejects
non-JSON constants, duplicates, malformed Unicode/UTF-8, invalid extreme Decimal
input, unknown envelopes and extra capture metadata. Diagnostics expose only
schema/pointer/keyword, not submitted values or parser messages. Existing
hash-pinned dependencies are reused.

Accepted nonempty `completion-contract-responses.json` arrays contain only public
synthetic `{schema,value}` JSON bodies named `CompletionDetailEnvelope`,
`CompletionPolicyEnvelope`, `CompleteMyTaskEnvelope`, `ErrorEnvelope`. Original
setup/older API responses keep their own artifacts and validators. No image,
base64, request, key, Cookie, token, header, actor audit or trace is captured;
existing recursive privacy/credential scans remain mandatory before upload.

The normal CompletionPolicy is UNCONFIRMED, independent of a historical witness.
Only isolated actual-DB-gated test-source policy may expose the named latest
readiness-event count1/PNG/65536-byte/4096-pixel rule and synthetic safety decision.
Checkboxtrue, RESOLVED, AWAITING, no occupation or an old release is not formal
approval. Completed reads are a new narrow same-physical-driver/current-anchor
capability; old current-only self APIs remain404 after release, and old14 discovery
still returns recorded ASSIGNED anchors. This does not grant arbitrary historical
photos or future closure authority.

Schema declarations do not prove actual streamed JSON16KiB enforcement, original
progress success receipt/set seals, complete2/3 marker identity, zero-case blocker
root locking, source-version dependency readiness, original-session/binding
freshness, same-actor receipt ordering, header/status/version+1 witness or atomic
rollback. PostgreSQL/HTTPS IT/E2E must prove these, including direct-SQL constraints
and upgrade original-column fingerprints. Body schemas cannot establish the V13
seals or real product acceptance. The E2001/02/03/07/08/09 miss whitelist applies
only to the SAME frozen POST; a read error never settles it and E2006/common errors
are not proof of rollback.

```sh
# Static only, explicitly not actual HTTP acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_completion_contract.py --static-only
# After isolated real HTTPS acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_completion_contract.py \
  --responses 'test-results/**/completion-contract-responses.json'
```

The default response glob is mandatory without `--static-only`; missing matches,
empty/malformed evidence, unknown schemas, extra metadata and body failures fail.
`--static-only` and `--responses` are mutually exclusive. No new dependency or
artifact-scanner exception is introduced.

## Independent M4-05 management-confirmation and TOM-closure checks

`validate_closure_contract.py` reads only the new
`docs/06_api/tom-closure-openapi.yaml`: exactly nine operations / 49 schemas. It
independently verifies eight preserved sources (the original four manifest entries
plus frozen M401/M402/M403/M404 OpenAPI digests), twelve copied components and the
original taskNo/orderNo field definitions. Public SHA-256 pins use explicit YAML
`!!str` and parenthesized complete Python literals. Old schemas, validators,
requirements and checksum manifests remain unchanged; no scanner exclusions.

Static tooling contains 99 positives / 603 negatives and eight reference-rejection
self-checks. It covers administrative counter0/1 versus TOM/source/execution units,
summary11/fact6/detail5/confirmation-result6, closure-view9/counts6/policy4/
requirements4/task-review14/witness9/result6, raw NUL/NFD/space/newline/Unicode
codepoint boundaries, exact required keys, manager-only actor snapshots, conditional
current mode versus recorded mode, ALREADY_CONFIRMED/CLOSED priority, honest legacy
CLOSED/null audit, all-task true|null semantics, literal confirmations, page metadata
arithmetic, one-step closure versions and matching typed witness fields. No public
or client proof digest, false requiredTask waiver or E2111 is accepted.

Independent custom keywords enforce original raw nonblank Unicode semantics,
matching public witness fields, completion/release timestamp equality, exact TOM
version increments, ordered task counts and bounded page arithmetic. Decimal
arithmetic accepts JSON1/1.0/1e0 and rejects fractional/unsafe version boundaries.
E2101 requires exactly one actualConfirmationVersion0/1 or actualOrderVersion;
route-specific units must additionally be checked by strict clients and actual
HTTP tests. Other business/common errors cannot smuggle counters, identity, SQL,
storage or another module's error namespace. Canonical local-only references are
checked before an in-memory retrieval-denying registry; network/file/foreign refs
and resource-ID overrides fail without retrieval.

Sanitized evidence parsing rejects duplicates, malformed UTF-8/Unicode, non-JSON
constants, invalid extreme Decimal input, unknown envelopes, empty arrays/files,
extra capture metadata and body failures. Diagnostics print only schema, pointer
and keyword, never submitted values or parser messages. The normal policies are
UNCONFIRMED; the named isolated actual-DB-gated test cohort requires ALL persisted
true|null tasks genuinely complete and management-confirmed, with zero whole-order
blocking exceptions/effective occupations. It does not approve formal confirmer
responsibility, unspecified-task handling, photos/provider or operational release.

Accepted nonempty `closure-contract-responses.json` arrays contain only public
synthetic `{schema,value}` bodies named `CompletionConfirmationSummaryPageEnvelope`,
`CompletionConfirmationDetailEnvelope`, `ConfirmCompletionEnvelope`,
`ManagementConfirmationHistoryPageEnvelope`, `ClosureSummaryPageEnvelope`,
`OrderClosureEnvelope`, `ClosureTaskReviewPageEnvelope`,
`OrderClosureHistoryPageEnvelope`, `CloseOrderEnvelope`, `ErrorEnvelope`. Public
synthetic actor role/displayName are allowed projections; account/actor IDs,
requests, keys, Cookie/token/header/trace/SQL/private-storage data and images/base64
are not captured. Existing recursive privacy/credential scans remain mandatory.
Original ordinary CLOSED GET/list use the old order artifact and OrderEnvelope/
OrderPageEnvelope; original accept success/replay keep their OPEN acceptance schema.

Static checks cannot prove streamed JSON16KiB enforcement, original-session role
freshness, same-actor ledger priority, exact whole-order/source proof, typed
reciprocal FKs/manifest, new-owned-only root/graph/TMS membership seals, legacy SQL
upgrade compatibility, PostgreSQL NOWAIT/RR behavior or atomic CLOSED revision.
They also cannot prove headers/status/replay witnesses or real product acceptance.
These require IT/E2E and source/privacy evidence. Only the SAME frozen POST's
E2101/02/03/07/08/09/10 after successful-ledger MISS may settle a prior unknown;
GET/common/key/in-progress/protected-relation errors do not prove rollback.

```sh
# Static only, explicitly not actual HTTP acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_closure_contract.py --static-only
# After isolated real HTTPS acceptance:
/tmp/xk-contract-env/bin/python tests/contracts/validate_closure_contract.py \
  --responses 'test-results/**/closure-contract-responses.json'
```

The default response glob is mandatory without `--static-only`; missing, empty or
malformed evidence fails. `--static-only` and `--responses` are mutually exclusive.
No new dependency, runtime-policy switch or artifact-scanner exception is added.
