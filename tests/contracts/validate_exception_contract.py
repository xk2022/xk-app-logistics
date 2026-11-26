"""Validate the frozen M4-03 exceptions/supplements contract and sanitized HTTP evidence without network access."""
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
import unicodedata

import jsonschema
from jsonschema import validators
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource
from referencing.jsonschema import DRAFT202012
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC_PATH = ROOT / "docs/06_api/tms-exceptions-openapi.yaml"
SCHEMA_URI = "urn:xk:tms:exceptions:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/exception-contract-responses.json"
PRESERVED_SPECS = {
    "tms-execution-openapi.yaml": ("b260dd0a814f11d1dc28b874f79f4280d4edce2b9bc5ac75ca0d4f3f7bbc51d4"),
    "tms-progress-evidence-openapi.yaml": ("fd062dd540f657dd48a172a8955ada62aa7dd80e8a0faa805d3e91dd3efbc791"),
}
COPIED_SCHEMAS = {"Uuid", "CanonicalUuid", "Version", "Timestamp", "CompanyTimestamp", "ExecutionPhase", "ExecutionView", "IdempotencyReplayed", "EvidenceIds", "Evidence", "EvidenceRequirements", "PolicyMode", "ExecutionVersionHeader", "BinaryPng"}
SCHEMAS = COPIED_SCHEMAS | {
    "ReportedPhase", "ExceptionStatus", "RawDescription", "RawNote", "CanonicalEvidenceIds", "ExceptionSummary",
    "ActiveSupplementRequest", "ExceptionRecord", "ActionAvailability", "ExceptionActions", "ExceptionDetail",
    "ReportExceptionRequest", "ManagerExceptionRequest", "SubmitSupplementRequest", "SupplementUploadResult",
    "SupplementEvidence", "DriverExceptionEvent", "ActorSnapshot", "ManagerExceptionEvent", "ExceptionSummaryPage",
    "DriverExceptionHistoryPage", "ManagerExceptionHistoryPage", "SupplementEvidencePage", "ErrorData", "ErrorEnvelope",
    "ExceptionSummaryPageEnvelope", "ExceptionRecordEnvelope", "ExceptionDetailEnvelope", "DriverExceptionHistoryPageEnvelope",
    "ManagerExceptionHistoryPageEnvelope", "SupplementUploadEnvelope", "SupplementEvidencePageEnvelope",
}
RESPONSE_SCHEMAS = {"ExceptionSummaryPageEnvelope", "ExceptionRecordEnvelope", "ExceptionDetailEnvelope", "DriverExceptionHistoryPageEnvelope", "ManagerExceptionHistoryPageEnvelope", "SupplementUploadEnvelope", "SupplementEvidencePageEnvelope", "ErrorEnvelope"}
D = "/tms/my-tasks/{assignmentId}/exceptions"
M = "/tms/exceptions"
OPERATIONS = {
    (D,"get"): ("listMyExceptions","ExceptionSummaryPageEnvelope",None,["page","size","status"]),
    (D,"post"): ("reportMyException","ExceptionRecordEnvelope","ReportExceptionRequest",[]),
    (D+"/{exceptionId}","get"): ("getMyException","ExceptionDetailEnvelope",None,[]),
    (D+"/{exceptionId}/history","get"): ("getMyExceptionHistory","DriverExceptionHistoryPageEnvelope",None,["page","size"]),
    (D+"/{exceptionId}/supplement-requests/{requestId}/evidence","post"): ("uploadMySupplement","SupplementUploadEnvelope","BinaryPng",[]),
    (D+"/{exceptionId}/supplement-requests/{requestId}/submit","post"): ("submitMySupplement","ExceptionRecordEnvelope","SubmitSupplementRequest",[]),
    (M,"get"): ("listExceptions","ExceptionSummaryPageEnvelope",None,["page","size","status","assignmentId"]),
    (M+"/{exceptionId}","get"): ("getException","ExceptionDetailEnvelope",None,[]),
    (M+"/{exceptionId}/history","get"): ("getExceptionHistory","ManagerExceptionHistoryPageEnvelope",None,["page","size"]),
    (M+"/{exceptionId}/supplement-requests","post"): ("requestExceptionSupplement","ExceptionRecordEnvelope","ManagerExceptionRequest",[]),
    (M+"/{exceptionId}/resolve","post"): ("resolveException","ExceptionRecordEnvelope","ManagerExceptionRequest",[]),
    (M+"/{exceptionId}/evidence","get"): ("listExceptionEvidence","SupplementEvidencePageEnvelope",None,["page","size"]),
    (M+"/{exceptionId}/evidence/{evidenceId}/content","get"): ("getExceptionEvidenceContent",None,None,[]),
}
BUSINESS_ERRORS = {
    "E1901":"VERSION_CONFLICT", "E1902":"INVALID_STATE", "E1903":"POLICY_NOT_CONFIRMED", "E1904":"IDEMPOTENCY_KEY_REUSED",
    "E1905":"IDEMPOTENCY_IN_PROGRESS", "E1906":"PROTECTED_RELATION", "E1907":"SUPPLEMENT_REQUIRED", "E1908":"EVIDENCE_INVALID",
}
ERROR_STATUSES = {"400","401","403","404","405","409","413","415","500"}
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


