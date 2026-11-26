"""Validate the frozen M4-04 completion/effective release contract and sanitized HTTP evidence without network access."""
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
SPEC_PATH = ROOT / "docs/06_api/tms-completion-openapi.yaml"
SCHEMA_URI = "urn:xk:tms:completion:0.1.0"
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SOURCE_PATHS = {
    "docs/06_api/openapi.yaml", "docs/06_api/order-draft-access-policy.json",
    "docs/06_api/order-draft-contract-examples.json", "docs/05_database/first-slice-fields.csv",
}
DEFAULT_RESPONSES = "test-results/**/completion-contract-responses.json"
PRESERVED_SPECS = {
    "tms-execution-openapi.yaml": ("b260dd0a814f11d1dc28b874f79f4280d4edce2b9bc5ac75ca0d4f3f7bbc51d4"),
    "tms-progress-evidence-openapi.yaml": ("fd062dd540f657dd48a172a8955ada62aa7dd80e8a0faa805d3e91dd3efbc791"),
    "tms-exceptions-openapi.yaml": ("34ac767897b8aa56cd121b80f17abc983a4133e51e89b0d1840b9d885feed965"),
}
COPIED_SCHEMA_SOURCES = {
    "tms-execution-openapi.yaml": {"Uuid","Version","Timestamp","CompanyTimestamp","SourceDateTime","NullableSourceDateTime","NullableText","NullableAddress","NullableDecimal","PolicyMode","ItemSnapshot","SiteContact","ExecutionPhase","ExecutionView","IdempotencyReplayed"},
    "tms-progress-evidence-openapi.yaml": {"CanonicalUuid","EvidenceIds"},
}
COPIED_SCHEMAS = set().union(*COPIED_SCHEMA_SOURCES.values())
SCHEMAS = COPIED_SCHEMAS | {"CanonicalEvidenceIds","CompletionWitness","CompletionDetail","CompletionResult","CompleteRequest","CompletionRequirements","CompletionPolicyView","ErrorData","ErrorEnvelope","CompletionDetailEnvelope","CompletionPolicyEnvelope","CompleteMyTaskEnvelope"}
RESPONSE_SCHEMAS = {"CompletionDetailEnvelope","CompletionPolicyEnvelope","CompleteMyTaskEnvelope","ErrorEnvelope"}
BASE = "/tms/my-tasks/{assignmentId}"
OPERATIONS = {
    (BASE+"/completion","get"): ("getMyCompletion","CompletionDetailEnvelope"),
    (BASE+"/completion-policy","get"): ("getMyCompletionPolicy","CompletionPolicyEnvelope"),
    (BASE+"/complete","post"): ("completeMyTask","CompleteMyTaskEnvelope"),
}
BUSINESS_ERRORS = {"E2001":"VERSION_CONFLICT","E2002":"INVALID_STATE","E2003":"POLICY_NOT_CONFIRMED","E2004":"IDEMPOTENCY_KEY_REUSED","E2005":"IDEMPOTENCY_IN_PROGRESS","E2006":"PROTECTED_RELATION","E2007":"EVIDENCE_REQUIRED","E2008":"EVIDENCE_INVALID","E2009":"UNRESOLVED_EXCEPTIONS"}
ERROR_STATUSES = {"400","401","403","404","405","409","413","415","500"}
SAFE14_FIELDS = {"assignmentId","taskId","taskNo","item","taskKind","pickupName","pickupAddress","deliveryName","deliveryAddress","siteContact","plannedStartAt","plannedEndAt","assignedAt","policyMode"}
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


def canonical_uuid_uniqueness(validator, enabled, value, schema):
    if enabled and isinstance(value, list) and all(isinstance(item, str) for item in value):
        normalized = [item.lower() for item in value]
        if len(normalized) != len(set(normalized)):
            yield jsonschema.ValidationError("UUID set contains a duplicate canonical identity")


