"""Validate the frozen M4-05 management confirmation/TOM closure contract and sanitized HTTP evidence without network access."""
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
SPEC_PATH = ROOT / "docs/06_api/tom-closure-openapi.yaml"
SCHEMA_URI = "urn:xk:tom:closure:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/closure-contract-responses.json"
PRESERVED_SPECS = {
    "tms-execution-openapi.yaml": ("b260dd0a814f11d1dc28b874f79f4280d4edce2b9bc5ac75ca0d4f3f7bbc51d4"),
    "tms-progress-evidence-openapi.yaml": ("fd062dd540f657dd48a172a8955ada62aa7dd80e8a0faa805d3e91dd3efbc791"),
    "tms-exceptions-openapi.yaml": ("34ac767897b8aa56cd121b80f17abc983a4133e51e89b0d1840b9d885feed965"),
    "tms-completion-openapi.yaml": ("450b3dc5160f7dec785506cd1751d338ff1bef15d2292b26ad39a5dde880b3a7"),
}
COPIED_SCHEMA_SOURCES = {
    "tms-execution-openapi.yaml": {"Uuid","Version","Timestamp","CompanyTimestamp","PolicyMode","ExecutionPhase","ExecutionView","IdempotencyReplayed"},
    "tms-progress-evidence-openapi.yaml": {"CanonicalUuid"},
    "tms-exceptions-openapi.yaml": {"RawNote","ActorSnapshot"},
    "tms-completion-openapi.yaml": {"CompletionWitness"},
}
COPIED_SCHEMAS = set().union(*COPIED_SCHEMA_SOURCES.values())
SCHEMAS = COPIED_SCHEMAS | {'ManagementConfirmationPolicy', 'CompletionConfirmationSummary', 'ErrorEnvelope', 'ClosureTaskReviewPage', 'ClosureSummaryPage', 'ManagementConfirmationHistoryPageEnvelope', 'ClosureAuditState', 'ManagementConfirmationHistoryPage', 'OrderClosureResult', 'OrderClosureView', 'ManagerActor', 'CompletionConfirmationSummaryPage', 'ClosureCounts', 'CompletionConfirmationDetail', 'CompletionConfirmationResult', 'ConfirmationVersion', 'OrderClosureHistoryPage', 'ClosureRequirements', 'OrderClosureHistoryPageEnvelope', 'CloseOrderRequest', 'CloseOrderEnvelope', 'ClosureSummary', 'ErrorData', 'ConfirmCompletionEnvelope', 'CompletionConfirmationSummaryPageEnvelope', 'OrderClosureWitness', 'ManagementConfirmationFact', 'CompletedExecution', 'ConfirmCompletionRequest', 'ClosureTaskReview', 'SafeVersion', 'OrderClosureEnvelope', 'OrderClosurePolicyView', 'SourceVersion', 'ClosureSummaryPageEnvelope', 'CompletionConfirmationDetailEnvelope', 'ClosureTaskReviewPageEnvelope'}
RESPONSE_SCHEMAS = {'OrderClosureEnvelope', 'ErrorEnvelope', 'ClosureTaskReviewPageEnvelope', 'OrderClosureHistoryPageEnvelope', 'ManagementConfirmationHistoryPageEnvelope', 'ClosureSummaryPageEnvelope', 'CloseOrderEnvelope', 'CompletionConfirmationDetailEnvelope', 'ConfirmCompletionEnvelope', 'CompletionConfirmationSummaryPageEnvelope'}
OPERATIONS = {
    ('/tms/completions','get'): ('listCompletionConfirmations','CompletionConfirmationSummaryPageEnvelope',None,['Page', 'Size', 'ConfirmationState', 'OrderFilter']),
    ('/tms/completions/{completionId}/confirmation','get'): ('getCompletionConfirmation','CompletionConfirmationDetailEnvelope',None,['CompletionId']),
    ('/tms/completions/{completionId}/confirm','post'): ('confirmCompletion','ConfirmCompletionEnvelope','ConfirmCompletionRequest',['CompletionId']),
    ('/tms/completions/{completionId}/confirmation/history','get'): ('getCompletionConfirmationHistory','ManagementConfirmationHistoryPageEnvelope',None,['CompletionId', 'Page', 'Size']),
    ('/orders/closure-worklist','get'): ('listOrderClosures','ClosureSummaryPageEnvelope',None,['Page', 'Size', 'ClosureState']),
    ('/orders/{orderId}/closure','get'): ('getOrderClosure','OrderClosureEnvelope',None,['OrderId']),
    ('/orders/{orderId}/closure/tasks','get'): ('getOrderClosureTasks','ClosureTaskReviewPageEnvelope',None,['OrderId', 'Page', 'Size']),
    ('/orders/{orderId}/closure/history','get'): ('getOrderClosureHistory','OrderClosureHistoryPageEnvelope',None,['OrderId', 'Page', 'Size']),
    ('/orders/{orderId}/close','post'): ('closeOrder','CloseOrderEnvelope','CloseOrderRequest',['OrderId']),
}
BUSINESS_ERRORS = {"E2101":"VERSION_CONFLICT","E2102":"INVALID_STATE","E2103":"POLICY_NOT_CONFIRMED","E2104":"IDEMPOTENCY_KEY_REUSED","E2105":"IDEMPOTENCY_IN_PROGRESS","E2106":"PROTECTED_RELATION","E2107":"TASKS_INCOMPLETE","E2108":"CONFIRMATION_REQUIRED","E2109":"UNRESOLVED_EXCEPTIONS","E2110":"RESOURCES_OCCUPIED"}
ERROR_STATUSES = {"400","401","403","404","405","409","413","415","500"}
POST_MISS = ["E2101","E2102","E2103","E2107","E2108","E2109","E2110"]
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


