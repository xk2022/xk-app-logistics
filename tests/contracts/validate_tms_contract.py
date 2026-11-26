"""Validate the independent TMS dispatch contract and sanitized HTTP evidence without network access."""
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
SPEC_PATH = ROOT / "docs/06_api/tms-dispatch-openapi.yaml"
SCHEMA_URI = "urn:xk:tms:dispatch:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/tms-contract-responses.json"
SCHEMAS = {
    "Uuid", "Version", "Timestamp", "CompanyTimestamp", "SourceDateTime", "NullableSourceDateTime",
    "NullableText", "NullableAddress", "NullableDecimal", "PolicyMode", "Blocker", "OrderType",
    "ItemSnapshot", "SiteContact", "TaskSummary", "TaskSource", "DriverSnapshot", "VehicleSnapshot",
    "ReasonInput", "Reservation", "Event", "Assignment", "Occupation", "ResourceCandidate", "MyTask",
    "TaskPage", "AssignmentPage", "ResourcePage", "MyTaskPage", "DispatchDetail", "CommandResult",
    "AssignRequest", "RevokeRequest", "ReassignRequest", "IdempotencyReplayed", "ErrorData", "ErrorEnvelope",
    "TaskPageEnvelope", "DispatchDetailEnvelope", "CommandResultEnvelope", "ResourcePageEnvelope", "MyTaskPageEnvelope",
}
RESPONSE_SCHEMAS = {
    "TaskPageEnvelope", "DispatchDetailEnvelope", "CommandResultEnvelope", "ResourcePageEnvelope",
    "MyTaskPageEnvelope", "ErrorEnvelope",
}
OPERATIONS = {
    ("/tms/tasks", "get"): ("listTmsTasks", "TaskPageEnvelope", None),
    ("/tms/tasks/{taskId}/dispatch", "get"): ("getTaskDispatch", "DispatchDetailEnvelope", None),
    ("/tms/tasks/{taskId}/dispatch", "post"): ("assignTask", "CommandResultEnvelope", "AssignRequest"),
    ("/tms/dispatches/{id}/revoke", "post"): ("revokeDispatch", "CommandResultEnvelope", "RevokeRequest"),
    ("/tms/dispatches/{id}/reassign", "post"): ("reassignDispatch", "CommandResultEnvelope", "ReassignRequest"),
    ("/tms/resources", "get"): ("listTmsResources", "ResourcePageEnvelope", None),
    ("/tms/my-tasks", "get"): ("listMyTmsTasks", "MyTaskPageEnvelope", None),
}
BUSINESS_ERRORS = {
    "E1601": "VERSION_CONFLICT", "E1602": "RESOURCE_OCCUPIED", "E1603": "INVALID_STATE",
    "E1604": "PREREQUISITE_NOT_READY", "E1605": "POLICY_NOT_CONFIRMED", "E1606": "MASTER_UNAVAILABLE",
    "E1607": "IDEMPOTENCY_KEY_REUSED", "E1608": "IDEMPOTENCY_IN_PROGRESS", "E1609": "PROTECTED_RELATION",
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
                    fail("TMS specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("TMS specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("TMS specification must not redefine an in-memory resource identity")
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
        fail("Cannot read the independent TMS specification")
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("Expected independent TMS OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas = spec["components"]["schemas"]
        operations = {(path, method): operation for path, item in spec["paths"].items()
                      for method, operation in item.items() if method in HTTP_METHODS}
        if set(schemas) != SCHEMAS or set(operations) != set(OPERATIONS):
            fail("Expected exactly seven TMS operations and forty-two component schemas")
        for key, operation in operations.items():
            identity, response, request = OPERATIONS[key]
            self_read = key == ("/tms/my-tasks", "get")
            roles = ["DRIVER"] if self_read else ["ADMIN", "DISPATCHER"]
            security = {"sessionCookie": []}
            if request:
                security["csrfToken"] = []
            if operation["operationId"] != identity or operation.get("x-allowedRoles") != roles or operation.get("security") != [security]:
                fail("TMS operation identity or role/Session/CSRF contract differs")
            if operation.get("x-rejectUnknownQueryParameters") is not True or operation.get("x-rejectDuplicateQueryParameters") is not True:
                fail("TMS query rejection contract differs")
            params = [resolve(spec, value) for value in operation["parameters"]]
            pairs = [(value["in"], value["name"]) for value in params]
            if len(pairs) != len(set(pairs)):
                fail("TMS operation repeats a parameter declaration")
            if request:
                path_name = "taskId" if "/tasks/" in key[0] else "id"
                if set(pairs) != {("path", path_name), ("header", "X-CSRF-TOKEN"), ("header", "Idempotency-Key")} or any(value.get("required") is not True for value in params):
                    fail("TMS write path/header/query contract differs")
                body = operation["requestBody"]
                if body.get("required") is not True or set(body["content"]) != {"application/json"} or body["content"]["application/json"]["schema"] != {"$ref": f"#/components/schemas/{request}"}:
                    fail("TMS write JSON body declaration differs")
                replay = operation["responses"]["200"]["headers"]["Idempotency-Replayed"]
                if replay.get("required") is not True or replay["schema"] != {"$ref": "#/components/schemas/IdempotencyReplayed"} or not operation.get("x-versionRule") or not operation.get("x-replayRule"):
                    fail("TMS version or replay-header declaration differs")
            elif "requestBody" in operation:
                fail("TMS read operation must not declare a request body")
            responses = operation["responses"]
            if set(responses) != ERROR_STATUSES | {"200"}:
                fail("TMS HTTP status declarations differ")
            if resolve(spec, responses["200"])["content"]["application/json"]["schema"] != {"$ref": f"#/components/schemas/{response}"}:
                fail("TMS success envelope declaration differs")
            for status in ERROR_STATUSES:
                if resolve(spec, responses[status])["content"]["application/json"]["schema"] != {"$ref": "#/components/schemas/ErrorEnvelope"}:
                    fail("TMS error envelope declaration differs")
            if self_read and set(pairs) != {("query", "page"), ("query", "size")}:
                fail("TMS self query must be limited to page/size")
        for schema in schemas.values():
            jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError, TypeError, AttributeError, jsonschema.SchemaError):
        fail("TMS schema metadata or operation structure is invalid")
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
    uid = lambda value: f"00000000-0000-4000-8000-{value:012d}"
    stamp = "2026-11-26T10:00:00.123456+08:00"
    raw_time = "1500-01-02T00:00:00.123456+23:59"
    item = {"itemKind": "CARGO", "containerType": None, "containerNo": None,
            "packageQty": 7, "grossWeight": "0.0000000000000000001", "cbm": "0.00"}
    source = {"taskId": uid(1), "taskNo": "TSK-SYNTHETIC", "orderId": uid(2), "orderNo": "ORD-SYNTHETIC",
              "orderType": "LOCAL", "orderVersion": 1, "item": item, "taskKind": " 合成 e\u0301 ",
              "pickupName": "合成起點", "pickupAddress": "合成\u0000地址", "deliveryName": "合成終點",
              "deliveryAddress": "合成地址", "siteContact": {"name": None, "phone": None},
              "plannedStartAt": raw_time, "plannedEndAt": None, "requiredTask": True}
    summary = {key: source[key] for key in ("taskId", "taskNo", "orderId", "orderNo", "orderType", "orderVersion", "taskKind", "pickupName", "deliveryName")}
    summary.update(generation=0, prerequisiteReady=True, currentAssignmentId=None,
                   policyMode="UNCONFIRMED", blockingReasons=["POLICY_NOT_CONFIRMED"])
    driver = {"id": uid(3), "driverCode": "SYNTHETIC-TMS-3", "name": "合成司機", "version": 0}
    vehicle = {"id": uid(4), "plateNo": "SYNTHETIC-4", "vehicleType": uid(5),
               "vehicleTypeCode": "SYNTHETIC_TMS_MAIN", "vehicleTypeLabel": "合成主車", "version": 0}
    reservations = [{"id": uid(10 + index), "kind": kind, "position": position, "resourceId": uid(resource),
                     "acquiredAt": stamp, "releasedAt": None, "releaseReason": None}
                    for index, (kind, position, resource) in enumerate((('DRIVER', 'DRIVER', 3), ('VEHICLE', 'MAIN', 4)))]
    event = {"id": uid(12), "action": "ASSIGNED", "assignmentId": uid(6), "previousAssignmentId": None,
             "fromGeneration": 0, "toGeneration": 1, "actorId": uid(7), "occurredAt": stamp, "reason": None}
    assignment = {"id": uid(6), "taskId": uid(1), "orderId": uid(2), "status": "ASSIGNED", "version": 0,
                  "assignedGeneration": 1, "endedGeneration": None, "driver": driver, "mainVehicle": vehicle,
                  "trailer": None, "source": source, "policyMode": "SYNTHETIC_TEST_ONLY", "assignedBy": uid(7),
                  "assignedAt": stamp, "assignmentReason": None, "endedBy": None, "endedAt": None, "endReason": None,
                  "previousAssignmentId": None, "reservations": reservations, "events": [event]}
    terminal = copy.deepcopy(assignment)
    terminal.update(status="REVOKED", version=1, endedGeneration=2, endedBy=uid(7), endedAt=stamp, endReason="合成撤派")
    for reservation in terminal["reservations"]:
        reservation.update(releasedAt=stamp, releaseReason="合成撤派")
    terminal["events"].append({**event, "id": uid(13), "action": "REVOKED", "fromGeneration": 1, "toGeneration": 2, "reason": "合成撤派"})
    trailer_assignment = copy.deepcopy(assignment)
    trailer_assignment["trailer"] = {**vehicle, "id": uid(8), "plateNo": "SYNTHETIC-8"}
    trailer_assignment["reservations"].append({**reservations[1], "id": uid(14), "position": "TRAILER", "resourceId": uid(8)})
    candidate = {"id": uid(3), "kind": "DRIVER", "position": "DRIVER", "code": "SYNTHETIC-TMS-3", "label": "合成司機",
                 "version": 0, "administrativelyAvailable": True, "occupied": False, "occupation": None,
                 "policyMode": "UNCONFIRMED", "policyApproved": False, "blockingReasons": ["POLICY_NOT_CONFIRMED"]}
    own = {"assignmentId": uid(6), **{key: source[key] for key in ("taskId", "taskNo", "item", "taskKind", "pickupName", "pickupAddress", "deliveryName", "deliveryAddress", "siteContact", "plannedStartAt", "plannedEndAt")}, "assignedAt": stamp, "policyMode": "SYNTHETIC_TEST_ONLY"}
    page = lambda content: {"content": content, "page": 0, "size": 20, "totalElements": len(content), "totalPages": int(bool(content))}
    envelope = lambda data: {"code": "0000", "message": "成功", "data": data, "timestamp": stamp}
    command = lambda value, generation: {"taskId": uid(1), "generation": generation, "assignment": value}
    detail = {"task": {**summary, "generation": 1, "currentAssignmentId": uid(6)}, "current": assignment, "history": page([assignment])}
    assign_request = {"expectedGeneration": 0, "driverId": uid(3), "mainVehicleId": uid(4), "trailerId": None}
    positive = [
        ("TaskPageEnvelope", envelope(page([summary]))), ("TaskPageEnvelope", envelope(page([]))),
        ("DispatchDetailEnvelope", envelope(detail)),
        ("DispatchDetailEnvelope", envelope({"task": summary, "current": None, "history": page([])})),
        ("CommandResultEnvelope", envelope(command(assignment, 1))),
        ("CommandResultEnvelope", envelope(command(trailer_assignment, 1))),
        ("CommandResultEnvelope", envelope(command(terminal, 2))),
        ("ResourcePageEnvelope", envelope(page([candidate]))), ("ResourcePageEnvelope", envelope(page([]))),
        ("MyTaskPageEnvelope", envelope(page([own]))), ("MyTaskPageEnvelope", envelope(page([]))),
        ("AssignRequest", assign_request), ("AssignRequest", {**assign_request, "expectedGeneration": Decimal('1.0'), "trailerId": uid(8)}),
        ("AssignRequest", {**assign_request, "expectedGeneration": 9007199254740991}),
        ("RevokeRequest", {"expectedGeneration": 1, "reason": "合成撤派"}),
        ("RevokeRequest", {"expectedGeneration": 1, "reason": " " * 600 + "e\u0301" + "\ufeff" * 600}),
        ("ReassignRequest", {**assign_request, "expectedGeneration": 2, "driverId": uid(9), "reason": "合成改派"}),
        ("IdempotencyReplayed", "true"), ("IdempotencyReplayed", "false"),
        ("TaskSource", {**source, "siteContact": None}),
        ("TaskSource", {**source, "pickupName": "", "taskKind": " \u0000 ", "siteContact": {"name": "", "phone": None}}),
        ("TaskSource", {**source, "plannedStartAt": "0001-01-01T00:00:00Z", "plannedEndAt": "9999-12-31T23:59:59.999999-23:59"}),
        ("ItemSnapshot", {**item, "packageQty": None, "grossWeight": None, "cbm": None}),
        ("Event", {**event, "reason": "合成\n多行\t原因\r"}),
    ]
    for kind in ("IMPORT", "EXPORT", "LOCAL"):
        positive.append(("TaskSource", {**source, "orderType": kind}))
    for status in ("REVOKED", "SUPERSEDED"):
        positive.append(("Assignment", {**terminal, "status": status}))
    reassign = copy.deepcopy(assignment)
    reassign.update(id=uid(15), driver={**driver, "id": uid(9)}, assignedGeneration=3, previousAssignmentId=uid(6), assignmentReason="合成改派")
    reassign['events'] = [{**event, "id": uid(16), "action": "REASSIGNED", "assignmentId": uid(15), "previousAssignmentId": uid(6), "fromGeneration": 2, "toGeneration": 3, "reason": "合成改派"}]
    positive.append(("CommandResultEnvelope", envelope(command(reassign, 3))))
    positive.append(("ResourceCandidate", {**candidate, "policyMode": "SYNTHETIC_TEST_ONLY", "policyApproved": True, "occupied": True, "occupation": {"assignmentId": uid(6), "taskId": uid(1)}, "blockingReasons": ["RESOURCE_OCCUPIED"]}))
    positive.append(("ResourceCandidate", {**candidate, "kind": "VEHICLE", "position": "MAIN"}))
    positive.append(("ResourceCandidate", {**candidate, "kind": "VEHICLE", "position": "TRAILER"}))
    positive.append(("ErrorEnvelope", {"code": "E0400", "message": "合成驗證", "data": {"reason": "VALIDATION_ERROR", "fields": {"reason": "合成欄位提示"}}, "timestamp": stamp}))
    for code in ("E0401", "E0403", "E0404", "E0405", "E0415", "E0500"):
        positive.append(("ErrorEnvelope", {"code": code, "message": "合成拒絕", "data": None, "timestamp": stamp}))
    for reason in ("CSRF_INVALID", "FORBIDDEN"):
        positive.append(("ErrorEnvelope", {"code": "E0403", "message": "合成安全拒絕", "data": {"reason": reason}, "timestamp": stamp}))
    for code, reason in BUSINESS_ERRORS.items():
        data = {"reason": reason}
        if code == 'E1601':
            data['actualGeneration'] = 0
        positive.append(("ErrorEnvelope", {"code": code, "message": "合成拒絕", "data": data, "timestamp": stamp}))

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
    for field, value in [('code', 'OK'), ('traceId', 'forbidden'), ('timestamp', '2026-11-26T02:00:00Z'), ('timestamp', '2026-02-30T02:00:00+08:00')]:
        changed('TaskPageEnvelope', envelope(page([summary])), [field], value)
    changed('TaskPageEnvelope', envelope(page([summary])), ['timestamp'], remove=True)
    changed('TaskPageEnvelope', envelope(page([summary])), ['data'], None)
    for value in [True, Decimal('0.1'), Decimal('9007199254740991.1'), 9007199254740992, -1, '1']:
        changed('AssignRequest', assign_request, ['expectedGeneration'], value)
    changed('AssignRequest', assign_request, ['trailerId'], remove=True)
    changed('AssignRequest', assign_request, ['driverId'], 'invalid-uuid')
    for field in ['reason', 'actorId', 'policyMode', 'generation', 'timestamp']:
        changed('AssignRequest', assign_request, [field], 'forbidden')
    changed('ReassignRequest', {**assign_request, 'reason': '合成'}, ['reason'], remove=True)
    changed('RevokeRequest', {'expectedGeneration': 0, 'reason': '合成'}, ['reason'], None)
    changed('RevokeRequest', {'expectedGeneration': 0, 'reason': '合成'}, ['driverId'], uid(3))
    for field in ['orderId', 'orderNo', 'customerId', 'customerName', 'driverId', 'accountId', 'resourceId', 'plateNo', 'generation', 'assignedBy', 'events', 'reservations', 'remark', 'shipping', 'tasks', 'licenseReference']:
        changed('MyTask', own, [field], 'forbidden')
    changed('MyTask', own, ['item', 'id'], uid(20))
    changed('MyTask', own, ['item', 'packageQty'], 0)
    changed('TaskSource', source, ['customerName'], 'forbidden')
    changed('TaskSource', source, ['predecessorRefs'], [])
    changed('TaskSource', source, ['siteContact', 'phone'], remove=True)
    for value in ['2026-02-30T00:00:00Z', '0000-01-01T00:00:00Z', '2026-01-01T00:00:00.1234567Z', '2026-01-01T00:00:60Z', '2026-01-01T00:00:00+24:00', '2026-01-01T00:00:00']:
        changed('TaskSource', source, ['plannedStartAt'], value)
    for value in ['01.00', '-1', '1e2', '1\n', '0.' + '1' * 32, 1.0]:
        changed('ItemSnapshot', item, ['grossWeight'], value)
    changed('DriverSnapshot', driver, ['accountId'], uid(7))
    changed('DriverSnapshot', driver, ['licenseReference'], 'forbidden')
    changed('VehicleSnapshot', vehicle, ['capacityTon'], 'forbidden')
    changed('VehicleSnapshot', vehicle, ['inspectionDueDate'], 'forbidden')
    changed('Assignment', assignment, ['version'], 1)
    changed('Assignment', assignment, ['endedAt'], stamp)
    changed('Assignment', terminal, ['version'], 0)
    for field in ['endedGeneration', 'endedBy', 'endedAt', 'endReason']:
        changed('Assignment', terminal, [field], None)
    changed('Assignment', terminal, ['endReason'], '')
    changed('Assignment', terminal, ['endReason'], '合成\u0000原因')
    changed('Assignment', assignment, ['reservations'], reservations[:1])
    changed('Assignment', assignment, ['reservations'], [reservations[0], {**reservations[0], 'id': uid(31), 'resourceId': uid(32)}])
    changed('Assignment', assignment, ['reservations'], [reservations[1], {**reservations[1], 'id': uid(33), 'resourceId': uid(34)}])
    changed('Assignment', assignment, ['events'], [event, {**event, 'id': uid(21)}, {**event, 'id': uid(22)}])
    changed('Assignment', trailer_assignment, ['reservations'], reservations)
    changed('Reservation', reservations[0], ['position'], 'MAIN')
    changed('Reservation', reservations[1], ['position'], 'DRIVER')
    changed('Reservation', reservations[0], ['releaseReason'], '合成尚未釋放')
    changed('ResourceCandidate', candidate, ['kind'], 'VEHICLE')
    changed('ResourceCandidate', candidate, ['policyApproved'], True)
    changed('ResourceCandidate', candidate, ['blockingReasons'], ['LEGAL_APPROVED'])
    for field, value in [('size', 0), ('size', 101), ('page', 100001), ('page', -1), ('totalElements', -1), ('content', [summary] * 101)]:
        changed('TaskPage', page([summary]), [field], value)
    changed('CommandResult', command(assignment, 1), ['generation'], 0)
    changed('CommandResult', command(assignment, 1), ['traceId'], 'forbidden')
    for value in [True, 'TRUE', 'false\n']:
        negative.append(('IdempotencyReplayed', value))
    validation_error = {'code': 'E0400', 'message': '合成', 'data': {'reason': 'VALIDATION_ERROR', 'fields': {'reason': '合成'}}, 'timestamp': stamp}
    changed('ErrorEnvelope', validation_error, ['data', 'fields'], {})
    changed('ErrorEnvelope', validation_error, ['data', 'fields', 'reason'], 7)
    changed('ErrorEnvelope', validation_error, ['data', 'actualVersion'], 1)
    changed('ErrorEnvelope', validation_error, ['code'], 'E0409')
    for code, reason in BUSINESS_ERRORS.items():
        data = {'reason': reason, **({'actualGeneration': 2} if code == 'E1601' else {})}
        value = {'code': code, 'message': '合成', 'data': data, 'timestamp': stamp}
        changed('ErrorEnvelope', value, ['data'], None)
        changed('ErrorEnvelope', value, ['data', 'reason'], 'OTHER_REASON')
        if code == 'E1601':
            changed('ErrorEnvelope', value, ['data', 'actualGeneration'], remove=True)
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
        fail("TMS reference rejection self-check failed")
    print(f"TMS static contract: 7 operations, 42 schemas, 4 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed")


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
        fail("No synthetic TMS HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic TMS response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic TMS response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"TMS response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual TMS HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual TMS HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