def canonical_uuid_order(validator, enabled, value, schema):
    if enabled and isinstance(value,list) and all(isinstance(item,str) for item in value) and value != sorted(value):
        yield jsonschema.ValidationError("Canonical UUID set must be sorted")


def equal_timestamp_fields(validator, fields, value, schema):
    if isinstance(value,dict) and all(name in value and isinstance(value[name],str) for name in fields):
        if len({value[name] for name in fields}) != 1:
            yield jsonschema.ValidationError("Completion and release timestamps must match")


ExactValidator = validators.extend(
    jsonschema.Draft202012Validator,
    validators={"x-uniqueCanonicalUuids":canonical_uuid_uniqueness,"x-canonicalUuidOrder":canonical_uuid_order,"x-equalTimestampFields":equal_timestamp_fields},
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
                    fail("Completion specification contains a forbidden reference; retrieval is disabled")
                target = spec
                for part in reference[2:].split("/"):
                    part = part.replace("~1", "/").replace("~0", "~")
                    if not isinstance(target, dict) or part not in target:
                        fail("Completion specification contains an unresolved local reference")
                    target = target[part]
            if "$id" in value:
                fail("Completion specification must not redefine an in-memory resource identity")
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
                fail("Frozen prior execution/progress/exception OpenAPI source changed")
            originals[name]=yaml.safe_load(data.decode("utf-8"))
        spec=yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError,UnicodeError,yaml.YAMLError):
        fail("Cannot read independent completion specification")
    if not isinstance(spec,dict) or spec.get("openapi")!="3.1.0":
        fail("Expected independent completion OpenAPI 3.1.0")
    local_references_only(spec)
    try:
        schemas=spec["components"]["schemas"]
        operations={(path,method):op for path,item in spec["paths"].items() for method,op in item.items() if method in HTTP_METHODS}
        if set(schemas)!=SCHEMAS or set(operations)!=set(OPERATIONS):
            fail("Expected exactly three M404 operations and twenty-nine schemas")
        if spec.get("x-preservedSpecSha256")!=PRESERVED_SPECS:
            fail("Completion preserved spec pin metadata differs")
        if any(schemas[name]!=originals[source]["components"]["schemas"][name] for source,names in COPIED_SCHEMA_SOURCES.items() for name in names):
            fail("Completion copied safe-source schemas differ")
        original_detail=originals["tms-execution-openapi.yaml"]["components"]["schemas"]["MyTaskExecutionDetail"]
        detail=schemas["CompletionDetail"]
        if set(detail["properties"])!=SAFE14_FIELDS|{"execution","completion"} or set(detail["required"])!=set(detail["properties"]) or detail.get("additionalProperties") is not False:
            fail("Completion detail must contain exactly original safe14 plus execution and completion")
        for field in SAFE14_FIELDS:
            expected={"$ref":"#/components/schemas/CanonicalUuid"} if field in {"assignmentId","taskId"} else original_detail["properties"][field]
            if detail["properties"][field]!=expected:
                fail("Completion original safe14 source field semantics differ")
        old=originals["tms-progress-evidence-openapi.yaml"]
        if spec["components"]["securitySchemes"]!=old["components"]["securitySchemes"]:
            fail("Completion Session/CSRF schemes differ")
        if spec.get("x-syntheticOnlyDecision")!={"database":"xk_logistics_e2e","evidenceBasis":"LATEST_AWAITING_TRANSITION","minimumCount":1,"allowedMediaTypes":["image/png"],"maxBytes":65536,"maxPixels":4096,"requiresSafeReleaseConfirmation":True,"formalSafetyApproval":False}:
            fail("Completion isolated synthetic decision must not become formal safety approval")
        if spec.get("x-effectiveOccupation")!="released_at IS NULL AND completion_id IS NULL" or spec.get("x-retainLegacy14RecordedMembership") is not True or spec.get("x-requiredSqlSeals")!=["reservation-after-completion-insert-refused-even-null-marker","readiness-selected-set-sealed-by-original-progress-success-receipt"]:
            fail("Completion legacy-record/effective-occupation/sealed-proof declarations differ")
        for (path,method),op in operations.items():
            identity,response=OPERATIONS[(path,method)];writing=method=="post";security={"sessionCookie":[]}
            if writing:security["csrfToken"]=[]
            if op.get("operationId")!=identity or op.get("x-allowedRoles")!=["DRIVER"] or op.get("x-deniedRoles")!=["ADMIN","DISPATCHER"] or op.get("security")!=[security]:
                fail("Completion operation/role/Session/CSRF contract differs")
            if op.get("x-rejectUnknownQueryParameters") is not True or op.get("x-rejectDuplicateQueryParameters") is not True:
                fail("Completion query rejection differs")
            params=[resolve(spec,value) for value in op["parameters"]];pairs=[(v["in"],v["name"]) for v in params]
            expected={("path","assignmentId")}
            if writing:expected|={("header","X-CSRF-TOKEN"),("header","Idempotency-Key")}
            if len(pairs)!=len(expected) or set(pairs)!=expected or any(v.get("required") is not True for v in params):
                fail("Completion exact path/header/no-query declarations differ")
            responses=op["responses"]
            if set(responses)!=ERROR_STATUSES|{"200"}:fail("Completion HTTP statuses differ")
            success=resolve(spec,responses["200"])
            if set(success["content"])!={"application/json"} or success["content"]["application/json"]["schema"]!={"$ref":f"#/components/schemas/{response}"} or not private_header(success["headers"]):
                fail("Completion success media/schema/privacy differs")
            if writing:
                request=op["requestBody"];replay=success["headers"]["Idempotency-Replayed"]
                if request.get("required") is not True or set(request["content"])!={"application/json"} or request["content"]["application/json"]["schema"]!={"$ref":"#/components/schemas/CompleteRequest"} or replay!={"required":True,"schema":{"$ref":"#/components/schemas/IdempotencyReplayed"}}:
                    fail("Completion command body/replay declarations differ")
                if op.get("x-maxStreamBytes")!=16384 or op.get("x-postLedgerMissErrors")!=["E2001","E2002","E2003","E2007","E2008","E2009"] or not op.get("x-replayRule") or not op.get("x-versionRule"):
                    fail("Completion stream/version/post-ledger-miss declarations differ")
            elif "requestBody" in op:fail("Completion GET must not declare a body")
            for status in ERROR_STATUSES:
                error=resolve(spec,responses[status])
                if error["content"]["application/json"]["schema"]!={"$ref":"#/components/schemas/ErrorEnvelope"} or not private_header(error["headers"]):
                    fail("Completion error envelope/privacy differs")
        for schema in schemas.values():jsonschema.Draft202012Validator.check_schema(schema)
    except (KeyError,TypeError,AttributeError,jsonschema.SchemaError):
        fail("Completion schema metadata or operation structure invalid")
    registry=Registry(retrieve=deny_remote).with_resource(SCHEMA_URI,Resource.from_contents(spec,default_specification=DRAFT202012))
    return {name:ExactValidator({"$ref":f"{SCHEMA_URI}#/components/schemas/{name}"},registry=registry,format_checker=CHECKER) for name in schemas}


