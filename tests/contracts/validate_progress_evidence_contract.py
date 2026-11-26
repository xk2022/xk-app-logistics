"""Validate the frozen M4-02 progress/private evidence contract and sanitized HTTP evidence without network access."""
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
SPEC_PATH = ROOT / "docs/06_api/tms-progress-evidence-openapi.yaml"
SCHEMA_URI = "urn:xk:tms:progress-evidence:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/progress-evidence-contract-responses.json"
M401_SPEC_PATH = ROOT / "docs/06_api/tms-execution-openapi.yaml"
M401_SPEC_SHA256 = "b260dd0a814f11d1dc28b874f79f4280d4edce2b9bc5ac75ca0d4f3f7bbc51d4"
COPIED_SCHEMAS = {"Uuid", "Version", "Timestamp", "CompanyTimestamp", "ExecutionPhase", "ExecutionView", "IdempotencyReplayed"}
SCHEMAS = COPIED_SCHEMAS | {
    "CanonicalUuid", "ProgressTargetPhase", "EvidenceIds", "ProgressCommand", "ProgressResult",
    "Evidence", "EvidencePage", "PolicyMode", "EvidenceRequirements", "ProgressPolicyView",
    "ExecutionVersionHeader", "BinaryPng", "ErrorData", "ErrorEnvelope", "ProgressPolicyEnvelope",
    "ProgressEnvelope", "EvidenceEnvelope", "EvidencePageEnvelope",
}
RESPONSE_SCHEMAS = {"ProgressPolicyEnvelope", "ProgressEnvelope", "EvidenceEnvelope", "EvidencePageEnvelope", "ErrorEnvelope"}
BASE = "/tms/my-tasks/{assignmentId}"
OPERATIONS = {
    (BASE + "/progress-policy", "get"): ("getMyProgressPolicy", "ProgressPolicyEnvelope"),
    (BASE + "/progress", "post"): ("reportMyTaskProgress", "ProgressEnvelope"),
    (BASE + "/evidence", "post"): ("uploadMyTaskEvidence", "EvidenceEnvelope"),
    (BASE + "/evidence", "get"): ("listMyTaskEvidence", "EvidencePageEnvelope"),
    (BASE + "/evidence/{evidenceId}/content", "get"): ("getMyTaskEvidenceContent", None),
}
BUSINESS_ERRORS = {
    "E1801": "VERSION_CONFLICT", "E1802": "INVALID_STATE", "E1803": "POLICY_NOT_CONFIRMED",
    "E1804": "IDEMPOTENCY_KEY_REUSED", "E1805": "IDEMPOTENCY_IN_PROGRESS", "E1806": "PROTECTED_RELATION",
    "E1807": "EVIDENCE_REQUIRED", "E1808": "EVIDENCE_INVALID",
}
ERROR_STATUSES = {"400", "401", "403", "404", "405", "409", "413", "415", "500"}
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


@CHECKER.checks("execution-version-header")
def valid_version_header(value):
    if not isinstance(value, str):
        return True
    return bool(re.fullmatch(r"0|[1-9][0-9]{0,15}", value)) and int(value) <= 9007199254740991


def canonical_uuid_uniqueness(validator, enabled, value, schema):
    if enabled and isinstance(value, list) and all(isinstance(item, str) for item in value):
        normalized = [item.lower() for item in value]
        if len(normalized) != len(set(normalized)):
            yield jsonschema.ValidationError("UUID set contains a duplicate canonical identity")


