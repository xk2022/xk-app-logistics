"""Validate the frozen M4-01 driver execution contract and sanitized HTTP evidence without network access."""
from __future__ import annotations

import argparse
import copy
import datetime as dt
from decimal import Decimal, DecimalException
import glob
import hashlib
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
SPEC_PATH = ROOT / "docs/06_api/tms-execution-openapi.yaml"
SCHEMA_URI = "urn:xk:tms:execution:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/execution-contract-responses.json"
SCHEMAS = {
    "Uuid", "Version", "Timestamp", "CompanyTimestamp", "SourceDateTime", "NullableSourceDateTime",
    "NullableText", "NullableAddress", "NullableDecimal", "PolicyMode", "ItemSnapshot", "SiteContact",
    "ExecutionPhase", "ExecutionView", "MyTaskExecutionDetail", "DepartRequest", "DepartureResult",
    "IdempotencyReplayed", "ErrorData", "ErrorEnvelope", "MyTaskExecutionEnvelope", "DepartMyTaskEnvelope",
}
RESPONSE_SCHEMAS = {"MyTaskExecutionEnvelope", "DepartMyTaskEnvelope", "ErrorEnvelope"}
OPERATIONS = {
    ("/tms/my-tasks/{assignmentId}", "get"): ("getMyTaskExecution", "MyTaskExecutionEnvelope", False),
    ("/tms/my-tasks/{assignmentId}/depart", "post"): ("departMyTask", "DepartMyTaskEnvelope", True),
}
BUSINESS_ERRORS = {
    "E1701": "VERSION_CONFLICT", "E1702": "INVALID_STATE", "E1703": "POLICY_NOT_CONFIRMED",
    "E1704": "IDEMPOTENCY_KEY_REUSED", "E1705": "IDEMPOTENCY_IN_PROGRESS", "E1706": "PROTECTED_RELATION",
}
ERROR_STATUSES = {"400", "401", "403", "404", "405", "409", "415", "500"}
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
CHECKER = jsonschema.FormatChecker()


@CHECKER.checks("date-time")
def valid_date_time(value):
    if not isinstance(value, str):
        return True
    match = re.fullmatch(
        r"([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
        r"(?:\.[0-9]+)?(?:[Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])", value,
    )
    try:
        return bool(match and dt.date.fromisoformat(match[1]))
    except ValueError:
        return False


def exact_integer(checker, value):
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
    raise NoSuchResource(ref=uri)


def local_references_only(spec):
    def visit(value):
        if isinstance(value, dict):
            for keyword in ("$ref", "$dynamicRef"):
                if keyword not in value:
                    continue
                reference = value[keyword]
                if not isinstance(reference, str) or not reference.startswith("#/"):
                    fail("Execution specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Execution specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Execution specification must not redefine an in-memory resource identity")
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(spec)


def verify_source_hashes():
    # Reuse the unchanged original checksum manifest; do not duplicate its pins or weaken checks.
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


def resolve(spec, value):
    if not isinstance(value, dict) or "$ref" not in value:
        return value
    target = spec
    for part in value["$ref"][2:].split("/"):
        target = target[part.replace("~1", "/").replace("~0", "~")]
    return target