def report_error(label, schema, error):
    pointer = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in error.path)
    # Never print error.message, instance values, request bodies, cookies or headers.
    fail(f"{label}: {schema} failed {error.validator} at {pointer}")


def static_fixtures():
    uid=lambda n:f"a0000000-0000-4000-8000-{n:012x}"
    stamp="2026-11-26T10:00:00.123456+08:00"
    item={"itemKind":"CARGO","containerType":None,"containerNo":None,"packageQty":7,"grossWeight":"0.0000000000000000001","cbm":"0.00"}
    view=lambda phase,version:{"phase":phase,"version":version,"departedAt":None if phase=="WAITING" else stamp}
    witness={"id":uid(3),"completedAt":stamp,"releasedAt":stamp,"policyMode":"SYNTHETIC_TEST_ONLY","safeReleaseConfirmed":True}
    detail={"assignmentId":uid(1),"taskId":uid(2),"taskNo":"TSK-合成完成","item":item,"taskKind":" 合成 e\u0301 ","pickupName":" 合成\u0000原值 ","pickupAddress":"合成\u0000原始地址\r\n第二行","deliveryName":"合成終點","deliveryAddress":"原值地址","siteContact":{"name":None,"phone":None},"plannedStartAt":"1500-01-02T08:00:00.123456+23:59","plannedEndAt":None,"assignedAt":stamp,"policyMode":"SYNTHETIC_TEST_ONLY","execution":view("AWAITING_COMPLETION",3),"completion":None}
    completed={**detail,"execution":view("COMPLETED",4),"completion":witness}
    result={"assignmentId":uid(1),"taskId":uid(2),"execution":view("COMPLETED",4),"completion":witness}
    rules={"evidenceBasis":"LATEST_AWAITING_TRANSITION","minimumCount":1,"allowedMediaTypes":["image/png"],"maxBytes":65536,"maxPixels":4096,"requiresSafeReleaseConfirmation":True}
    policy={"assignmentId":uid(1),"taskId":uid(2),"execution":view("AWAITING_COMPLETION",3),"mode":"SYNTHETIC_TEST_ONLY","canComplete":True,"blocker":None,"requirements":rules,"requiredEvidenceIds":[uid(10),uid(11)]}
    command={"expectedVersion":3,"evidenceIds":[uid(10),uid(11)],"confirmSafeRelease":True}
    env=lambda data:{"code":"0000","message":"成功","data":data,"timestamp":stamp}
    err=lambda code,data:{"code":code,"message":"合成拒絕","data":data,"timestamp":stamp}
    positive=[("CompletionDetailEnvelope",env(detail)),("CompletionDetailEnvelope",env(completed)),("CompletionPolicyEnvelope",env(policy)),("CompleteMyTaskEnvelope",env(result)),("CompleteRequest",command),("CompleteRequest",{**command,"expectedVersion":Decimal('3.0')}),("CompleteRequest",{**command,"expectedVersion":Decimal('3e0'),"evidenceIds":[uid(11).upper(),uid(10)]}),("CompleteRequest",{**command,"expectedVersion":9007199254740991,"evidenceIds":[]}),("CompletionResult",{**result,"execution":view("COMPLETED",9007199254740991)}),("CompletionDetail",{**detail,"siteContact":None}),("CompletionDetail",{**detail,"siteContact":{"name":"","phone":None},"taskKind":"","pickupName":" \u0000 "}),("CompletionDetail",{**detail,"item":{**item,"packageQty":None,"grossWeight":None,"cbm":None}}),("CompletionDetail",{**detail,"item":{**item,"itemKind":"CONTAINER","containerType":" 合成櫃型 ","containerNo":"合成\u0000非實櫃號"}}),("CompletionDetail",{**detail,"plannedStartAt":"0001-01-01T00:00:00Z","plannedEndAt":"9999-12-31T23:59:59.999999-23:59"}),("CompletionDetail",{**detail,"policyMode":"UNCONFIRMED"}),("CanonicalEvidenceIds",[uid(n) for n in range(32)]),("IdempotencyReplayed","true"),("IdempotencyReplayed","false")]
    for phase,version in [("WAITING",0),("DEPARTED",1),("IN_PROGRESS",2),("AWAITING_COMPLETION",3)]:
        current=view(phase,version)
        positive.append(("CompletionDetail",{**detail,"execution":current}))
        for mode in ["UNCONFIRMED","SYNTHETIC_TEST_ONLY"]:
            positive.append(("CompletionPolicyView",{**policy,"execution":current,"mode":mode,"canComplete":False,"blocker":"POLICY_NOT_CONFIRMED","requirements":None,"requiredEvidenceIds":None}))
        if phase!="AWAITING_COMPLETION":positive.append(("CompletionPolicyView",{**policy,"execution":current,"canComplete":False,"blocker":"UNSUPPORTED_PHASE","requirements":None,"requiredEvidenceIds":None}))
    for mode in ["UNCONFIRMED","SYNTHETIC_TEST_ONLY"]:positive.append(("CompletionPolicyView",{**policy,"execution":view("COMPLETED",4),"mode":mode,"canComplete":False,"blocker":"ALREADY_COMPLETED","requirements":None,"requiredEvidenceIds":None}))
    for blocker in ["UNRESOLVED_EXCEPTIONS","EVIDENCE_REQUIRED","EVIDENCE_INVALID"]:positive.append(("CompletionPolicyView",{**policy,"canComplete":False,"blocker":blocker,"requirements":None,"requiredEvidenceIds":None}))
    for code in ["E0401","E0403","E0404","E0405","E0413","E0415","E0500"]:positive.append(("ErrorEnvelope",err(code,None)))
    positive += [("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{"confirmSafeRelease":"請明確確認"}})),("ErrorEnvelope",err("E0403",{"reason":"CSRF_INVALID"})),("ErrorEnvelope",err("E0403",{"reason":"FORBIDDEN"}))]
    for code,reason in BUSINESS_ERRORS.items():positive.append(("ErrorEnvelope",err(code,{"reason":reason,**({"actualVersion":Decimal('4.0')} if code=="E2001" else {})})))
    negative=[]
    def change(schema,value,path,replacement=None,remove=False):
        altered=copy.deepcopy(value);cursor=altered
        for field in path[:-1]:cursor=cursor[field]
        if remove:del cursor[path[-1]]
        else:cursor[path[-1]]=replacement
        negative.append((schema,altered))
    for schema,value in [("CompletionDetail",detail),("CompletionWitness",witness),("CompletionResult",result),("CompleteRequest",command),("CompletionRequirements",rules),("CompletionPolicyView",policy),("ItemSnapshot",item),("ExecutionView",view("AWAITING_COMPLETION",3))]:
        for field in value:change(schema,value,[field],remove=True)
        negative += [(schema,None),(schema,[]),(schema,{**value,"unknown":None})]
    for name,data in [("CompletionDetailEnvelope",completed),("CompletionPolicyEnvelope",policy),("CompleteMyTaskEnvelope",result)]:
        for field in ["code","message","data","timestamp"]:change(name,env(data),[field],remove=True)
        for field,value in [("code","OK"),("traceId","forbidden"),("data",None),("timestamp","2026-11-26T02:00:00Z"),("timestamp","2026-02-30T10:00:00+08:00")]:change(name,env(data),[field],value)
    for schema,value in [("CompletionDetail",completed),("CompletionWitness",witness),("CompletionResult",result)]:
        for field in ["orderId","orderNo","customerId","customerName","driverId","accountId","resourceId","mainVehicleId","trailerId","plateNo","actorId","events","history","reservations","completion_id","generation","assignmentVersion","photos","uploadUrl","storagePath","storageKey","filename","bytes","base64"]:change(schema,value,[field],"forbidden")
    for field in ["actorId","driverId","taskId","assignmentId","policyMode","waiveEvidence","expectedGeneration","expectedExceptionVersion","expectedExecutionVersion","releasedAt","completedAt"]:change("CompleteRequest",command,[field],"forbidden")
    for value in [True,"3",-1,Decimal('3.1'),Decimal('9007199254740991.1'),9007199254740992]:change("CompleteRequest",command,["expectedVersion"],value)
    for value in [False,None,1,"true"]:change("CompleteRequest",command,["confirmSafeRelease"],value)
    for value in [[uid(10),uid(10)],[uid(10),uid(10).upper()],[uid(n) for n in range(33)],[None],["bad-uuid"],None]:change("CompleteRequest",command,["evidenceIds"],value)
    for value in [[],[uid(11),uid(10)],[uid(10).upper()],[uid(10),uid(10)],[uid(n) for n in range(33)],None]:negative.append(("CanonicalEvidenceIds",value))
    for field in ["id","assignmentId","taskId"]:change("CompletionDetail",detail,[field],uid(1).upper())
    for field,value in [("policyMode","UNCONFIRMED"),("safeReleaseConfirmed",False),("completedAt",None),("releasedAt",None),("releasedAt","2026-11-26T10:00:01.123456+08:00"),("completedAt","2026-02-30T10:00:00+08:00")]:change("CompletionWitness",witness,[field],value)
    change("CompletionDetail",detail,["completion"],witness);change("CompletionDetail",completed,["completion"],None)
    for phase,version in [("WAITING",0),("DEPARTED",1),("IN_PROGRESS",2),("AWAITING_COMPLETION",3)]:change("CompletionResult",result,["execution"],view(phase,version))
    for version in [0,1,2,3,True,Decimal('4.1'),9007199254740992]:change("CompletionResult",result,["execution","version"],version)
    change("CompletionResult",result,["execution","departedAt"],None);change("CompletionDetail",completed,["execution","version"],3)
    for value in ["2026-02-30T00:00:00Z","0000-01-01T00:00:00Z","2026-01-01T00:00:00.1234567Z","2026-01-01T00:00:60Z","2026-01-01T00:00:00+24:00","2026-01-01T00:00:00"]:change("CompletionDetail",detail,["plannedStartAt"],value)
    for value in ["01.00","-1","1e2","1\n","0."+"1"*32,1.0]:change("ItemSnapshot",item,["grossWeight"],value)
    change("ItemSnapshot",item,["packageQty"],0);change("ItemSnapshot",item,["itemKind"],None)
    change("CompletionDetail",detail,["siteContact","phone"],remove=True);change("CompletionDetail",detail,["siteContact","accountId"],uid(99))
    for field,value in [("mode","UNCONFIRMED"),("mode","APPROVED"),("canComplete",False),("blocker","POLICY_NOT_CONFIRMED"),("blocker","ALREADY_COMPLETED"),("blocker","UNKNOWN"),("requirements",None),("requirements",{}),("requirements",[]),("requiredEvidenceIds",None),("requiredEvidenceIds",[])]:change("CompletionPolicyView",policy,[field],value)
    blocked={**policy,"mode":"UNCONFIRMED","canComplete":False,"blocker":"POLICY_NOT_CONFIRMED","requirements":None,"requiredEvidenceIds":None}
    for field,value in [("canComplete",True),("blocker",None),("blocker","UNSUPPORTED_PHASE"),("requirements",rules),("requiredEvidenceIds",[uid(10)]),("requiredEvidenceIds",[])]:change("CompletionPolicyView",blocked,[field],value)
    for phase,version in [("WAITING",0),("DEPARTED",1),("IN_PROGRESS",2),("COMPLETED",4)]:change("CompletionPolicyView",policy,["execution"],view(phase,version))
    done={**blocked,"execution":view("COMPLETED",4),"blocker":"ALREADY_COMPLETED"}
    for field,value in [("blocker","POLICY_NOT_CONFIRMED"),("canComplete",True),("requirements",rules),("requiredEvidenceIds",[uid(10)])]:change("CompletionPolicyView",done,[field],value)
    for blocker in ["UNRESOLVED_EXCEPTIONS","EVIDENCE_REQUIRED","EVIDENCE_INVALID"]:
        value={**policy,"canComplete":False,"blocker":blocker,"requirements":None,"requiredEvidenceIds":None}
        change("CompletionPolicyView",value,["execution"],view("DEPARTED",1));change("CompletionPolicyView",value,["mode"],"UNCONFIRMED")
    for field,value in [("evidenceBasis","ANY_PHOTO"),("minimumCount",0),("minimumCount",2),("minimumCount",True),("allowedMediaTypes",[]),("allowedMediaTypes",["image/jpeg"]),("maxBytes",0),("maxBytes",65537),("maxPixels",4097),("requiresSafeReleaseConfirmation",False)]:change("CompletionRequirements",rules,[field],value)
    for value in [True,"TRUE","false\n"]:negative.append(("IdempotencyReplayed",value))
    for code in ["E1601","E1701","E1801","E1901","E2010"]:negative.append(("ErrorEnvelope",err(code,None)))
    for code in ["E0401","E0404","E0405","E0413","E0415","E0500"]:negative.append(("ErrorEnvelope",err(code,{"actualVersion":1})))
    for data in [{},{"reason":"UNKNOWN"},{"reason":"FORBIDDEN","actualVersion":4},{"reason":"CSRF_INVALID","fields":{"request":"合成"}}]:negative.append(("ErrorEnvelope",err("E0403",data)))
    for code,reason in BUSINESS_ERRORS.items():
        value=err(code,{"reason":reason,**({"actualVersion":4} if code=="E2001" else {})})
        change("ErrorEnvelope",value,["data"],None);change("ErrorEnvelope",value,["data","reason"],"OTHER_REASON")
        for field in ["actualExecutionVersion","actualGeneration","actorId","storagePath"]:change("ErrorEnvelope",value,["data",field],"forbidden")
        if code=="E2001":
            change("ErrorEnvelope",value,["data","actualVersion"],remove=True)
            for invalid in [True,-1,Decimal('4.1'),Decimal('9007199254740991.1'),9007199254740992]:change("ErrorEnvelope",value,["data","actualVersion"],invalid)
    negative.append(("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{}})));negative.append(("ErrorEnvelope",err("E0400",{"reason":"VALIDATION_ERROR","fields":{"request":4}})))
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
        fail('Completion reference rejection self-check failed')
    print(f'Completion static contract: 3 operations, 29 schemas, 7 source hashes, {len(positive)} positive, {len(negative)} negative, {len(forbidden)} reference guards passed')


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
        fail("No synthetic completion HTTP response files matched; validation was not run")
    count = 0
    for path in files:
        try:
            records = json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal,
                                 parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
            require_unicode(records)
        except (OSError, ValueError, UnicodeError, RecursionError, DecimalException):
            fail("Synthetic completion response evidence is not valid UTF-8 JSON")
        if not isinstance(records, list) or not records:
            fail("Synthetic completion response evidence must be a nonempty array")
        for index, record in enumerate(records):
            if not isinstance(record, dict) or set(record) != {"schema", "value"} or not isinstance(record['schema'], str) or record['schema'] not in RESPONSE_SCHEMAS:
                fail(f"Completion response[{index}] must contain only a known envelope schema and value")
            issue = next(schema_validators[record['schema']].iter_errors(record['value']), None)
            if issue:
                report_error(f"{path.name}[{index}]", record['schema'], issue)
            count += 1
    print(f"Actual completion HTTP responses: {count} passed across {len(files)} files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--responses', nargs='+', help='Glob(s) of sanitized synthetic [{schema,value}] HTTP evidence')
    group.add_argument('--static-only', action='store_true', help='Only validate static schemas/fixtures; explicitly not HTTP acceptance')
    args = parser.parse_args()
    schema_validators = load_contract()
    validate_static(schema_validators)
    if args.static_only:
        print('Actual completion HTTP responses: not run (--static-only)')
        return
    validate_responses(args.responses or [DEFAULT_RESPONSES], schema_validators)


if __name__ == '__main__':
    main()
