"""Validate the incremental TOM acceptance contract and sanitized HTTP evidence without network access."""
from __future__ import annotations

import argparse
import datetime as dt
import copy
import hashlib
from decimal import Decimal
import glob
import json
from pathlib import Path
import re

import jsonschema
from jsonschema import validators
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource
from referencing.jsonschema import DRAFT202012
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC_PATH = ROOT / "docs/06_api/tom-acceptance-openapi.yaml"
ORIGINAL_PATH = ROOT / "docs/06_api/openapi.yaml"
SCHEMA_URI = "https://contracts.invalid/xk/tom-acceptance-openapi.yaml"
ORIGINAL_URI = "https://contracts.invalid/xk/openapi.yaml"
DEFAULT_RESPONSES = "test-results/**/acceptance-contract-responses.json"
SCHEMAS = {"AcceptOrderRequest", "AcceptedOrderEnvelope", "ErrorEnvelope", "IdempotencyReplayed"}
RESPONSE_SCHEMAS = {"AcceptedOrderEnvelope", "ErrorEnvelope"}
OPERATIONS = {("/orders/{orderId}/accept", "post"): "acceptOrder"}
ALLOWED_ORIGINAL_REFS = {
    "openapi.yaml#/components/schemas/Uuid",
    "openapi.yaml#/components/schemas/OrderEnvelope",
    "openapi.yaml#/components/schemas/ErrorEnvelope",
}
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml",
    "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json",
    "docs/05_database/first-slice-fields.csv",
}
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
CHECKER = jsonschema.FormatChecker()


@CHECKER.checks("date")
def valid_date(value):
    if not isinstance(value, str):
        return True
    try:
        return bool(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value)) and bool(dt.date.fromisoformat(value))
    except ValueError:
        return False


@CHECKER.checks("date-time")
def valid_date_time(value):
    if not isinstance(value, str):
        return True
    match = re.fullmatch(
        r"([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
        r"(?:\.[0-9]+)?(?:[Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])", value,
    )
    return bool(match and valid_date(match[1]))


def exact_integer(checker, value):
    # JSON Schema integers include mathematical 1.0; never round 9007199254740991.1 through float.
    if isinstance(value, Decimal):
        return value.is_finite() and value == value.to_integral_value()
    return jsonschema.Draft202012Validator.TYPE_CHECKER.is_type(value, "integer")


ExactValidator = validators.extend(
    jsonschema.Draft202012Validator,
    type_checker=jsonschema.Draft202012Validator.TYPE_CHECKER.redefine("integer", exact_integer),
)


def fail(message):
    raise SystemExit(message)


def deny_remote(uri):
    # Explicitly no urllib, requests, filesystem retrieval or network fallback.
    raise NoSuchResource(ref=uri)


def local_references_only(spec, original=False):
    def visit(value):
        if isinstance(value, dict):
            for keyword in ("$ref", "$dynamicRef"):
                if keyword not in value:
                    continue
                reference = value[keyword]
                if not original and keyword == "$ref" and isinstance(reference, str) and reference in ALLOWED_ORIGINAL_REFS:
                    continue
                if not isinstance(reference, str) or not reference.startswith("#/"):
                    fail("Acceptance specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Acceptance specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Acceptance specification must not override an in-memory resource identity")
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(spec)


def verify_source_hashes():
    # Reuse the unchanged original checksum manifest rather than duplicate its pins.
    try:
        entries = [line.split() for line in SOURCE_MANIFEST.read_text(encoding="utf-8").splitlines()]
    except (OSError, UnicodeError):
        fail("Cannot read the preserved contract checksum manifest")
    if len(entries) != 4 or any(len(entry) != 2 or not re.fullmatch(r"[0-9a-f]{64}", entry[0]) for entry in entries):
        fail("Preserved contract checksum manifest must contain four SHA-256 pins")
    if {entry[1] for entry in entries} != SOURCE_PATHS:
        fail("Preserved contract checksum manifest contains unexpected sources")
    for expected, path in entries:
        try:
            actual = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        except OSError:
            fail("Cannot read a preserved contract source")
        if actual != expected:
            fail(f"Preserved contract source changed: {path}")


