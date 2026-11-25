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

Use CPython 3.12 on Linux x86_64 (verified locally with 3.12.14). The seven test-only
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