def nonblank_raw_text(validator, enabled, value, schema):
    if enabled and isinstance(value,str):
        try:
            value.encode("utf-8",errors="strict")
        except UnicodeError:
            yield jsonschema.ValidationError("Raw text must contain valid Unicode scalars")
            return
        def blank(character):
            return character in "\t\n\v\f\r\x1c\x1d\x1e\x1f\ufeff" or unicodedata.category(character) in {"Zs","Zl","Zp"}
        if not value or all(blank(character) for character in value):
            yield jsonschema.ValidationError("Raw text must contain a nonblank code point")


def equal_timestamp_fields(validator, fields, value, schema):
    if isinstance(value,dict) and all(name in value and isinstance(value[name],str) for name in fields):
        if len({value[name] for name in fields}) != 1:
            yield jsonschema.ValidationError("Completion and release timestamps must match")


def path_value(value,path):
    for name in path.split("."):
        if not isinstance(value,dict) or name not in value:
            return None
        value=value[name]
    return value


def equal_field_paths(validator, groups, value, schema):
    if not isinstance(value,dict):return
    for paths in groups:
        values=[path_value(value,path) for path in paths]
        if all(item is not None for item in values) and any(item!=values[0] for item in values[1:]):
            yield jsonschema.ValidationError("Typed public witness fields must match")


def safe_number(value):
    return isinstance(value,(int,Decimal)) and not isinstance(value,bool) and 0<=value<=9007199254740991 and value==int(value)


def version_increment(validator, fields, value, schema):
    if isinstance(value,dict) and all(safe_number(value.get(name)) for name in fields):
        if value[fields[1]]!=value[fields[0]]+1:
            yield jsonschema.ValidationError("TOM closure version must advance exactly once")


def ordered_counts(validator, fields, value, schema):
    if isinstance(value,dict) and all(safe_number(value.get(name)) for name in fields):
        if any(value[left]>value[right] for left,right in zip(fields,fields[1:])):
            yield jsonschema.ValidationError("Confirmed/completed/task counts must be ordered")


def page_arithmetic(validator, enabled, value, schema):
    if not enabled or not isinstance(value,dict) or not isinstance(value.get("content"),list):return
    if not all(safe_number(value.get(name)) for name in ["page","size","totalElements","totalPages"]) or value["size"]<1:return
    page,size,total,pages=[int(value[name]) for name in ["page","size","totalElements","totalPages"]]
    expected=(total+size-1)//size
    if pages!=expected or len(value["content"])>size or len(value["content"])!=min(size,max(0,total-page*size)):
        yield jsonschema.ValidationError("Page metadata and content count must agree")