def load_contract():
    verify_source_hashes()
    try:
        spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
        original = yaml.safe_load(ORIGINAL_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError):
        fail("Cannot read the independent acceptance specification or preserved source")
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("Expected independent acceptance OpenAPI 3.1.0")
    local_references_only(spec)
    local_references_only(original, original=True)
    try:
        schemas = spec["components"]["schemas"]
        operations = {
            (path, method): operation
            for path, path_item in spec["paths"].items()
            for method, operation in path_item.items() if method in HTTP_METHODS
        }
        if set(schemas) != SCHEMAS or set(operations) != set(OPERATIONS):
            fail("Expected exactly one acceptance POST and four incremental schemas")
        operation = operations[("/orders/{orderId}/accept", "post")]
        if operation["operationId"] != "acceptOrder" or operation.get("x-allowedRoles") != ["ADMIN", "DISPATCHER"] or operation.get("x-deniedRoles") != ["DRIVER"]:
            fail("Acceptance operation identity or role contract differs")
        if operation.get("security") != [{"sessionAuth": [], "csrfToken": []}]:
            fail("Acceptance Session/CSRF contract differs")
        parameters = operation["parameters"]
        if {(parameter["in"], parameter["name"]) for parameter in parameters} != {
            ("path", "orderId"), ("header", "X-CSRF-TOKEN"), ("header", "Idempotency-Key"),
        } or len(parameters) != 3 or any(parameter.get("required") is not True for parameter in parameters):
            fail("Acceptance path/header/query contract differs")
        if operation.get("x-rejectUnknownQueryParameters") is not True or operation.get("x-rejectDuplicateQueryParameters") is not True:
            fail("Acceptance query rejection contract differs")
        if set(operation["requestBody"]["content"]) != {"application/json"} or operation["requestBody"].get("required") is not True:
            fail("Acceptance JSON body declaration differs")
        responses = operation["responses"]
        if set(responses) != {"200", "400", "401", "403", "404", "409", "500"}:
            fail("Acceptance HTTP status contract differs")
        replay_header = responses["200"]["headers"]["Idempotency-Replayed"]
        if replay_header.get("required") is not True or replay_header["schema"] != {"$ref": "#/components/schemas/IdempotencyReplayed"}:
            fail("Acceptance replay header declaration differs")
        for status, response in responses.items():
            expected = "AcceptedOrderEnvelope" if status == "200" else "ErrorEnvelope"
            if response["content"]["application/json"]["schema"] != {"$ref": f"#/components/schemas/{expected}"}:
                fail("Acceptance response schema binding differs")
        for schema in list(schemas.values()) + list(original["components"]["schemas"].values()):
            jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError, TypeError, AttributeError, jsonschema.SchemaError):
        fail("Acceptance schema metadata or operation structure is invalid")
    registry = Registry(retrieve=deny_remote).with_resources([
        (SCHEMA_URI, Resource.from_contents(spec, default_specification=DRAFT202012)),
        (ORIGINAL_URI, Resource.from_contents(original, default_specification=DRAFT202012)),
    ])
    return {
        name: ExactValidator({"$ref": f"{SCHEMA_URI}#/components/schemas/{name}"},
                             registry=registry, format_checker=CHECKER)
        for name in schemas
    }


def report_error(label, schema, error):
    pointer = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.path)
    # Never print error.message, instance values, request bodies, cookies or headers.
    fail(f"{label}: {schema} failed {error.validator} at {pointer}")