def nonblank_raw_text(validator, enabled, value, schema):
    if enabled and isinstance(value, str):
        try:
            value.encode("utf-8",errors="strict")
        except UnicodeError:
            yield jsonschema.ValidationError("Raw text must contain valid Unicode scalars")
            return
        def blank(character):
            return character in "\t\n\v\f\r\x1c\x1d\x1e\x1f\ufeff" or unicodedata.category(character) in {"Zs","Zl","Zp"}
        if not value or all(blank(character) for character in value):
            yield jsonschema.ValidationError("Raw text must contain a nonblank code point")


def canonical_uuid_order(validator, enabled, value, schema):
    if enabled and isinstance(value, list) and all(isinstance(item,str) for item in value) and value != sorted(value):
        yield jsonschema.ValidationError("Canonical UUID set must be sorted")


def count_matches_ids(validator, enabled, value, schema):
    if enabled and isinstance(value,dict) and isinstance(value.get("evidenceIds"),list) and "uploadedCount" in value and value["uploadedCount"] != len(value["evidenceIds"]):
        yield jsonschema.ValidationError("Count must equal bounded evidence identity count")


def exact_version_increment(validator, enabled, value, schema):
    if not enabled or not isinstance(value,dict) or value.get("type")=="REPORTED":
        return
    before, after = value.get("fromVersion"), value.get("toVersion")
    if isinstance(before,(int,Decimal)) and not isinstance(before,bool) and isinstance(after,(int,Decimal)) and not isinstance(after,bool):
        if not (0 <= before <= 9007199254740991 and 0 <= after <= 9007199254740991):
            return
        if after != before + 1:
            yield jsonschema.ValidationError("Event version must advance exactly once")