ExactValidator = validators.extend(
    jsonschema.Draft202012Validator,
    validators={"x-nonBlankRawText":nonblank_raw_text,"x-equalTimestampFields":equal_timestamp_fields,"x-equalFieldPaths":equal_field_paths,"x-versionIncrementFields":version_increment,"x-orderedCounts":ordered_counts,"x-pageArithmetic":page_arithmetic},
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
                    fail("Closure specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Closure specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Closure specification must not redefine an in-memory resource identity")
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
            if hashlib.sha256(data).hexdigest()!=pin:fail("Frozen prior execution/progress/exception/completion OpenAPI source changed")
            originals[name]=yaml.safe_load(data.decode("utf-8"))
        spec=yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError,UnicodeError,yaml.YAMLError):fail("Cannot read independent management confirmation/closure specification")
    if not isinstance(spec,dict) or spec.get("openapi")!="3.1.0":fail("Expected independent closure OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas=spec["components"]["schemas"]
        operations={(path,method):op for path,item in spec["paths"].items() for method,op in item.items() if method in HTTP_METHODS}
        if set(schemas)!=SCHEMAS or set(operations)!=set(OPERATIONS):fail("Expected exactly nine M405 operations and forty-nine schemas")
        if spec.get("x-preservedSpecSha256")!=PRESERVED_SPECS:fail("Closure preserved spec pin metadata differs")
        if any(schemas[name]!=originals[source]["components"]["schemas"][name] for source,names in COPIED_SCHEMA_SOURCES.items() for name in names):fail("Closure copied source component definitions differ")
        original=originals["tms-execution-openapi.yaml"]["components"]["schemas"]["MyTaskExecutionDetail"]
        for target in ["CompletionConfirmationSummary","ClosureTaskReview"]:
            if schemas[target]["properties"]["taskNo"]!=original["properties"]["taskNo"]:fail("Closure raw source task number constraints differ")
        source=yaml.safe_load((ROOT/"docs/06_api/openapi.yaml").read_text(encoding="utf-8"))["components"]["schemas"]["OrderDraftRead"]["properties"]
        for target in ["CompletionConfirmationSummary","ClosureSummary","OrderClosureView","OrderClosureResult"]:
            if schemas[target]["properties"]["orderNo"]!=source["orderNo"]:fail("Closure raw source order number constraints differ")
        if schemas["ClosureTaskReview"]["properties"]["requiredTask"]!={"enum":[True,None]}:fail("Closure requiredTask must preserve true/null, never an invented false waiver")
        if spec["components"]["securitySchemes"]!=originals["tms-progress-evidence-openapi.yaml"]["components"]["securitySchemes"]:fail("Closure Session/CSRF schemes differ")
        if spec.get("x-syntheticOnlyDecision")!={"database":"xk_logistics_e2e","taskBasis":"ALL_PERSISTED_TASKS","requiresManagerConfirmation":True,"requiresNoBlockingExceptions":True,"requiresNoEffectiveOccupations":True,"formalResponsibilityApproved":False}:fail("Closure isolated decision must not become formal responsibility/waiver approval")
        if spec.get("x-requiredSqlSeals")!=["new-owned-closure-whole-graph-and-TMS-membership","new-owned-root-source-snapshot-immutable","UPDATE-DELETE-implicit-tuple-lock-use-NOWAIT-not-reverse-wait","legacy-CLOSED-without-new-owned-witness-still-legal"] or not spec.get("x-legacyClosedAudit"):fail("Closure new-owned-only/legacy/lock declarations differ")
        for (path,method),op in operations.items():
            identity,response,body,parameters=OPERATIONS[(path,method)];writing=method=="post";security={"sessionCookie":[]}
            if writing:security["csrfToken"]=[]
            if op.get("operationId")!=identity or op.get("x-allowedRoles")!=["ADMIN","DISPATCHER"] or op.get("x-deniedRoles")!=["DRIVER"] or op.get("security")!=[security]:fail("Closure operation/role/Session/CSRF contract differs")
            if op.get("x-rejectUnknownQueryParameters") is not True or op.get("x-rejectDuplicateQueryParameters") is not True:fail("Closure exact query rejection differs")
            expected=[{"$ref":"#/components/parameters/"+name} for name in parameters+(["CsrfToken","IdempotencyKey"] if writing else [])]
            if op.get("parameters")!=expected:fail("Closure path/header/query declarations differ")
            for p in [resolve(spec,value) for value in op["parameters"]]:
                if p["in"] in ["path","header"] and p.get("required") is not True:fail("Closure required path/header differs")
            responses=op["responses"]
            if set(responses)!=ERROR_STATUSES|{"200"}:fail("Closure HTTP statuses differ")
            success=resolve(spec,responses["200"])
            if set(success["content"])!={"application/json"} or success["content"]["application/json"]["schema"]!={"$ref":f"#/components/schemas/{response}"} or not private_header(success["headers"]):fail("Closure success media/schema/privacy differs")
            if writing:
                request=op["requestBody"]
                if request.get("required") is not True or set(request["content"])!={"application/json"} or request["content"]["application/json"]["schema"]!={"$ref":"#/components/schemas/"+body} or success["headers"]["Idempotency-Replayed"]!={"required":True,"schema":{"$ref":"#/components/schemas/IdempotencyReplayed"}}:fail("Closure command/replay declarations differ")
                if op.get("x-maxStreamBytes")!=16384 or op.get("x-postLedgerMissErrors")!=POST_MISS or not op.get("x-replayRule") or not op.get("x-versionRule"):fail("Closure stream/version/post-ledger-miss declarations differ")
            elif "requestBody" in op:fail("Closure GET must not declare a body")
            for status in ERROR_STATUSES:
                error=resolve(spec,responses[status])
                if error["content"]["application/json"]["schema"]!={"$ref":"#/components/schemas/ErrorEnvelope"} or not private_header(error["headers"]):fail("Closure error envelope/privacy differs")
        for name,definition in [("Page",{"type":"integer","minimum":0,"maximum":100000,"default":0}),("Size",{"type":"integer","minimum":1,"maximum":100,"default":20})]:
            if spec["components"]["parameters"][name]["schema"]!=definition:fail("Closure independent bounded pagination differs")
        for schema in schemas.values():jsonschema.Draft202012Validator.check_schema(schema)
    except (OSError,UnicodeError,yaml.YAMLError,KeyError,TypeError,AttributeError,jsonschema.SchemaError):fail("Closure schema metadata or operation structure invalid")
    registry=Registry(retrieve=deny_remote).with_resource(SCHEMA_URI,Resource.from_contents(spec,default_specification=DRAFT202012))
    return {name:ExactValidator({"$ref":f"{SCHEMA_URI}#/components/schemas/{name}"},registry=registry,format_checker=CHECKER) for name in schemas}


def report_error(label, schema, error):
    pointer = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.path)
    # Never print error.message, instance values, request bodies, cookies or headers.
    fail(f"{label}: {schema} failed {error.validator} at {pointer}")