def static_fixtures():
    examples = json.loads((ROOT / "docs/06_api/order-draft-contract-examples.json").read_text(), parse_float=Decimal)
    originals = [record["value"] for record in examples["schemaPositive"] if record["schema"] == "OrderEnvelope"]
    accepted = []
    for original in originals:
        result = copy.deepcopy(original)
        result["data"].update(lifecycleStatus="OPEN", acceptanceStatus="ACCEPTED", version=1)
        accepted.append(result)
    result = accepted[0]
    error = lambda code, data=None, issues=None: {
        "code": code, "message": "合成拒絕訊息", "data": data, "traceId": "synthetic-acceptance-trace", "fieldErrors": issues or [],
    }
    issue = {"path": "/items/0/sequence", "fieldId": "IF02", "ruleId": "ORD-P02", "message": "請補合成序號"}
    positive = [("AcceptedOrderEnvelope", value) for value in accepted]
    positive += [
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "version": 9007199254740991}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "remark": "合成\u0000快照"}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "shipping": None}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "updatedAt": "0001-01-01T00:00:00.123456+23:59"}}),
        ("AcceptOrderRequest", {"expectedVersion": 0}),
        ("AcceptOrderRequest", {"expectedVersion": Decimal("1.0")}),
        ("AcceptOrderRequest", {"expectedVersion": Decimal("1e0")}),
        ("AcceptOrderRequest", {"expectedVersion": Decimal("-0")}),
        # Shape permits MAX; the new-operation state/version guard must reject increment with 409.
        ("AcceptOrderRequest", {"expectedVersion": 9007199254740991}),
        ("ErrorEnvelope", error("VALIDATION_ERROR", issues=[issue])),
        ("ErrorEnvelope", error("INVALID_CUSTOMER", issues=[{**issue, "path": "/customerId", "fieldId": "OF04", "ruleId": "ORD-P01"}])),
        ("ErrorEnvelope", error("VERSION_CONFLICT", {"actualVersion": 12}, [{**issue, "path": "/expectedVersion", "fieldId": "AF01", "ruleId": "VERSION"}])),
        ("IdempotencyReplayed", "false"), ("IdempotencyReplayed", "true"),
    ]
    codes = ["UNAUTHENTICATED", "FORBIDDEN", "CSRF_INVALID", "ORDER_NOT_FOUND", "ORDER_NOT_EDITABLE",
             "PROTECTED_RELATION", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS", "INTERNAL_ERROR"]
    positive += [("ErrorEnvelope", error(code)) for code in codes]
    negative = [
        ("AcceptOrderRequest", {}),
        ("AcceptOrderRequest", {"expectedVersion": None}),
        ("AcceptOrderRequest", {"expectedVersion": True}),
        ("AcceptOrderRequest", {"expectedVersion": "1"}),
        ("AcceptOrderRequest", {"expectedVersion": Decimal("0.1")}),
        ("AcceptOrderRequest", {"expectedVersion": Decimal("9007199254740991.1")}),
        ("AcceptOrderRequest", {"expectedVersion": -1}),
        ("AcceptOrderRequest", {"expectedVersion": 9007199254740992}),
        ("AcceptOrderRequest", {"expectedVersion": 1, "actorId": "00000000-0000-4000-8000-000000000001"}),
        ("AcceptOrderRequest", {"expectedVersion": 1, "acceptedAt": "2026-11-26T00:00:00Z"}),
        ("AcceptOrderRequest", {"expectedVersion": 1, "draft": {}}),
        ("AcceptedOrderEnvelope", {**result, "code": "0000"}),
        ("AcceptedOrderEnvelope", {**result, "timestamp": "2026-11-26T08:00:00+08:00"}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "acceptanceStatus": "DRAFT"}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "lifecycleStatus": "CLOSED"}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "version": 0}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "version": Decimal("9007199254740991.1")}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "acceptedAt": "2026-11-26T00:00:00Z"}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "actorId": "00000000-0000-4000-8000-000000000001"}}),
        ("AcceptedOrderEnvelope", {**result, "event": {}}),
        ("AcceptedOrderEnvelope", {key: value for key, value in result.items() if key != "traceId"}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "updatedAt": "2026-02-30T00:00:00Z"}}),
        ("AcceptedOrderEnvelope", {**result, "data": {**result["data"], "updatedAt": "2026-11-26T00:00:00.1234567Z"}}),
        ("ErrorEnvelope", error("ACCEPTANCE_INCOMPLETE")),
        ("ErrorEnvelope", error("E0400")),
        ("ErrorEnvelope", {**error("VALIDATION_ERROR"), "timestamp": "2026-11-26T08:00:00+08:00"}),
        ("ErrorEnvelope", error("VERSION_CONFLICT", {"version": 12})),
        ("ErrorEnvelope", error("VERSION_CONFLICT", {"actualVersion": Decimal("9007199254740991.1")})),
        ("ErrorEnvelope", {key: value for key, value in error("FORBIDDEN").items() if key != "fieldErrors"}),
        ("ErrorEnvelope", error("VALIDATION_ERROR", issues=[{**issue, "request": "unexpected"}])),
        ("IdempotencyReplayed", False), ("IdempotencyReplayed", "TRUE"), ("IdempotencyReplayed", " true "),
    ]
    return positive, negative