ExactValidator = validators.extend(
    jsonschema.Draft202012Validator,
    validators={"x-uniqueCanonicalUuids":canonical_uuid_uniqueness,"x-nonBlankRawText":nonblank_raw_text,"x-canonicalUuidOrder":canonical_uuid_order,"x-countMatchesEvidenceIds":count_matches_ids,"x-exactVersionIncrement":exact_version_increment},
    type_checker=jsonschema.Draft202012Validator.TYPE_CHECKER.redefine("integer",exact_integer),
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
                    fail("Exception specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Exception specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Exception specification must not redefine an in-memory resource identity")
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
    originals={}
    try:
        for name,pin in PRESERVED_SPECS.items():
            data=(ROOT/"docs/06_api"/name).read_bytes()
            if hashlib.sha256(data).hexdigest()!=pin:
                fail("Frozen prior execution/progress OpenAPI source changed")
            originals[name]=yaml.safe_load(data.decode("utf-8"))
        spec=yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError,UnicodeError,yaml.YAMLError):
        fail("Cannot read independent exception specification")
    if not isinstance(spec,dict) or spec.get("openapi")!="3.1.0":
        fail("Expected independent exception OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        old=originals["tms-progress-evidence-openapi.yaml"]
        schemas=spec["components"]["schemas"]
        operations={(path,method):op for path,item in spec["paths"].items() for method,op in item.items() if method in HTTP_METHODS}
        if set(schemas)!=SCHEMAS or set(operations)!=set(OPERATIONS):
            fail("Expected exactly thirteen M403 operations and forty-six schemas")
        def copied_schema_matches(name):
            # Only the new document's Version annotation has different domain units.
            # Every numeric/type keyword and all other copied schemas remain exact.
            current, preserved = schemas[name], old["components"]["schemas"][name]
            if name == "Version":
                return ({k:v for k,v in current.items() if k != "description"}
                        == {k:v for k,v in preserved.items() if k != "description"})
            return current == preserved
        if spec.get("x-preservedSpecSha256")!=PRESERVED_SPECS or any(not copied_schema_matches(n) for n in COPIED_SCHEMAS):
            fail("Preserved source pins or copied immutable schema constraints differ")
        if spec["components"]["securitySchemes"]!=old["components"]["securitySchemes"]:
            fail("Exception Session/CSRF schemes differ")
        if spec.get("x-syntheticOnlyDecision")!={"database":"xk_logistics_e2e","minimumCount":1,"allowedMediaTypes":["image/png"],"maxBytes":65536,"maxPixels":4096,"resolutionRequiresSubmittedSupplement":True,"formalExceptionApproval":False}:
            fail("Synthetic isolated decision must not become formal approval")
        for (path,method),op in operations.items():
            identity,response,body,queries=OPERATIONS[(path,method)]
            writing=method=="post";driver=path.startswith("/tms/my-tasks/");upload=body=="BinaryPng";binary=response is None
            allowed,denied=(["DRIVER"],["ADMIN","DISPATCHER"]) if driver else (["ADMIN","DISPATCHER"],["DRIVER"])
            security={"sessionCookie":[]}
            if writing:security["csrfToken"]=[]
            if op.get("operationId")!=identity or op.get("x-allowedRoles")!=allowed or op.get("x-deniedRoles")!=denied or op.get("security")!=[security]:
                fail("Exception operation/role/Session/CSRF contract differs")
            if op.get("x-rejectUnknownQueryParameters") is not True or op.get("x-rejectDuplicateQueryParameters") is not True:
                fail("Exception strict query rejection differs")
            params=[resolve(spec,value) for value in op["parameters"]]
            pairs=[(v["in"],v["name"]) for v in params]
            expected={("path",name) for name in ("assignmentId","exceptionId","requestId","evidenceId") if "{"+name+"}" in path}|{("query",name) for name in queries}
            if writing:expected|={("header","X-CSRF-TOKEN"),("header","Idempotency-Key")}
            if upload:expected|={("header","X-Exception-Version"),("header","X-Execution-Version")}
            if len(pairs)!=len(expected) or set(pairs)!=expected or any(v.get("required") is not (v["in"]!="query") for v in params):
                fail("Exception exact path/query/header declarations differ")
            responses=op["responses"]
            if set(responses)!=ERROR_STATUSES|{"200"}:
                fail("Exception HTTP status declarations differ")
            success=resolve(spec,responses["200"]);media="image/png" if binary else "application/json";shape="BinaryPng" if binary else response
            if set(success["content"])!={media} or success["content"][media]["schema"]!={"$ref":f"#/components/schemas/{shape}"} or not private_header(success["headers"]):
                fail("Exception success media/schema/privacy differs")
            if writing:
                request=op["requestBody"];media="image/png" if upload else "application/json"
                replay=success["headers"]["Idempotency-Replayed"]
                if request.get("required") is not True or set(request["content"])!={media} or request["content"][media]["schema"]!={"$ref":f"#/components/schemas/{body}"} or replay!={"required":True,"schema":{"$ref":"#/components/schemas/IdempotencyReplayed"}}:
                    fail("Exception request/replay declaration differs")
                if op.get("x-maxStreamBytes")!=(1048576 if upload else 16384) or op.get("x-postLedgerMissErrors")!=["E1901","E1902","E1903","E1907","E1908"] or not op.get("x-replayRule") or not op.get("x-versionRule"):
                    fail("Exception bounds/version/post-ledger-miss guarantees differ")
            elif "requestBody" in op:fail("Exception GET must not declare a request body")
            if upload and (op.get("x-rejectDuplicateHeaders")!=["Content-Type","Content-Encoding","X-Exception-Version","X-Execution-Version","Idempotency-Key"] or op.get("x-rejectMediaParameters") is not True or op.get("x-allowedContentEncodings")!=["identity"]):
                fail("Exception raw binary metadata declarations differ")
            if binary:
                original=old["paths"]["/tms/my-tasks/{assignmentId}/evidence/{evidenceId}/content"]["get"]
                if success["headers"]!=original["responses"]["200"]["headers"] or op.get("x-noConditionalOrRangeServing") is not True or op.get("x-forbiddenSuccessHeaders")!=original["x-forbiddenSuccessHeaders"]:
                    fail("Exception manager private-content header contract differs")
            for status in ERROR_STATUSES:
                error=resolve(spec,responses[status])
                if error["content"]["application/json"]["schema"]!={"$ref":"#/components/schemas/ErrorEnvelope"} or not private_header(error["headers"]):
                    fail("Exception error envelope/privacy declarations differ")
        for schema in schemas.values():jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError,TypeError,AttributeError,jsonschema.SchemaError):
        fail("Exception schema metadata or operation structure invalid")
    registry=Registry(retrieve=deny_remote).with_resource(SCHEMA_URI,Resource.from_contents(spec,default_specification=DRAFT202012))
    return {name:ExactValidator({"$ref":f"{SCHEMA_URI}#/components/schemas/{name}"},registry=registry,format_checker=CHECKER) for name in schemas}