def static_fixtures():
    uid=lambda n:f"a0000000-0000-4000-8000-{n:012x}"
    stamp="2026-11-26T10:00:00.123456+08:00"
    raw=" 合成\u0000 e\u0301 原始理由\r\n第二行 "
    actor={"role":"ADMIN","displayName":" 合成\u0000管理 e\u0301 "}
    execution={"phase":"COMPLETED","version":4,"departedAt":stamp}
    completion={"id":uid(3),"completedAt":stamp,"releasedAt":stamp,"policyMode":"SYNTHETIC_TEST_ONLY","safeReleaseConfirmed":True}
    fact={"id":uid(4),"version":1,"confirmedAt":stamp,"policyMode":"SYNTHETIC_TEST_ONLY","reason":raw,"actor":actor}
    summary={"completionId":uid(3),"assignmentId":uid(1),"taskId":uid(2),"taskNo":" 合成\u0000 e\u0301 ","orderId":uid(5),"orderNo":" 合成\u0000訂單 e\u0301 ","sourceOrderVersion":1,"completedAt":stamp,"version":0,"state":"PENDING","confirmedAt":None}
    confirm_policy={"mode":"SYNTHETIC_TEST_ONLY","canConfirm":True,"blocker":None}
    pending={"record":summary,"execution":execution,"completion":completion,"confirmation":None,"policy":confirm_policy}
    confirmed={**pending,"record":{**summary,"version":1,"state":"CONFIRMED","confirmedAt":stamp},"confirmation":fact,"policy":{"mode":"UNCONFIRMED","canConfirm":False,"blocker":"ALREADY_CONFIRMED"}}
    confirm_result={"completionId":uid(3),"assignmentId":uid(1),"taskId":uid(2),"sourceOrderVersion":1,"version":1,"confirmation":fact}
    confirm_command={"expectedVersion":0,"confirmCompletion":True,"reason":raw}
    requirements={"taskBasis":"ALL_PERSISTED_TASKS","requiresManagerConfirmation":True,"requiresNoBlockingExceptions":True,"requiresNoEffectiveOccupations":True}
    close_policy={"mode":"SYNTHETIC_TEST_ONLY","canClose":True,"blocker":None,"requirements":requirements}
    counts={"taskCount":1,"completedTaskCount":1,"confirmedTaskCount":1,"blockingExceptionCount":0,"effectiveOccupationCount":0,"sourceOrderVersion":1}
    witness={"id":uid(6),"sourceVersion":1,"version":2,"closedAt":stamp,"policyMode":"SYNTHETIC_TEST_ONLY","managerConfirmed":True,"reason":raw,"taskCount":1,"actor":actor}
    order={"orderId":uid(5),"orderNo":summary["orderNo"],"lifecycleStatus":"OPEN","acceptanceStatus":"ACCEPTED","version":1,"auditState":"NOT_CLOSED","closure":None,"policy":close_policy,"counts":counts}
    recorded={**order,"lifecycleStatus":"CLOSED","version":2,"auditState":"RECORDED","closure":witness,"counts":None,"policy":{"mode":"UNCONFIRMED","canClose":False,"blocker":"ALREADY_CLOSED","requirements":None}}
    legacy={**recorded,"version":1,"auditState":"LEGACY_CLOSED","closure":None}
    closure_summary={"orderId":uid(5),"orderNo":summary["orderNo"],"type":"LOCAL","lifecycleStatus":"OPEN","acceptanceStatus":"ACCEPTED","version":1,"auditState":"NOT_CLOSED","updatedAt":stamp,"closedAt":None}
    review={"orderId":uid(5),"sourceOrderVersion":1,"taskId":uid(2),"taskNo":summary["taskNo"],"requiredTask":True,"assignmentId":uid(1),"completionId":uid(3),"executionPhase":"COMPLETED","executionVersion":4,"completionVerified":True,"confirmationVersion":1,"confirmedAt":stamp,"blockingExceptionCount":0,"effectiveOccupationCount":0}
    waiting={**review,"requiredTask":None,"assignmentId":None,"completionId":None,"executionPhase":"WAITING","executionVersion":0,"completionVerified":False,"confirmationVersion":0,"confirmedAt":None}
    close_result={"orderId":uid(5),"orderNo":summary["orderNo"],"lifecycleStatus":"CLOSED","acceptanceStatus":"ACCEPTED","version":2,"closure":witness}
    close_command={"expectedVersion":1,"confirmClosure":True,"reason":raw}
    page=lambda data:{"content":[data],"page":0,"size":20,"totalElements":1,"totalPages":1}
    env=lambda data:{"code":"0000","message":"成功","data":data,"timestamp":stamp}
    err=lambda code,data:{"code":code,"message":"合成拒絕","data":data,"timestamp":stamp}
    objects={"CompletionConfirmationSummary":summary,"ManagementConfirmationFact":fact,"ManagementConfirmationPolicy":confirm_policy,"CompletionConfirmationDetail":pending,"ConfirmCompletionRequest":confirm_command,"CompletionConfirmationResult":confirm_result,"ClosureSummary":closure_summary,"ClosureCounts":counts,"ClosureRequirements":requirements,"OrderClosurePolicyView":close_policy,"OrderClosureWitness":witness,"OrderClosureView":order,"ClosureTaskReview":review,"CloseOrderRequest":close_command,"OrderClosureResult":close_result,"ManagerActor":actor}
    positives=[(schema,value) for schema,value in objects.items()]
    positives += [("CompletionConfirmationDetail",confirmed),("OrderClosureView",recorded),("OrderClosureView",legacy),("OrderClosureView",{**legacy,"acceptanceStatus":"DRAFT"}),("ClosureTaskReview",waiting),("ClosureTaskReview",{**review,"requiredTask":None}),("ManagementConfirmationFact",{**fact,"actor":{**actor,"role":"DISPATCHER"}}),("ManagementConfirmationFact",{**fact,"reason":"\u0000"}),("ManagementConfirmationFact",{**fact,"reason":"🚚"*1000}),("ManagementConfirmationFact",{**fact,"actor":{**actor,"displayName":"合"*80}}),("ConfirmCompletionRequest",{**confirm_command,"expectedVersion":Decimal('0.0')}),("ConfirmCompletionRequest",{**confirm_command,"expectedVersion":Decimal('1e0')}),("ConfirmCompletionRequest",{**confirm_command,"expectedVersion":9007199254740991}),("CloseOrderRequest",{**close_command,"expectedVersion":Decimal('1.0')}),("CloseOrderRequest",{**close_command,"expectedVersion":9007199254740991}),("OrderClosureWitness",{**witness,"sourceVersion":9007199254740990,"version":9007199254740991}),("IdempotencyReplayed","true"),("IdempotencyReplayed","false")]
    for phase,version in [("WAITING",0),("DEPARTED",1),("IN_PROGRESS",2),("AWAITING_COMPLETION",3)]:
        positives.append(("ClosureTaskReview",{**waiting,"assignmentId":uid(1),"executionPhase":phase,"executionVersion":version,"effectiveOccupationCount":3}))
    for blocker in ["POLICY_NOT_CONFIRMED","ORDER_NOT_OPEN","SOURCE_VERSION_MISMATCH","UNRESOLVED_EXCEPTIONS","PROOF_INVALID"]:
        positives.append(("CompletionConfirmationDetail",{**pending,"policy":{"mode":"SYNTHETIC_TEST_ONLY","canConfirm":False,"blocker":blocker}}))
    positives.append(("CompletionConfirmationDetail",{**pending,"policy":{"mode":"UNCONFIRMED","canConfirm":False,"blocker":"POLICY_NOT_CONFIRMED"}}))
    for blocker in ["POLICY_NOT_CONFIRMED","ORDER_NOT_OPEN","ORDER_NOT_ACCEPTED","TASKS_INCOMPLETE","CONFIRMATION_REQUIRED","UNRESOLVED_EXCEPTIONS","RESOURCES_OCCUPIED","PROOF_INVALID"]:
        positives.append(("OrderClosureView",{**order,"policy":{"mode":"SYNTHETIC_TEST_ONLY","canClose":False,"blocker":blocker,"requirements":None}}))
    positives.append(("OrderClosureView",{**order,"version":9007199254740991,"counts":{**counts,"sourceOrderVersion":9007199254740991},"policy":{"mode":"SYNTHETIC_TEST_ONLY","canClose":False,"blocker":"PROOF_INVALID","requirements":None}}))
    positives.append(("OrderClosureView",{**order,"policy":{"mode":"UNCONFIRMED","canClose":False,"blocker":"POLICY_NOT_CONFIRMED","requirements":None}}))
    for life,accepted in [("OPEN","DRAFT"),("CANCELLED","DRAFT"),("CANCELLED","ACCEPTED")]:
        positives.append(("OrderClosureView",{**order,"lifecycleStatus":life,"acceptanceStatus":accepted,"counts":None,"policy":{"mode":"UNCONFIRMED","canClose":False,"blocker":"POLICY_NOT_CONFIRMED","requirements":None}}))
    for audit,version,closedAt in [("RECORDED",2,stamp),("LEGACY_CLOSED",0,None)]:
        positives.append(("ClosureSummary",{**closure_summary,"lifecycleStatus":"CLOSED","auditState":audit,"version":version,"closedAt":closedAt}))
    envelopes={"CompletionConfirmationSummaryPageEnvelope":("CompletionConfirmationSummaryPage",page(summary)),"CompletionConfirmationDetailEnvelope":("CompletionConfirmationDetail",pending),"ConfirmCompletionEnvelope":("CompletionConfirmationResult",confirm_result),"ManagementConfirmationHistoryPageEnvelope":("ManagementConfirmationHistoryPage",page(fact)),"ClosureSummaryPageEnvelope":("ClosureSummaryPage",page(closure_summary)),"OrderClosureEnvelope":("OrderClosureView",recorded),"ClosureTaskReviewPageEnvelope":("ClosureTaskReviewPage",page(review)),"OrderClosureHistoryPageEnvelope":("OrderClosureHistoryPage",page(witness)),"CloseOrderEnvelope":("OrderClosureResult",close_result)}
    for envelope,(schema,value) in envelopes.items():positives.append((envelope,env(value)))
    for envelope,(schema,value) in envelopes.items():
        if schema.endswith('Page'):
            positives.extend([(schema,value),(schema,{"content":[],"page":100000,"size":100,"totalElements":0,"totalPages":0})])
    for code in ["E0401","E0403","E0404","E0405","E0413","E0415","E0500"]:positives.append(("ErrorEnvelope",err(code,None)))
    positives += [("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{"confirmClosure":"請明確確認"}})),("ErrorEnvelope",err("E0403",{"reason":"CSRF_INVALID"})),("ErrorEnvelope",err("E0403",{"reason":"FORBIDDEN"}))]
    for code,reason in BUSINESS_ERRORS.items():positives.append(("ErrorEnvelope",err(code,{"reason":reason,**({"actualOrderVersion":Decimal('2.0')} if code=="E2101" else {})})))
    positives.append(("ErrorEnvelope",err("E2101",{"reason":"VERSION_CONFLICT","actualConfirmationVersion":1})))
    negatives=[]
    def change(schema,value,path,replacement=None,remove=False):
        changed=copy.deepcopy(value);current=changed
        for field in path[:-1]:current=current[field]
        if remove:del current[path[-1]]
        else:current[path[-1]]=replacement
        negatives.append((schema,changed))
    for schema,value in objects.items():
        for field in value:change(schema,value,[field],remove=True)
        negatives.extend([(schema,None),(schema,[]),(schema,{**value,"unknown":None})])
    for envelope,(schema,value) in envelopes.items():
        wrapped=env(value)
        for field in wrapped:change(envelope,wrapped,[field],remove=True)
        for field,bad in [("code","OK"),("traceId","forbidden"),("data",None),("timestamp","2026-11-26T02:00:00Z"),("timestamp","2026-02-30T10:00:00+08:00")]:change(envelope,wrapped,[field],bad)
        if schema.endswith('Page'):
            for field,bad in [("page",-1),("page",100001),("size",0),("size",101),("totalPages",2),("totalElements",0),("content",[])]:change(schema,value,[field],bad)
    for schema,value in [("ConfirmCompletionRequest",confirm_command),("CloseOrderRequest",close_command)]:
        for bad in [True,"1",-1,Decimal('1.1'),Decimal('9007199254740991.1'),9007199254740992]:change(schema,value,["expectedVersion"],bad)
        flag='confirmCompletion' if schema=='ConfirmCompletionRequest' else 'confirmClosure'
        for bad in [False,None,1,"true"]:change(schema,value,[flag],bad)
        for bad in [""," \t\n\u00a0\ufeff","\u2003\u2028","合"*1001,None,42,"\ud800"]:change(schema,value,["reason"],bad)
        for field in ["actorId","companyScope","waive","forceRelease","requiredTask","expectedExecutionVersion","actualOrderVersion","expectedProofDigest","proofDigest","policyMode","completionIds"]:change(schema,value,[field],"forbidden")
    for schema,value in [("ManagementConfirmationFact",fact),("OrderClosureWitness",witness)]:
        for field in ["accountId","actorId","driverId","resourceId","plateNo","storageKey","storagePath","filename","proofDigest","base64","bytes"]:change(schema,value,[field],"forbidden")
        for field,bad in [("role","DRIVER"),("role","OTHER"),("displayName",""),("displayName","合"*81),("accountId",uid(30))]:change(schema,value,["actor",field],bad)
        for field,bad in [("policyMode","UNCONFIRMED"),("reason"," "),("id",uid(4).upper()),("confirmedAt" if schema=='ManagementConfirmationFact' else 'closedAt',"2026-02-30T00:00:00Z")]:change(schema,value,[field],bad)
    for field,bad in [("version",1),("state","CONFIRMED"),("confirmedAt",stamp),("sourceOrderVersion",0),("completionId",uid(3).upper()),("taskNo","合"*101),("orderNo"," \n ")]:change('CompletionConfirmationSummary',summary,[field],bad)
    change('ManagementConfirmationFact',fact,['version'],0)
    for field,bad in [("confirmation",fact),("completion",None),("execution",{"phase":"AWAITING_COMPLETION","version":3,"departedAt":stamp})]:change('CompletionConfirmationDetail',pending,[field],bad)
    for path,bad in [(['completion','id'],uid(30)),(['completion','completedAt'],'2026-11-26T10:00:01+08:00'),(['completion','releasedAt'],'2026-11-26T10:00:01+08:00'),(['confirmation','confirmedAt'],'2026-11-26T10:00:01+08:00'),(['policy','blocker'],'POLICY_NOT_CONFIRMED'),(['confirmation'],None)]:change('CompletionConfirmationDetail',confirmed,path,bad)
    for phase,version in [('WAITING',0),('DEPARTED',1),('IN_PROGRESS',2),('AWAITING_COMPLETION',3)]:change('CompletedExecution',execution,['phase'],phase)
    change('CompletedExecution',execution,['version'],3);change('CompletedExecution',execution,['departedAt'],None)
    for field,bad in [('mode','UNCONFIRMED'),('canConfirm',False),('blocker','ALREADY_CONFIRMED'),('blocker','UNKNOWN')]:change('ManagementConfirmationPolicy',confirm_policy,[field],bad)
    for field,bad in [('mode','UNCONFIRMED'),('canClose',False),('blocker','POLICY_NOT_CONFIRMED'),('requirements',None),('requirements',{}),('proofDigest','f'*64)]:change('OrderClosurePolicyView',close_policy,[field],bad)
    blocked={'mode':'UNCONFIRMED','canClose':False,'blocker':'POLICY_NOT_CONFIRMED','requirements':None}
    for field,bad in [('canClose',True),('blocker',None),('blocker','TASKS_INCOMPLETE'),('requirements',requirements),('requirements',[])]:change('OrderClosurePolicyView',blocked,[field],bad)
    for field,bad in [('taskBasis','REQUIRED_ONLY'),('requiresManagerConfirmation',False),('requiresNoBlockingExceptions',False),('requiresNoEffectiveOccupations',False)]:change('ClosureRequirements',requirements,[field],bad)
    for field,bad in [('sourceVersion',2),('version',3),('version',1),('managerConfirmed',False),('taskCount',0)]:change('OrderClosureWitness',witness,[field],bad)
    for field,bad in [('taskCount',0),('completedTaskCount',0),('confirmedTaskCount',0),('blockingExceptionCount',1),('effectiveOccupationCount',1),('sourceOrderVersion',2)]:change('OrderClosureView',order,['counts',field],bad)
    for field,bad in [('lifecycleStatus','CLOSED'),('acceptanceStatus','DRAFT'),('closure',witness),('counts',None),('version',9007199254740991)]:change('OrderClosureView',order,[field],bad)
    for field,bad in [('counts',counts),('closure',None),('auditState','LEGACY_CLOSED'),('lifecycleStatus','OPEN'),('acceptanceStatus','DRAFT'),('version',3)]:change('OrderClosureView',recorded,[field],bad)
    change('OrderClosureView',recorded,['policy','blocker'],'POLICY_NOT_CONFIRMED')
    for field,bad in [('closure',witness),('counts',counts),('lifecycleStatus','OPEN')]:change('OrderClosureView',legacy,[field],bad)
    for field,bad in [('confirmedTaskCount',2),('completedTaskCount',2)]:change('ClosureCounts',counts,[field],bad)
    for field,bad in [('closedAt',stamp),('auditState','LEGACY_CLOSED'),('lifecycleStatus','CLOSED')]:change('ClosureSummary',closure_summary,[field],bad)
    for field,bad in [('requiredTask',False),('requiredTask','true'),('sourceOrderVersion',0),('orderId',uid(5).upper()),('assignmentId',None),('completionId',None),('executionPhase','AWAITING_COMPLETION'),('executionVersion',3),('completionVerified',False),('confirmationVersion',2),('confirmedAt',None)]:change('ClosureTaskReview',review,[field],bad)
    for field,bad in [('executionVersion',1),('completionVerified',True),('completionId',uid(3)),('confirmationVersion',1),('confirmedAt',stamp),('executionPhase','DEPARTED')]:change('ClosureTaskReview',waiting,[field],bad)
    for schema,value in [('CompletionConfirmationResult',confirm_result),('OrderClosureResult',close_result)]:
        change(schema,value,['version'],0)
        for field in ['traceId','execution','resources','photos','proofDigest','actualVersion']:change(schema,value,[field],'forbidden')
    change('OrderClosureResult',close_result,['version'],3);change('OrderClosureResult',close_result,['lifecycleStatus'],'OPEN');change('OrderClosureResult',close_result,['acceptanceStatus'],'DRAFT')
    for value in [True,'TRUE','false\n']:negatives.append(('IdempotencyReplayed',value))
    for code in ['E1601','E1701','E1801','E1901','E2001','E2111']:negatives.append(('ErrorEnvelope',err(code,None)))
    for code in ['E0401','E0404','E0405','E0413','E0415','E0500']:negatives.append(('ErrorEnvelope',err(code,{'reason':'FORBIDDEN'})))
    for data in [{},{'reason':'UNKNOWN'},{'reason':'FORBIDDEN','actualOrderVersion':1},{'reason':'CSRF_INVALID','fields':{'request':'合成'}}]:negatives.append(('ErrorEnvelope',err('E0403',data)))
    for code,reason in BUSINESS_ERRORS.items():
        value=err(code,{'reason':reason,**({'actualOrderVersion':2} if code=='E2101' else {})})
        change('ErrorEnvelope',value,['data'],None);change('ErrorEnvelope',value,['data','reason'],'OTHER_REASON')
        for field in ['actualVersion','actualExecutionVersion','actualGeneration','accountId','storagePath','proofDigest']:change('ErrorEnvelope',value,['data',field],'forbidden')
        if code!='E2101':
            for field in ['actualOrderVersion','actualConfirmationVersion','fields']:change('ErrorEnvelope',value,['data',field],1)
    for data in [{'reason':'VERSION_CONFLICT'},{'reason':'VERSION_CONFLICT','actualOrderVersion':2,'actualConfirmationVersion':1},{'reason':'VERSION_CONFLICT','actualOrderVersion':2,'fields':{'request':'合成'}},{'reason':'VERSION_CONFLICT','actualConfirmationVersion':2},{'reason':'VERSION_CONFLICT','actualConfirmationVersion':True},{'reason':'VERSION_CONFLICT','actualOrderVersion':Decimal('2.1')},{'reason':'VERSION_CONFLICT','actualOrderVersion':9007199254740992}]:negatives.append(('ErrorEnvelope',err('E2101',data)))
    for data in [{'reason':'VALIDATION_ERROR','fields':{}},{'reason':'VALIDATION_ERROR','fields':{'reason':4}},{'reason':'VALIDATION_ERROR','fields':{'reason':'合成'},'actualOrderVersion':2}]:negatives.append(('ErrorEnvelope',err('E0400',data)))
    return positives,negatives


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
        fail('Closure reference rejection self-check failed')
    print(f'Closure static contract: 9 operations, 49 schemas, 8 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed')


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
        fail("No synthetic closure HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic closure response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic closure response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"Closure response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual closure HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual closure HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