def validate_static(schema_validators):
    positive, negative = static_fixtures()
    for index, (schema, value) in enumerate(positive):
        issue = next(schema_validators[schema].iter_errors(value), None)
        if issue:
            report_error(f"Static positive[{index}]", schema, issue)
    for index, (schema, value) in enumerate(negative):
        if schema_validators[schema].is_valid(value):
            fail(f"Static negative[{index}]: {schema} unexpectedly accepted")
    forbidden = [
        {"$ref": "https://invalid.example/schema"}, {"$ref": "file:///tmp/schema"},
        {"$ref": "./openapi.yaml#/components/schemas/OrderEnvelope"},
        {"$ref": "openapi.yaml#/components/schemas/OrderPageEnvelope"},
        {"$dynamicRef": "openapi.yaml#/components/schemas/OrderEnvelope"},
        {"$id": ORIGINAL_URI}, {"$ref": "#/components/schemas/Missing"},
    ]
    for document in forbidden:
        try:
            local_references_only(document)
        except SystemExit:
            continue
        fail("Forbidden-reference rejection self-check failed")
    print(f"Acceptance static contract: 1 operation, 4 incremental schemas, 4 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed")


def reject_duplicates(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON property")
        value[key] = item
    return value


def reject_constant(value):
    raise ValueError("Non-JSON numeric constant")


def require_unicode(value):
    if isinstance(value, str):
        # Also reject escaped, unpaired UTF-16 surrogates without printing them.
        value.encode("utf-8", errors="strict")
    elif isinstance(value, dict):
        for key, item in value.items():
            require_unicode(key)
            require_unicode(item)
    elif isinstance(value, list):
        for item in value:
            require_unicode(item)


def validate_responses(patterns, schema_validators):
    files = sorted({Path(path) for pattern in patterns for path in glob.glob(str(ROOT / pattern), recursive=True)})
    if not files:
        fail("No synthetic TOM acceptance HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError):
            fail("Synthetic TOM acceptance response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic TOM acceptance response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record["schema"], str) or record["schema"] not in RESPONSE_SCHEMAS:
                fail(f"Acceptance response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record["schema"]].iter_errors(record["value"]), None)
            if issue:
                report_error(f"{path.name}[{index}]", record["schema"], issue)
            count += 1
    print(f"Actual acceptance HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--responses", nargs="+", help="Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence")
    group.add_argument("--static-only", action="store_true", help="Only validate static schemas/fixtures; explicitly not HTTP acceptance")
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print("Actual acceptance HTTP responses: not run (--static-only)")
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == "__main__":
    main()