def load_contract():
    verify_source_hashes()
    try:
        spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError):
        fail("Cannot read the independent execution specification")
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("Expected independent execution OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas = spec["components"]["schemas"]
        operations = {(path, method): operation for path, item in spec["paths"].items()
                      for method, operation in item.items() if method in HTTP_METHODS}
        if set(schemas) != SCHEMAS or set(operations) != set(OPERATIONS):
            fail("Expected exactly two M4-01 operations and twenty-two component schemas")
        for key, operation in operations.items():
            identity, response, writing = OPERATIONS[key]
            security = {"sessionCookie": []}
            if writing:
                security["csrfToken"] = []
            if operation["operationId"] != identity or operation.get("x-allowedRoles") != ["DRIVER"] or operation.get("x-deniedRoles") != ["ADMIN", "DISPATCHER"] or operation.get("security") != [security]:
                fail("Execution operation identity or role/Session/CSRF contract differs")
            if operation.get("x-rejectUnknownQueryParameters") is not True or operation.get("x-rejectDuplicateQueryParameters") is not True:
                fail("Execution query rejection contract differs")
            params = [resolve(spec, value) for value in operation["parameters"]]
            pairs = [(value["in"], value["name"]) for value in params]
            expected = {("path", "assignmentId")}
            if writing:
                expected |= {("header", "X-CSRF-TOKEN"), ("header", "Idempotency-Key")}
            if len(pairs) != len(expected) or set(pairs) != expected or any(value.get("required") is not True for value in params):
                fail("Execution path/header/query contract differs")
            if writing:
                body = operation["requestBody"]
                if body.get("required") is not True or set(body["content"]) != {"application/json"} or body["content"]["application/json"]["schema"] != {"$ref": "#/components/schemas/DepartRequest"}:
                    fail("Execution departure JSON body declaration differs")
                replay = operation["responses"]["200"]["headers"]["Idempotency-Replayed"]
                if replay.get("required") is not True or replay["schema"] != {"$ref": "#/components/schemas/IdempotencyReplayed"} or not operation.get("x-versionRule") or not operation.get("x-replayRule"):
                    fail("Execution version or replay-header declaration differs")
            elif "requestBody" in operation:
                fail("Execution detail read must not declare a request body")
            responses = operation["responses"]
            if set(responses) != ERROR_STATUSES | {"200"}:
                fail("Execution HTTP status declarations differ")
            if resolve(spec, responses["200"])["content"]["application/json"]["schema"] != {"$ref": f"#/components/schemas/{response}"}:
                fail("Execution success envelope declaration differs")
            for status in ERROR_STATUSES:
                if resolve(spec, responses[status])["content"]["application/json"]["schema"] != {"$ref": "#/components/schemas/ErrorEnvelope"}:
                    fail("Execution error envelope declaration differs")
        for schema in schemas.values():
            jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError, TypeError, AttributeError, jsonschema.SchemaError):
        fail("Execution schema metadata or operation structure is invalid")
    registry = Registry(retrieve=deny_remote).with_resource(
        SCHEMA_URI, Resource.from_contents(spec, default_specification=DRAFT202012),
    )
    return {name: ExactValidator({"$ref": f"{SCHEMA_URI}#/components/schemas/{name}"},
                                 registry=registry, format_checker=CHECKER) for name in schemas}


def report_error(label, schema, error):
    pointer = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.path)
    # Never print error.message, instance values, request bodies, cookies or headers.
    fail(f"{label}: {schema} failed {error.validator} at {pointer}")


