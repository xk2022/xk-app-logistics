"""Validate the independent FMS schemas and sanitized HTTP evidence without network access."""
from __future__ import annotations

import argparse
import datetime as dt
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
SPEC_PATH = ROOT / "docs/06_api/fms-vehicles-openapi.yaml"
SCHEMA_URI = "urn:xk:fms:vehicles:0.1.0"
DEFAULT_RESPONSES = "test-results/**/vehicle-contract-responses.json"
SCHEMAS = {
    "Uuid", "Version", "NaturalDate", "CapacityTon", "AdministrativeStatus", "PlateInput",
    "RegistrationInput", "Vehicle", "VehicleOption", "VehicleType", "CreateVehicleRequest",
    "PatchVehicleRequest", "PageVehicle", "PageVehicleOption", "VehicleEnvelope",
    "PageVehicleEnvelope", "PageVehicleOptionEnvelope", "VehicleTypesEnvelope", "ErrorEnvelope",
}
RESPONSE_SCHEMAS = {
    "VehicleEnvelope", "PageVehicleEnvelope", "PageVehicleOptionEnvelope", "VehicleTypesEnvelope", "ErrorEnvelope",
}
OPERATIONS = {
    ("/fms/vehicles", "get"): "listVehicles",
    ("/fms/vehicles", "post"): "createVehicle",
    ("/fms/vehicles/{id}", "get"): "getVehicle",
    ("/fms/vehicles/{id}", "patch"): "patchVehicle",
    ("/fms/vehicle-types", "get"): "listVehicleTypes",
    ("/fms/vehicle-options", "get"): "listVehicleOptions",
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


def local_references_only(spec):
    def visit(value):
        if isinstance(value, dict):
            for keyword in ("$ref", "$dynamicRef"):
                if keyword not in value:
                    continue
                reference = value[keyword]
                if not isinstance(reference, str) or not reference.startswith("#/"):
                    fail("FMS specification contains a non-local reference; retrieval is forbidden")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("FMS specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("FMS specification must not redefine the in-memory resource identity")
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(spec)


def load_contract():
    try:
        spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError):
        fail("Cannot read the independent FMS specification")
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("Expected independent FMS OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas = spec["components"]["schemas"]
        operations = {
            (path, method): operation
            for path, path_item in spec["paths"].items()
            for method, operation in path_item.items() if method in HTTP_METHODS
        }
        if set(schemas) != SCHEMAS or set(operations) != set(OPERATIONS):
            fail("Expected the exact six FMS operations and nineteen component schemas")
        for key, operation in operations.items():
            if operation["operationId"] != OPERATIONS[key] or operation.get("x-allowedRoles") != ["ADMIN", "DISPATCHER"]:
                fail("FMS operation identity or role contract differs")
            expected_security = {"sessionCookie": []}
            if key[1] in {"post", "patch"}:
                expected_security["csrfToken"] = []
            if operation.get("security") != [expected_security]:
                fail("FMS Session/CSRF operation contract differs")
        for schema in schemas.values():
            jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError, TypeError, AttributeError, jsonschema.SchemaError):
        fail("FMS schema metadata or operation structure is invalid")
    registry = Registry(retrieve=deny_remote).with_resource(
        SCHEMA_URI, Resource.from_contents(spec, default_specification=DRAFT202012),
    )
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
    identity = "00000000-0000-4000-8000-000000000036"
    kind = "00000000-0000-4000-8000-000000000037"
    stamp = "2026-11-25T10:00:00.123456789+08:00"
    vehicle = {
        "id": identity, "plateNo": "SYNTHETIC-36", "vehicleType": kind,
        "vehicleTypeCode": "SYNTHETIC_TYPE", "vehicleTypeLabel": "合成車種",
        "vehicleTypeEnabled": True, "capacityTon": "12.3400", "registrationNo": None,
        "registrationDate": "1500-01-02", "inspectionDueDate": "0001-01-01",
        "administrativeStatus": "AVAILABLE", "version": 0, "createdAt": stamp,
        "createdBy": identity, "updatedAt": stamp, "updatedBy": identity,
    }
    option = {field: vehicle[field] for field in (
        "id", "plateNo", "vehicleType", "vehicleTypeCode", "vehicleTypeLabel", "vehicleTypeEnabled", "capacityTon", "version",
    )}
    envelope = lambda data: {"code": "0000", "message": "成功", "data": data, "timestamp": stamp}
    error = lambda code, data: {"code": code, "message": "合成拒絕訊息", "data": data, "timestamp": stamp}
    page = lambda content: {"content": content, "page": 0, "size": 20, "totalElements": len(content), "totalPages": int(bool(content))}
    create = {"plateNo": "SYNTHETIC-36", "vehicleType": kind}
    positive = [
        ("VehicleEnvelope", envelope(vehicle)),
        ("VehicleEnvelope", envelope({**vehicle, "capacityTon": None, "registrationDate": None, "inspectionDueDate": None})),
        ("VehicleEnvelope", envelope({**vehicle, "vehicleTypeEnabled": False, "administrativeStatus": "INACTIVE"})),
        ("PageVehicleEnvelope", envelope(page([]))),
        ("PageVehicleEnvelope", envelope(page([vehicle]))),
        ("PageVehicleOptionEnvelope", envelope(page([{**option, "vehicleTypeEnabled": False}]))),
        ("VehicleTypesEnvelope", envelope([])),
        ("VehicleTypesEnvelope", envelope([{"id": kind, "code": "SYNTHETIC_TYPE", "label": "合成車種"}])),
        ("ErrorEnvelope", error("E0400", {"vehicleType": "合成欄位問題"})),
        ("ErrorEnvelope", error("E0403", None)),
        ("ErrorEnvelope", error("E0409", {"plateNo": "合成重複"})),
        ("ErrorEnvelope", error("E0409", {"version": "合成版本衝突"})),
        ("ErrorEnvelope", error("E0503", None)),
        ("CreateVehicleRequest", create),
        # Canonical limits are intentionally NOT raw-string limits.
        ("CreateVehicleRequest", {**create, "plateNo": " " * 50 + "e\u0301-甲" + "\ufeff" * 50, "registrationNo": " " * 110}),
        ("CreateVehicleRequest", {**create, "capacityTon": "0.000000000000000000000000000001", "registrationDate": "9999-12-31"}),
        ("CreateVehicleRequest", {**create, "capacityTon": "0", "registrationNo": None}),
        ("PatchVehicleRequest", {"version": Decimal("1.0"), "capacityTon": None}),
        ("PatchVehicleRequest", {"version": 9007199254740990, "plateNo": "SYNTHETIC-CHANGED"}),
        ("PatchVehicleRequest", {"version": 0, "inspectionDueDate": None}),
    ]
    negative = [
        ("VehicleEnvelope", {**envelope(vehicle), "code": "OK"}),
        ("VehicleEnvelope", {**envelope(vehicle), "timestamp": "2026-11-25T02:00:00Z"}),
        ("VehicleEnvelope", {**envelope(vehicle), "traceId": "unexpected"}),
        ("VehicleEnvelope", envelope({key: value for key, value in vehicle.items() if key != "vehicleTypeEnabled"})),
        ("VehicleEnvelope", envelope({**vehicle, "version": Decimal("9007199254740991.1")})),
        ("VehicleEnvelope", envelope({**vehicle, "version": True})),
        ("VehicleEnvelope", envelope({**vehicle, "capacityTon": 12.34})),
        ("VehicleEnvelope", envelope({**vehicle, "plateNo": "A" * 41})),
        ("VehicleEnvelope", envelope({**vehicle, "registrationDate": "0000-01-01"})),
        ("VehicleEnvelope", envelope({**vehicle, "inspectionDueDate": "2026-02-30"})),
        ("PageVehicleOptionEnvelope", envelope(page([{**option, "vehicleTypeId": kind}]))),
        ("VehicleTypesEnvelope", envelope([{"id": kind, "code": "SYNTHETIC_TYPE", "label": "合成", "enabled": True}])),
        ("ErrorEnvelope", error("OK", None)),
        ("ErrorEnvelope", error("E0409", {"actualVersion": 9})),
        ("CreateVehicleRequest", {"plateNo": "SYNTHETIC-36"}),
        ("CreateVehicleRequest", {**create, "vehicleType": None}),
        ("CreateVehicleRequest", {**create, "administrativeStatus": "IN_USE"}),
        ("CreateVehicleRequest", {**create, "capacityTon": "01"}),
        ("CreateVehicleRequest", {**create, "capacityTon": "1e3"}),
        ("CreateVehicleRequest", {**create, "capacityTon": "1\n"}),
        ("CreateVehicleRequest", {**create, "capacityTon": "1" * 33}),
        ("PatchVehicleRequest", {"version": 1}),
        ("PatchVehicleRequest", {"version": 1, "plateNo": None}),
        ("PatchVehicleRequest", {"version": 1, "vehicleTypeLabel": "覆寫"}),
        ("PatchVehicleRequest", {"version": 9007199254740992, "capacityTon": None}),
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
    for keyword, reference in (("$ref", "https://invalid.example/schema"), ("$dynamicRef", "file:///tmp/schema"), ("$id", "https://invalid.example/identity")):
        try:
            local_references_only({keyword: reference})
        except SystemExit:
            continue
        fail("External-resource rejection self-check failed")
    print(f"FMS static contract: 6 operations, 19 schemas, {len(positive)} positive, {len(negative)} negative, 3 external-reference guards passed")


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
        fail("No synthetic FMS HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError):
            fail("Synthetic FMS response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic FMS response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record["schema"], str) or record["schema"] not in RESPONSE_SCHEMAS:
                fail(f"FMS response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record["schema"]].iter_errors(record["value"]), None)
            if issue:
                report_error(f"{path.name}[{index}]", record["schema"], issue)
            count += 1
    print(f"Actual FMS HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--responses", nargs="+", help="Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence")
    group.add_argument("--static-only", action="store_true", help="Only validate static schemas/fixtures; explicitly not HTTP acceptance")
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print("Actual FMS HTTP responses: not run (--static-only)")
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == "__main__":
    main()