def report_error(label, schema, error):
    pointer = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.path)
    # Never print error.message, instance values, request bodies, cookies or headers.
    fail(f"{label}: {schema} failed {error.validator} at {pointer}")


def static_fixtures():
    uid=lambda n:f"a0000000-0000-4000-8000-{n:012x}"
    stamp="2026-11-26T10:00:00.123456+08:00";raw=" \n合成 e\u0301 \u0000原值\n "
    execution={"phase":"AWAITING_COMPLETION","version":3,"departedAt":stamp}
    record={"id":uid(1),"assignmentId":uid(2),"taskId":uid(3),"taskNo":"TSK-合成例外","reportedPhase":"DEPARTED","reportedExecutionVersion":1,"description":raw,"status":"OPEN","version":0,"blocking":True,"reportedAt":stamp,"resolvedAt":None,"resolutionNote":None,"activeRequest":None}
    active={"id":uid(5),"reason":raw,"requestedAt":stamp,"uploadedCount":0,"evidenceIds":[]}
    requested={**record,"status":"NEEDS_SUPPLEMENT","version":1,"activeRequest":active}
    uploaded={**requested,"version":2,"activeRequest":{**active,"uploadedCount":1,"evidenceIds":[uid(4)]}}
    submitted={**record,"version":3}
    resolved={**record,"status":"RESOLVED","version":4,"blocking":False,"resolvedAt":stamp,"resolutionNote":raw}
    summary={n:record[n] for n in ["id","assignmentId","taskId","taskNo","reportedPhase","status","version","blocking","reportedAt"]}
    yes={"allowed":True,"blocker":None};no=lambda reason:{"allowed":False,"blocker":reason}
    rules={"minimumCount":1,"allowedMediaTypes":["image/png"],"maxBytes":65536,"maxPixels":4096}
    actions={"mode":"SYNTHETIC_TEST_ONLY","requestSupplement":yes,"resolve":no("SUPPLEMENT_REQUIRED"),"uploadSupplement":no("FORBIDDEN"),"submitSupplement":no("FORBIDDEN"),"requirements":None}
    driveractions={**actions,"requestSupplement":no("FORBIDDEN"),"resolve":no("FORBIDDEN"),"uploadSupplement":yes,"submitSupplement":yes,"requirements":rules}
    detail={"exception":record,"execution":execution,"actions":actions}
    evidence={"id":uid(4),"assignmentId":uid(2),"taskId":uid(3),"phase":"AWAITING_COMPLETION","executionVersion":3,"mediaType":"image/png","byteSize":67,"sha256":"a"*64,"uploadedAt":stamp,"status":"VERIFIED"}
    upload={"exceptionId":uid(1),"assignmentId":uid(2),"taskId":uid(3),"version":2,"requestId":uid(5),"evidence":evidence}
    supplement={"requestId":uid(5),"submitted":False,"evidence":evidence}
    report={"expectedExecutionVersion":1,"description":raw};manager={"expectedVersion":0,"reason":raw};submit={"expectedVersion":2,"note":raw,"evidenceIds":[uid(4)]}
    page=lambda content:{"content":content,"page":0,"size":20,"totalElements":len(content),"totalPages":1 if content else 0}
    env=lambda data:{"code":"0000","message":"成功","data":data,"timestamp":stamp}
    err=lambda code,data:{"code":code,"message":"合成拒絕","data":data,"timestamp":stamp}
    eventbase={"id":uid(20),"exceptionId":uid(1),"type":"REPORTED","fromStatus":None,"toStatus":"OPEN","fromVersion":None,"toVersion":0,"occurredAt":stamp,"note":raw,"requestId":None,"evidenceIds":[]}
    events=[eventbase,
        {**eventbase,"id":uid(21),"type":"SUPPLEMENT_REQUESTED","fromStatus":"OPEN","toStatus":"NEEDS_SUPPLEMENT","fromVersion":0,"toVersion":1,"requestId":uid(5)},
        {**eventbase,"id":uid(22),"type":"SUPPLEMENT_EVIDENCE_UPLOADED","fromStatus":"NEEDS_SUPPLEMENT","toStatus":"NEEDS_SUPPLEMENT","fromVersion":1,"toVersion":2,"note":None,"requestId":uid(5),"evidenceIds":[uid(4)]},
        {**eventbase,"id":uid(23),"type":"SUPPLEMENT_SUBMITTED","fromStatus":"NEEDS_SUPPLEMENT","toStatus":"OPEN","fromVersion":2,"toVersion":3,"requestId":uid(5),"evidenceIds":[uid(4)]},
        {**eventbase,"id":uid(24),"type":"RESOLVED","fromStatus":"OPEN","toStatus":"RESOLVED","fromVersion":3,"toVersion":4},
    ]
    manager_events=[{**event,"actor":{"role":"DRIVER" if event["type"] in {"REPORTED","SUPPLEMENT_EVIDENCE_UPLOADED","SUPPLEMENT_SUBMITTED"} else "DISPATCHER","displayName":"合成操作員"}} for event in events]
    positive=[("ExceptionRecordEnvelope",env(value)) for value in [record,requested,uploaded,submitted,resolved]]
    positive += [("ExceptionSummaryPageEnvelope",env(page([summary]))),("ExceptionSummaryPageEnvelope",env(page([]))),("ExceptionDetailEnvelope",env(detail)),("ExceptionDetailEnvelope",env({"exception":uploaded,"execution":execution,"actions":driveractions})),("ExceptionDetailEnvelope",env({"exception":resolved,"execution":{**execution,"phase":"COMPLETED","version":4},"actions":{**actions,**{n:no("INVALID_STATE") for n in ["requestSupplement","resolve","uploadSupplement","submitSupplement"]}}})),("DriverExceptionHistoryPageEnvelope",env(page(events))),("ManagerExceptionHistoryPageEnvelope",env(page(manager_events))),("SupplementUploadEnvelope",env(upload)),("SupplementEvidencePageEnvelope",env(page([supplement]))),("SupplementEvidence",{**supplement,"submitted":True}),("ReportExceptionRequest",report),("ReportExceptionRequest",{**report,"expectedExecutionVersion":Decimal('1.0')}),("ManagerExceptionRequest",manager),("ManagerExceptionRequest",{**manager,"expectedVersion":9007199254740991}),("SubmitSupplementRequest",submit),("SubmitSupplementRequest",{**submit,"evidenceIds":[uid(8).upper(),uid(4)],"expectedVersion":Decimal('2e0')}),("SubmitSupplementRequest",{**submit,"evidenceIds":[]}),("RawDescription","🚚"*2000),("RawNote","\u0085"),("RawNote","\u0000"),("CanonicalEvidenceIds",[uid(n) for n in range(32)]),("ExecutionVersionHeader","0"),("ExecutionVersionHeader","9007199254740991"),("IdempotencyReplayed","true"),("IdempotencyReplayed","false")]
    positive.append(("ManagerExceptionEvent",{**manager_events[0],"actor":{"role":"DRIVER","displayName":"合"*80}}))
    for phase in ("DEPARTED","IN_PROGRESS","AWAITING_COMPLETION"):
        positive.append(("ExceptionRecord",{**record,"reportedPhase":phase}))
    for mode in ("UNCONFIRMED","SYNTHETIC_TEST_ONLY"):
        blocked={**actions,"mode":mode,**{n:no("POLICY_NOT_CONFIRMED") for n in ["requestSupplement","resolve","uploadSupplement","submitSupplement"]}}
        positive.append(("ExceptionDetail",{**detail,"actions":blocked}))
    for event,manager_event in zip(events,manager_events):
        positive += [("DriverExceptionEvent",event),("ManagerExceptionEvent",manager_event)]
    for code in ("E0401","E0403","E0404","E0405","E0413","E0415","E0500"):
        positive.append(("ErrorEnvelope",err(code,None)))
    positive += [("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{"description":"請提供原始回報"}})),("ErrorEnvelope",err("E0403",{"reason":"CSRF_INVALID"})),("ErrorEnvelope",err("E0403",{"reason":"FORBIDDEN"})),("ErrorEnvelope",err("E1901",{"reason":"VERSION_CONFLICT","actualExecutionVersion":3}))]
    for code,reason in BUSINESS_ERRORS.items():
        positive.append(("ErrorEnvelope",err(code,{"reason":reason,**({"actualVersion":1} if code=="E1901" else {})})))
    negative=[]
    def change(schema,value,path,replacement=None,remove=False):
        altered=copy.deepcopy(value);cursor=altered
        for part in path[:-1]:cursor=cursor[part]
        if remove:del cursor[path[-1]]
        else:cursor[path[-1]]=replacement
        negative.append((schema,altered))
    objects=[("ExceptionSummary",summary),("ExceptionRecord",record),("ActiveSupplementRequest",active),("ActionAvailability",yes),("ExceptionActions",actions),("ExceptionDetail",detail),("ReportExceptionRequest",report),("ManagerExceptionRequest",manager),("SubmitSupplementRequest",submit),("SupplementUploadResult",upload),("SupplementEvidence",supplement),("DriverExceptionEvent",eventbase),("ManagerExceptionEvent",manager_events[0]),("ActorSnapshot",manager_events[0]["actor"]),("ExceptionSummaryPage",page([summary])),("DriverExceptionHistoryPage",page(events)),("ManagerExceptionHistoryPage",page(manager_events)),("SupplementEvidencePage",page([supplement]))]
    for schema,value in objects:
        for field in value:change(schema,value,[field],remove=True)
        negative += [(schema,None),(schema,[]),(schema,{**value,"unknown":None})]
    for name,data in [("ExceptionRecordEnvelope",record),("ExceptionDetailEnvelope",detail),("ExceptionSummaryPageEnvelope",page([summary])),("DriverExceptionHistoryPageEnvelope",page(events)),("ManagerExceptionHistoryPageEnvelope",page(manager_events)),("SupplementUploadEnvelope",upload),("SupplementEvidencePageEnvelope",page([supplement]))]:
        for field in ["code","message","data","timestamp"]:change(name,env(data),[field],remove=True)
        for field,value in [("code","OK"),("traceId","forbidden"),("data",None),("timestamp","2026-11-26T02:00:00Z"),("timestamp","2026-02-30T10:00:00+08:00")]:change(name,env(data),[field],value)
    for schema,value in [("ExceptionRecord",record),("ExceptionSummary",summary),("DriverExceptionEvent",eventbase),("SupplementUploadResult",upload)]:
        for field in ["actorId","accountId","driverId","orderId","customerId","resourceId","plateNo","storagePath","storageKey","filename","url","bytes","base64","policyOverride","allowRelease","waiveFields"]:change(schema,value,[field],"forbidden")
    for phase in ["WAITING","COMPLETED","REVOKED",None]:
        change("ExceptionRecord",record,["reportedPhase"],phase);change("ExceptionSummary",summary,["reportedPhase"],phase)
    for value in [True,"0",-1,Decimal('0.1'),Decimal('9007199254740991.1'),9007199254740992]:
        change("ReportExceptionRequest",report,["expectedExecutionVersion"],value);change("ManagerExceptionRequest",manager,["expectedVersion"],value);change("SubmitSupplementRequest",submit,["expectedVersion"],value)
    for field in ["description","reportedAt","expectedVersion","driverId","blocking","policyMode","waiveFields"]:
        if field not in report:change("ReportExceptionRequest",report,[field],"forbidden")
    for schema in ["RawDescription","RawNote"]:
        for value in [""," ","\n\r\t", "\u00a0\u2007\u202f\ufeff\u3000", "\ud800",True,None,7]:negative.append((schema,value))
        negative.append((schema,"x"*(2001 if schema=="RawDescription" else 1001)))
    for value in [None,False,"false"]:change("ExceptionRecord",record,["blocking"],value)
    for field,value in [("status","NEEDS_SUPPLEMENT"),("status","RESOLVED"),("resolvedAt",stamp),("resolutionNote",raw),("activeRequest",active)]:change("ExceptionRecord",record,[field],value)
    for field,value in [("activeRequest",None),("version",0),("blocking",False)]:change("ExceptionRecord",requested,[field],value)
    for field,value in [("blocking",True),("resolvedAt",None),("resolutionNote",None),("activeRequest",active)]:change("ExceptionRecord",resolved,[field],value)
    for field,value in [("uploadedCount",1),("uploadedCount",33),("evidenceIds",[uid(4)]),("evidenceIds",[uid(9),uid(8)]),("evidenceIds",[uid(4).upper()])]:change("ActiveSupplementRequest",active,[field],value)
    for value in [[uid(4),uid(4)],[uid(4),uid(4).upper()],[uid(i) for i in range(33)],[None],["not-uuid"],None]:change("SubmitSupplementRequest",submit,["evidenceIds"],value)
    for value in ["", "01", "+1", "-1", "1.0", "1e0", " 1", "1 ", "1\n", "1,1", "9007199254740992",1,True,None,"１"]:negative.append(("ExecutionVersionHeader",value))
    for field,value in [("allowed",False),("blocker","POLICY_NOT_CONFIRMED"),("blocker","UNKNOWN"),("allowed",1)]:change("ActionAvailability",yes,[field],value)
    change("ActionAvailability",no("INVALID_STATE"),["blocker"],None)
    for name in ["requestSupplement","resolve","uploadSupplement","submitSupplement"]:
        change("ExceptionActions",{**actions,"mode":"UNCONFIRMED",**{n:no("POLICY_NOT_CONFIRMED") for n in ["requestSupplement","resolve","uploadSupplement","submitSupplement"]}},[name],yes)
    change("ExceptionActions",{**driveractions,"mode":"UNCONFIRMED"},["requirements"],rules)
    change("ExceptionActions",driveractions,["requirements"],None)
    change("ExceptionDetail",detail,["actions","requirements"],rules)
    change("ExceptionDetail",detail,["actions","uploadSupplement"],yes)
    change("ExceptionDetail",{"exception":uploaded,"execution":execution,"actions":driveractions},["actions","resolve"],yes)
    change("ExceptionDetail",{"exception":uploaded,"execution":execution,"actions":driveractions},["execution","phase"],"COMPLETED")
    for event in events:
        change("DriverExceptionEvent",event,["actor"],manager_events[0]["actor"])
        change("DriverExceptionEvent",event,["toVersion"],event["toVersion"]+2)
        change("DriverExceptionEvent",event,["toStatus"],"RESOLVED" if event["toStatus"]!="RESOLVED" else "OPEN")
        change("DriverExceptionEvent",event,["type"],"WAIVED")
    change("DriverExceptionEvent",events[2],["note"],raw);change("DriverExceptionEvent",events[2],["evidenceIds"],[])
    change("DriverExceptionEvent",events[3],["requestId"],None);change("DriverExceptionEvent",events[3],["evidenceIds"],[])
    change("DriverExceptionEvent",events[1],["note"],"x"*1001)
    change("DriverExceptionEvent",events[1],["fromVersion"],Decimal("1e999999999"))
    change("ManagerExceptionEvent",manager_events[0],["actor","role"],"ADMIN")
    change("ManagerExceptionEvent",manager_events[1],["actor","role"],"DRIVER")
    change("ManagerExceptionEvent",manager_events[0],["actor","accountId"],uid(99))
    change("ManagerExceptionEvent",manager_events[0],["actor","displayName"],"合"*81)
    change("SupplementUploadResult",upload,["version"],0);change("SupplementUploadResult",upload,["version"],1);
    for phase in ["WAITING","COMPLETED"]:change("SupplementUploadResult",upload,["evidence","phase"],phase)
    change("SupplementUploadResult",upload,["evidence","actorId"],uid(99))
    change("SupplementEvidence",supplement,["submitted"],1)
    for field,value in [("page",-1),("page",100001),("size",0),("size",101),("content",[summary]*101),("totalElements",Decimal('1.1'))]:change("ExceptionSummaryPage",page([summary]),[field],value)
    for code in ["E1601","E1701","E1801","E1909"]:negative.append(("ErrorEnvelope",err(code,None)))
    for code in ["E0401","E0404","E0405","E0413","E0415","E0500"]:negative.append(("ErrorEnvelope",err(code,{"actualVersion":1})))
    for data in [{},{"reason":"UNKNOWN"},{"reason":"FORBIDDEN","actualVersion":1},{"reason":"CSRF_INVALID","fields":{"note":"合成"}}]:negative.append(("ErrorEnvelope",err("E0403",data)))
    for data in [{"reason":"VERSION_CONFLICT"},{"reason":"VERSION_CONFLICT","actualVersion":1,"actualExecutionVersion":3},{"reason":"VERSION_CONFLICT","actualVersion":None}]:negative.append(("ErrorEnvelope",err("E1901",data)))
    for field in ["actualVersion","actualExecutionVersion"]:
        for value in [True,-1,Decimal('1.1'),Decimal('9007199254740991.1'),9007199254740992]:negative.append(("ErrorEnvelope",err("E1901",{"reason":"VERSION_CONFLICT",field:value})))
    for code,reason in BUSINESS_ERRORS.items():
        value=err(code,{"reason":reason,**({"actualVersion":1} if code=="E1901" else {})})
        change("ErrorEnvelope",value,["data"],None);change("ErrorEnvelope",value,["data","reason"],"OTHER_REASON")
        for field in ["actualGeneration","storagePath","actorId"]:change("ErrorEnvelope",value,["data",field],"forbidden")
    negative.append(("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{}})))
    negative.append(("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{"description":7}})))
    return positive,negative


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
        fail('Exception reference rejection self-check failed')
    print(f'Exception static contract: 13 operations, 46 schemas, 6 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed')


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
        fail("No synthetic exception HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic exception response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic exception response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"Exception response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual exception HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual exception HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