def static_fixtures():
    uid = lambda number: f"00000000-0000-4000-8000-{number:012d}"
    stamp = "2026-11-26T10:00:00.123456+08:00"
    item = {"itemKind": "CARGO", "containerType": None, "containerNo": None,
            "packageQty": 7, "grossWeight": "0.0000000000000000001", "cbm": "0.00"}
    waiting = {"phase": "WAITING", "version": 0, "departedAt": None}
    departed = {"phase": "DEPARTED", "version": 1, "departedAt": stamp}
    detail = {
        "assignmentId": uid(1), "taskId": uid(2), "taskNo": "TSK-SYNTHETIC-EXECUTION", "item": item,
        "taskKind": " 合成 e\u0301 ", "pickupName": "合成起點", "pickupAddress": "合成\u0000地址",
        "deliveryName": "合成終點", "deliveryAddress": "原值地址", "siteContact": {"name": None, "phone": None},
        "plannedStartAt": "1500-01-02T00:00:00.123456+23:59", "plannedEndAt": None,
        "assignedAt": stamp, "policyMode": "SYNTHETIC_TEST_ONLY", "execution": waiting,
    }
    receipt = {"assignmentId": uid(1), "taskId": uid(2), "execution": departed}
    envelope = lambda data: {"code": "0000", "message": "成功", "data": data, "timestamp": stamp}
    error = lambda code, data: {"code": code, "message": "合成拒絕", "data": data, "timestamp": stamp}
    positive = [
        ("MyTaskExecutionEnvelope", envelope(detail)),
        ("MyTaskExecutionEnvelope", envelope({**detail, "execution": departed})),
        ("MyTaskExecutionEnvelope", envelope({**detail, "policyMode": "UNCONFIRMED"})),
        ("MyTaskExecutionDetail", {**detail, "siteContact": None}),
        ("MyTaskExecutionDetail", {**detail, "taskKind": "", "pickupName": " \u0000 ", "siteContact": {"name": "", "phone": None}}),
        ("MyTaskExecutionDetail", {**detail, "plannedStartAt": "0001-01-01T00:00:00Z", "plannedEndAt": "9999-12-31T23:59:59.999999-23:59"}),
        ("MyTaskExecutionDetail", {**detail, "item": {**item, "packageQty": None, "grossWeight": None, "cbm": None}}),
        ("MyTaskExecutionDetail", {**detail, "item": {**item, "itemKind": "CONTAINER", "containerType": " 合成櫃型 ", "containerNo": "合成\u0000非實際櫃號"}}),
        ("DepartMyTaskEnvelope", envelope(receipt)),
        ("DepartRequest", {"expectedVersion": 0}),
        ("DepartRequest", {"expectedVersion": Decimal('1.0')}),
        ("DepartRequest", {"expectedVersion": 9007199254740991}),
        ("IdempotencyReplayed", "true"), ("IdempotencyReplayed", "false"),
        ("ErrorEnvelope", error('E0400', {"reason": "VALIDATION_ERROR", "fields": {"expectedVersion": "請提供執行版本"}})),
        ("ErrorEnvelope", error('E0403', {"reason": "CSRF_INVALID"})),
        ("ErrorEnvelope", error('E0403', {"reason": "FORBIDDEN"})),
    ]
    for phase in ('IN_PROGRESS', 'AWAITING_COMPLETION', 'COMPLETED'):
        positive.append(('MyTaskExecutionEnvelope', envelope({**detail, 'execution': {**departed, 'phase': phase, 'version': 2}})))
    for code in ('E0401', 'E0403', 'E0404', 'E0405', 'E0415', 'E0500'):
        positive.append(('ErrorEnvelope', error(code, None)))
    for code, reason in BUSINESS_ERRORS.items():
        positive.append(('ErrorEnvelope', error(code, {'reason': reason, **({'actualVersion': 1} if code == 'E1701' else {})})))
    negative = []
    def changed(schema, value, path, replacement=None, remove=False):
        altered = copy.deepcopy(value)
        cursor = altered
        for component in path[:-1]:
            cursor = cursor[component]
        if remove:
            del cursor[path[-1]]
        else:
            cursor[path[-1]] = replacement
        negative.append((schema, altered))
    for field, value in [('code', 'OK'), ('traceId', 'forbidden'), ('timestamp', '2026-11-26T02:00:00Z'), ('timestamp', '2026-02-30T10:00:00+08:00')]:
        changed('MyTaskExecutionEnvelope', envelope(detail), [field], value)
    changed('MyTaskExecutionEnvelope', envelope(detail), ['timestamp'], remove=True)
    changed('MyTaskExecutionEnvelope', envelope(detail), ['data'], None)
    changed('MyTaskExecutionDetail', detail, ['execution'], remove=True)
    for field in ['orderId', 'orderNo', 'customerId', 'customerName', 'driverId', 'accountId', 'resourceId', 'mainVehicleId', 'trailerId', 'plateNo', 'actorId', 'events', 'history', 'reservations', 'generation', 'assignmentVersion', 'photos', 'uploadUrl', 'storagePath', 'canDepart']:
        changed('MyTaskExecutionDetail', detail, [field], 'forbidden')
    changed('MyTaskExecutionDetail', detail, ['execution', 'actorId'], uid(9))
    changed('MyTaskExecutionDetail', detail, ['execution', 'id'], uid(9))
    changed('MyTaskExecutionDetail', detail, ['item', 'id'], uid(9))
    changed('MyTaskExecutionDetail', detail, ['siteContact', 'accountId'], uid(9))
    changed('MyTaskExecutionDetail', detail, ['siteContact', 'phone'], remove=True)
    changed('MyTaskExecutionDetail', detail, ['policyMode'], 'FORMALLY_APPROVED')
    for value in [True, '0', -1, Decimal('0.1'), Decimal('9007199254740991.1'), 9007199254740992]:
        changed('DepartRequest', {'expectedVersion': 0}, ['expectedVersion'], value)
    negative.append(('DepartRequest', {}))
    for field in ['driverId', 'actorId', 'taskId', 'assignmentId', 'expectedGeneration', 'assignmentVersion', 'departedAt', 'reason', 'evidence', 'policyMode']:
        changed('DepartRequest', {'expectedVersion': 0}, [field], 'forbidden')
    for field, value in [('version', 1), ('departedAt', stamp), ('phase', 'REVOKED')]:
        changed('ExecutionView', waiting, [field], value)
    for field, value in [('version', 0), ('version', Decimal('1.1')), ('departedAt', None), ('phase', 'CANCELLED')]:
        changed('ExecutionView', departed, [field], value)
    for field, value in [('version', 2), ('departedAt', None), ('phase', 'WAITING'), ('phase', 'IN_PROGRESS')]:
        changed('DepartureResult', receipt, ['execution', field], value)
    for field in ['executionId', 'actorId', 'events', 'task', 'source', 'driverId', 'timestamp']:
        changed('DepartureResult', receipt, [field], 'forbidden')
    changed('DepartureResult', receipt, ['assignmentId'], 'invalid-uuid')
    changed('DepartureResult', receipt, ['taskId'], remove=True)
    for value in ['2026-02-30T00:00:00Z', '0000-01-01T00:00:00Z', '2026-01-01T00:00:00.1234567Z', '2026-01-01T00:00:60Z', '2026-01-01T00:00:00+24:00', '2026-01-01T00:00:00']:
        changed('MyTaskExecutionDetail', detail, ['plannedStartAt'], value)
    for value in ['01.00', '-1', '1e2', '1\n', '0.' + '1' * 32, 1.0]:
        changed('ItemSnapshot', item, ['grossWeight'], value)
    changed('ItemSnapshot', item, ['packageQty'], 0)
    changed('ItemSnapshot', item, ['itemKind'], None)
    for value in [True, 'TRUE', 'false\n']:
        negative.append(('IdempotencyReplayed', value))
    validation = error('E0400', {'reason': 'VALIDATION_ERROR', 'fields': {'expectedVersion': '合成'}})
    changed('ErrorEnvelope', validation, ['data', 'fields'], {})
    changed('ErrorEnvelope', validation, ['data', 'fields', 'expectedVersion'], 7)
    changed('ErrorEnvelope', validation, ['data', 'actualGeneration'], 1)
    changed('ErrorEnvelope', validation, ['code'], 'E1601')
    for code in ('E0401', 'E0404', 'E0405', 'E0415', 'E0500'):
        negative.append(('ErrorEnvelope', error(code, {'actualVersion': 1})))
    negative.append(('ErrorEnvelope', error('E0403', {'reason': 'UNKNOWN_REASON'})))
    negative.append(('ErrorEnvelope', error('E0403', {'reason': 'FORBIDDEN', 'actualVersion': 1})))
    for code, reason in BUSINESS_ERRORS.items():
        value = error(code, {'reason': reason, **({'actualVersion': 1} if code == 'E1701' else {})})
        changed('ErrorEnvelope', value, ['data'], None)
        changed('ErrorEnvelope', value, ['data', 'reason'], 'OTHER_REASON')
        if code == 'E1701':
            changed('ErrorEnvelope', value, ['data', 'actualVersion'], remove=True)
    return positive, negative


def validate_static(schema_validators):
    positive, negative = static_fixtures()
    for index, (schema, value) in enumerate(positive):
        issue = next(schema_validators[schema].iter_errors(value), None)
        if issue:
            report_error(f'Static positive[{index}]', schema, issue)
    for index, (schema, value) in enumerate(negative):
        if schema_validators[schema].is_valid(value):
            fail(f'Static negative[{index}]: {schema} unexpectedly accepted')
    forbidden = [
        {'$ref': 'https://invalid.example/schema'}, {'$ref': 'file:///tmp/schema'},
        {'$ref': 'other.yaml#/components/schemas/Uuid'}, {'$ref': '//invalid.example/schema'},
        {'$ref': '#/missing'}, {'$dynamicRef': 'https://invalid.example/dynamic'},
        {'$dynamicRef': 'file:///tmp/schema'}, {'$id': 'urn:forbidden:override'},
    ]
    for value in forbidden:
        try:
            local_references_only(value)
        except SystemExit:
            continue
        fail('Execution reference rejection self-check failed')
    print(f'Execution static contract: 2 operations, 22 schemas, 4 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed')


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
        fail("No synthetic execution HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic execution response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic execution response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"Execution response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual execution HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual execution HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