ExactValidator = validators.extend(
    jsonschema.Draft202012Validator,
    validators={"x-uniqueCanonicalUuids": canonical_uuid_uniqueness},
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
                    fail("Progress/evidence specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Progress/evidence specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Progress/evidence specification must not redefine an in-memory resource identity")
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


def private_header(headers):
    return headers.get("Cache-Control", {}).get("required") is True and headers["Cache-Control"].get("schema") == {"const": "no-store, private"}


def load_contract():
    verify_source_hashes()
    try:
        original_bytes = M401_SPEC_PATH.read_bytes()
        if hashlib.sha256(original_bytes).hexdigest() != M401_SPEC_SHA256:
            fail("Frozen M4-01 execution OpenAPI source changed")
        original = yaml.safe_load(original_bytes.decode("utf-8"))
        spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError):
        fail("Cannot read the independent progress/evidence specification")
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("Expected independent progress/evidence OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas = spec["components"]["schemas"]
        operations = {(path, method): operation for path, item in spec["paths"].items()
                      for method, operation in item.items() if method in HTTP_METHODS}
        if set(schemas) != SCHEMAS or set(operations) != set(OPERATIONS):
            fail("Expected exactly five M4-02 operations and twenty-five component schemas")
        if spec.get("x-immutableM401SpecSha256") != M401_SPEC_SHA256 or any(schemas[name] != original["components"]["schemas"][name] for name in COPIED_SCHEMAS):
            fail("Copied immutable M4-01 schema or source pin differs")
        if spec["components"]["securitySchemes"] != original["components"]["securitySchemes"]:
            fail("Progress/evidence Session/CSRF scheme differs")
        if spec.get("x-testOnlyRequirements") != {"database": "xk_logistics_e2e", "minimumCount": 1, "allowedMediaTypes": ["image/png"], "maxBytes": 65536, "maxPixels": 4096, "formalPolicyApproval": False}:
            fail("Isolated test requirements differ; no formal policy may be inferred")
        for (path, method), operation in operations.items():
            identity, response = OPERATIONS[(path, method)]
            writing, binary = method == "post", response is None
            upload, listing = path == BASE + "/evidence" and writing, path == BASE + "/evidence" and not writing
            security = {"sessionCookie": []}
            if writing:
                security["csrfToken"] = []
            if operation["operationId"] != identity or operation.get("x-allowedRoles") != ["DRIVER"] or operation.get("x-deniedRoles") != ["ADMIN", "DISPATCHER"] or operation.get("security") != [security]:
                fail("Progress/evidence operation identity or role/Session/CSRF differs")
            if operation.get("x-rejectUnknownQueryParameters") is not True or operation.get("x-rejectDuplicateQueryParameters") is not True:
                fail("Progress/evidence query rejection differs")
            params = [resolve(spec, value) for value in operation["parameters"]]
            pairs = [(value["in"], value["name"]) for value in params]
            expected = {("path", "assignmentId")}
            if writing:
                expected |= {("header", "X-CSRF-TOKEN"), ("header", "Idempotency-Key")}
            if upload:
                expected.add(("header", "X-Execution-Version"))
            if listing:
                expected |= {("query", "page"), ("query", "size")}
            if binary:
                expected.add(("path", "evidenceId"))
            if len(pairs) != len(expected) or set(pairs) != expected or any(value.get("required") is not (value["in"] != "query") for value in params):
                fail("Progress/evidence exact path/header/query contract differs")
            if writing:
                media, body_schema = ("image/png", "BinaryPng") if upload else ("application/json", "ProgressCommand")
                body = operation["requestBody"]
                if body.get("required") is not True or set(body["content"]) != {media} or body["content"][media]["schema"] != {"$ref": f"#/components/schemas/{body_schema}"}:
                    fail("Progress/evidence command body declaration differs")
                replay = operation["responses"]["200"]["headers"]["Idempotency-Replayed"]
                if replay.get("required") is not True or replay["schema"] != {"$ref": "#/components/schemas/IdempotencyReplayed"} or not operation.get("x-versionRule") or not operation.get("x-replayRule"):
                    fail("Progress/evidence version or replay-header differs")
            elif "requestBody" in operation:
                fail("Progress/evidence read must not declare a request body")
            if upload and (operation.get("x-maxStreamBytes") != 1048576 or operation.get("x-rejectDuplicateHeaders") != ["Content-Type", "Content-Encoding", "X-Execution-Version", "Idempotency-Key"] or operation.get("x-rejectMediaParameters") is not True or operation.get("x-allowedContentEncodings") != ["identity"]):
                fail("Progress/evidence bounded raw upload metadata differs")
            responses = operation["responses"]
            if set(responses) != ERROR_STATUSES | {"200"}:
                fail("Progress/evidence HTTP status declarations differ")
            success = resolve(spec, responses["200"])
            media, body_schema = ("image/png", "BinaryPng") if binary else ("application/json", response)
            if set(success["content"]) != {media} or success["content"][media]["schema"] != {"$ref": f"#/components/schemas/{body_schema}"} or not private_header(success["headers"]):
                fail("Progress/evidence success media/envelope/privacy declaration differs")
            if binary:
                headers = success["headers"]
                if operation.get("x-noConditionalOrRangeServing") is not True or operation.get("x-forbiddenSuccessHeaders") != ["ETag", "Last-Modified", "Accept-Ranges", "Location", "Content-Location"] or headers["X-Content-Type-Options"] != {"required": True, "schema": {"const": "nosniff"}} or headers["Content-Disposition"] != {"required": True, "schema": {"const": 'inline; filename="evidence.png"'}} or headers["Content-Length"].get("required") is not True:
                    fail("Private binary content header/conditional contract differs")
            for status in ERROR_STATUSES:
                error = resolve(spec, responses[status])
                if error["content"]["application/json"]["schema"] != {"$ref": "#/components/schemas/ErrorEnvelope"} or not private_header(error["headers"]):
                    fail("Progress/evidence error envelope/privacy differs")
        for schema in schemas.values():
            jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError, TypeError, AttributeError, jsonschema.SchemaError):
        fail("Progress/evidence schema metadata or operation structure is invalid")
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
    uid = lambda number: f"a0000000-0000-4000-8000-{number:012x}"
    stamp = "2026-11-26T10:00:00.123456+08:00"
    view = lambda phase, version=1: {"phase": phase, "version": 0 if phase == "WAITING" else version, "departedAt": None if phase == "WAITING" else stamp}
    requirements = {"minimumCount": 1, "allowedMediaTypes": ["image/png"], "maxBytes": 65536, "maxPixels": 4096}
    policy = {"assignmentId": uid(1), "taskId": uid(2), "execution": view("DEPARTED"), "mode": "SYNTHETIC_TEST_ONLY", "nextPhase": "IN_PROGRESS", "requirements": requirements, "blocker": None}
    evidence = {"id": uid(3), "assignmentId": uid(1), "taskId": uid(2), "phase": "DEPARTED", "executionVersion": 1, "mediaType": "image/png", "byteSize": 67, "sha256": "a" * 64, "uploadedAt": stamp, "status": "VERIFIED"}
    page = {"content": [evidence], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
    command = {"expectedVersion": 1, "targetPhase": "IN_PROGRESS", "evidenceIds": [uid(3)]}
    result = {"assignmentId": uid(1), "taskId": uid(2), "execution": view("IN_PROGRESS", 2)}
    envelope = lambda data: {"code": "0000", "message": "成功", "data": data, "timestamp": stamp}
    error = lambda code, data: {"code": code, "message": "合成拒絕", "data": data, "timestamp": stamp}
    positive = [
        ("ProgressPolicyEnvelope", envelope(policy)), ("ProgressEnvelope", envelope(result)),
        ("ProgressEnvelope", envelope({**result, "execution": view("AWAITING_COMPLETION", 3)})),
        ("EvidenceEnvelope", envelope(evidence)), ("EvidencePageEnvelope", envelope(page)),
        ("EvidencePageEnvelope", envelope({**page, "content": [], "page": 1})),
        ("ProgressCommand", command), ("ProgressCommand", {**command, "expectedVersion": Decimal('1.0')}),
        ("ProgressCommand", {**command, "expectedVersion": Decimal('1e0'), "evidenceIds": [uid(4).upper(), uid(3)]}),
        ("ProgressCommand", {**command, "expectedVersion": 9007199254740991, "evidenceIds": []}),
        ("EvidenceIds", [uid(index) for index in range(32)]),
        ("Evidence", {**evidence, "byteSize": 9007199254740991, "executionVersion": Decimal('2.0')}),
        ("ExecutionVersionHeader", "0"), ("ExecutionVersionHeader", "1"), ("ExecutionVersionHeader", "9007199254740991"),
        ("IdempotencyReplayed", "true"), ("IdempotencyReplayed", "false"),
        ("ErrorEnvelope", error("E0400", {"reason": "VALIDATION_ERROR", "fields": {"photo": "請提供有效照片"}})),
        ("ErrorEnvelope", error("E0403", {"reason": "CSRF_INVALID"})),
        ("ErrorEnvelope", error("E0403", {"reason": "FORBIDDEN"})),
    ]
    for phase in ("WAITING", "DEPARTED", "IN_PROGRESS", "AWAITING_COMPLETION", "COMPLETED"):
        current = view(phase, 2)
        positive += [
            ("ExecutionView", current),
            ("Evidence", {**evidence, "phase": phase}),  # Read enum reservation, not proof a WAITING/future upload is permitted.
            ("ProgressPolicyView", {**policy, "execution": current, "mode": "UNCONFIRMED", "nextPhase": None, "requirements": None, "blocker": "POLICY_NOT_CONFIRMED"}),
            ("ProgressPolicyView", {**policy, "execution": current, "nextPhase": None, "requirements": None, "blocker": "POLICY_NOT_CONFIRMED"}),
        ]
        if phase in ("WAITING", "AWAITING_COMPLETION", "COMPLETED"):
            positive.append(("ProgressPolicyView", {**policy, "execution": current, "nextPhase": None, "requirements": None, "blocker": "UNSUPPORTED_PHASE"}))
    positive.append(("ProgressPolicyView", {**policy, "execution": view("IN_PROGRESS", 2), "nextPhase": "AWAITING_COMPLETION"}))
    for code in ("E0401", "E0403", "E0404", "E0405", "E0413", "E0415", "E0500"):
        positive.append(("ErrorEnvelope", error(code, None)))
    for code, reason in BUSINESS_ERRORS.items():
        positive.append(("ErrorEnvelope", error(code, {"reason": reason, **({"actualVersion": Decimal('1.0')} if code == "E1801" else {})})))
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
    public = [("ProgressPolicyView", policy), ("ProgressResult", result), ("Evidence", evidence), ("EvidencePage", page), ("ProgressCommand", command), ("ExecutionView", view("DEPARTED")), ("EvidenceRequirements", requirements)]
    for schema, value in public:
        for field in value:
            changed(schema, value, [field], remove=True)
        negative += [(schema, None), (schema, []), (schema, {**value, "unknown": None})]
    for field in ("actorId", "accountId", "driverId", "resourceId", "orderId", "customerId", "storagePath", "storageKey", "objectId", "provider", "url", "uploadUrl", "contentUrl", "filename", "originalFilename", "bytes", "blob", "base64", "photo", "events", "history"):
        changed("Evidence", evidence, [field], "forbidden")
    for field in ("actorId", "evidenceIds", "generation", "assignmentVersion", "photos", "history", "canComplete"):
        changed("ProgressResult", result, [field], "forbidden")
    for field in ("driverId", "actorId", "phase", "timestamp", "requirementsMet", "policyMode", "taskId", "assignmentVersion", "expectedGeneration"):
        changed("ProgressCommand", command, [field], "forbidden")
    for schema, value in [("ProgressPolicyEnvelope", envelope(policy)), ("ProgressEnvelope", envelope(result)), ("EvidenceEnvelope", envelope(evidence)), ("EvidencePageEnvelope", envelope(page))]:
        for field in value:
            changed(schema, value, [field], remove=True)
        for field, invalid in [("code", "OK"), ("data", None), ("traceId", "forbidden"), ("timestamp", "2026-11-26T02:00:00Z"), ("timestamp", "2026-02-30T10:00:00+08:00")]:
            changed(schema, value, [field], invalid)
    for value in [True, "1", -1, Decimal('0.1'), Decimal('9007199254740991.1'), 9007199254740992]:
        changed("ProgressCommand", command, ["expectedVersion"], value)
    for value in [None, "WAITING", "DEPARTED", "COMPLETED", "CANCELLED"]:
        changed("ProgressCommand", command, ["targetPhase"], value)
    for value in [[uid(3), uid(3)], [uid(3), uid(3).upper()], [uid(index) for index in range(33)], [None], ["not-uuid"], None, uid(3)]:
        changed("ProgressCommand", command, ["evidenceIds"], value)
    for value in ["", "01", "+1", "-1", "1.0", "1e0", " 1", "1 ", "1\n", "1,1", "9007199254740992", "10000000000000000", 1, True, None, "１"]:
        negative.append(("ExecutionVersionHeader", value))
    for field in ("version", "departedAt"):
        changed("ExecutionView", view("WAITING"), [field], 1 if field == "version" else stamp)
    for value in [0, -1, True, Decimal('1.1'), 9007199254740992]:
        changed("ExecutionView", view("DEPARTED"), ["version"], value)
    changed("ExecutionView", view("DEPARTED"), ["departedAt"], None)
    for field, value in [("phase", "DEPARTED"), ("phase", "COMPLETED"), ("version", 1), ("departedAt", None)]:
        changed("ProgressResult", result, ["execution", field], value)
    for field in ("id", "assignmentId", "taskId"):
        changed("Evidence", evidence, [field], uid(3).upper())
        changed("Evidence", evidence, [field], "not-uuid")
    for field in ("executionVersion", "byteSize"):
        for value in [0, -1, True, "1", Decimal('1.1'), Decimal('9007199254740991.1'), 9007199254740992]:
            changed("Evidence", evidence, [field], value)
    for field, value in [("mediaType", "image/jpeg"), ("mediaType", "image/png; charset=utf-8"), ("mediaType", None), ("phase", "UPLOADED"), ("status", "PENDING"), ("status", "FAILED"), ("sha256", "A"*64), ("sha256", "a"*63), ("sha256", "a"*64+"\n"), ("sha256", 123), ("uploadedAt", "2026-02-30T10:00:00+08:00")]:
        changed("Evidence", evidence, [field], value)
    for field, value in [("page", -1), ("page", 100001), ("page", True), ("size", 0), ("size", 101), ("totalElements", Decimal('1.1')), ("totalPages", 9007199254740992), ("content", [evidence]*101)]:
        changed("EvidencePage", page, [field], value)
    blocked = {**policy, "mode": "UNCONFIRMED", "nextPhase": None, "requirements": None, "blocker": "POLICY_NOT_CONFIRMED"}
    for field, value in [("mode", "APPROVED"), ("nextPhase", None), ("nextPhase", "AWAITING_COMPLETION"), ("requirements", None), ("requirements", {}), ("requirements", []), ("blocker", "UNKNOWN"), ("blocker", "POLICY_NOT_CONFIRMED"), ("blocker", "UNSUPPORTED_PHASE")]:
        changed("ProgressPolicyView", policy, [field], value)
    for field, value in [("blocker", None), ("blocker", "UNSUPPORTED_PHASE"), ("nextPhase", "IN_PROGRESS"), ("requirements", requirements)]:
        changed("ProgressPolicyView", blocked, [field], value)
    changed("ProgressPolicyView", policy, ["execution"], view("WAITING"))
    changed("ProgressPolicyView", policy, ["execution"], view("AWAITING_COMPLETION", 3))
    changed("ProgressPolicyView", policy, ["execution"], view("COMPLETED", 4))
    changed("ProgressPolicyView", {**policy, "execution": view("IN_PROGRESS", 2), "nextPhase": "AWAITING_COMPLETION"}, ["nextPhase"], "IN_PROGRESS")
    for field, value in [("minimumCount", 0), ("minimumCount", 2), ("minimumCount", True), ("allowedMediaTypes", []), ("allowedMediaTypes", ["image/jpeg"]), ("maxBytes", 0), ("maxBytes", 65537), ("maxPixels", 4097)]:
        changed("EvidenceRequirements", requirements, [field], value)
    for value in [True, "TRUE", "false\n"]:
        negative.append(("IdempotencyReplayed", value))
    validation = error("E0400", {"reason": "VALIDATION_ERROR", "fields": {"photo": "合成"}})
    for field, value in [("fields", {}), ("fields", {"photo": 7}), ("reason", "EVIDENCE_INVALID"), ("storagePath", "forbidden"), ("actualGeneration", 1)]:
        changed("ErrorEnvelope", validation, ["data", field], value)
    for code in ("E0401", "E0404", "E0405", "E0413", "E0415", "E0500"):
        negative.append(("ErrorEnvelope", error(code, {"actualVersion": 1})))
    for data in [{}, {"reason": "UNKNOWN"}, {"reason": "FORBIDDEN", "fields": {"photo": "合成"}}, {"reason": "CSRF_INVALID", "actualVersion": 1}]:
        negative.append(("ErrorEnvelope", error("E0403", data)))
    for code in ("E1601", "E1701", "E1809", "OK"):
        negative.append(("ErrorEnvelope", error(code, None)))
    for code, reason in BUSINESS_ERRORS.items():
        value = error(code, {"reason": reason, **({"actualVersion": 1} if code == "E1801" else {})})
        changed("ErrorEnvelope", value, ["data"], None)
        changed("ErrorEnvelope", value, ["data", "reason"], "OTHER_REASON")
        changed("ErrorEnvelope", value, ["data", "storagePath"], "forbidden")
        if code == "E1801":
            changed("ErrorEnvelope", value, ["data", "actualVersion"], remove=True)
            for invalid in [True, -1, Decimal('1.1'), Decimal('9007199254740991.1'), 9007199254740992]:
                changed("ErrorEnvelope", value, ["data", "actualVersion"], invalid)
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
        fail('Progress/evidence reference rejection self-check failed')
    print(f'Progress/evidence static contract: 5 operations, 25 schemas, 5 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed')


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
        fail("No synthetic progress/evidence HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic progress/evidence response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic progress/evidence response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"Progress/evidence response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual progress/evidence HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual progress/evidence HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
