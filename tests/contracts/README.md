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
