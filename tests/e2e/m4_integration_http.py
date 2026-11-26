"""Private stdlib HTTPS side of the M4 integration acceptance drill.

No command executes at import. Credentials, command keys, cookies and PNG bytes
stay in memory. Only the existing public JSON contract buckets are persisted.
The orchestrator, not this module, owns process/DB lifecycle and the deadline.
"""
from __future__ import annotations

import copy
import hashlib
import http.cookiejar
import json
import re
import ssl
import struct
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


class HttpFailure(RuntimeError):
    """A deliberately payload-free failure, suitable for a public summary."""


def _require(condition: bool, stage: str = "response") -> None:
    if not condition:
        raise HttpFailure("Controlled HTTPS " + stage + " verification failed")


def _exact(value: Any, fields: tuple[str, ...] | list[str]) -> None:
    _require(isinstance(value, dict) and set(value) == set(fields), "shape")


def _object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for name, value in pairs:
        if name in result:
            raise HttpFailure("Controlled HTTPS JSON verification failed")
        result[name] = value
    return result


def _noninteger(_: str) -> Any:
    # Every numeric response field used by these fixed contracts is an integer;
    # all domain decimals remain strings. Refuse unexpected floating responses.
    raise HttpFailure("Controlled HTTPS numeric response verification failed")


@dataclass(frozen=True)
class Reply:
    status: int
    payload: dict[str, Any]
    data: Any
    replayed: str | None


_BUCKETS = ("closure", "completion", "exception", "progress-evidence", "execution",
            "tms", "order", "acceptance", "vehicle", "driver")
_UUID = r"[0-9a-fA-F-]{36}"
_SAFE = ("assignmentId", "taskId", "taskNo", "item", "taskKind", "pickupName",
         "pickupAddress", "deliveryName", "deliveryAddress", "siteContact",
         "plannedStartAt", "plannedEndAt", "assignedAt", "policyMode")


class Collector:
    def __init__(self, report_dir: Path):
        self.report_dir = Path(report_dir)
        self._buckets: dict[str, list[dict[str, Any]]] = {key: [] for key in _BUCKETS}

    @staticmethod
    def _destination(method: str, path: str) -> tuple[str, str] | None:
        u = _UUID
        if re.fullmatch(r"/api/v1/tms/completions(?:/.*)?", path):
            schema = ("ManagementConfirmationHistoryPageEnvelope" if path.endswith("/history")
                      else "ConfirmCompletionEnvelope" if path.endswith("/confirm")
                      else "CompletionConfirmationDetailEnvelope" if path.endswith("/confirmation")
                      else "CompletionConfirmationSummaryPageEnvelope")
            return "closure", schema
        if path == "/api/v1/orders/closure-worklist" or re.fullmatch(
                rf"/api/v1/orders/{u}/(?:close|closure(?:/(?:tasks|history))?)", path):
            schema = ("ClosureSummaryPageEnvelope" if path.endswith("/closure-worklist")
                      else "ClosureTaskReviewPageEnvelope" if path.endswith("/tasks")
                      else "OrderClosureHistoryPageEnvelope" if path.endswith("/history")
                      else "CloseOrderEnvelope" if path.endswith("/close") else "OrderClosureEnvelope")
            return "closure", schema
        if re.fullmatch(rf"/api/v1/tms/my-tasks/{u}/(?:completion|completion-policy|complete)", path):
            return "completion", ("CompletionPolicyEnvelope" if path.endswith("/completion-policy")
                                  else "CompleteMyTaskEnvelope" if path.endswith("/complete")
                                  else "CompletionDetailEnvelope")
        manager = re.fullmatch(r"/api/v1/tms/exceptions(?:/.*)?", path)
        own = re.fullmatch(rf"/api/v1/tms/my-tasks/{u}/exceptions(?:/.*)?", path)
        if manager or own:
            if path.endswith("/history"):
                schema = "ManagerExceptionHistoryPageEnvelope" if manager else "DriverExceptionHistoryPageEnvelope"
            elif path.endswith("/content"):
                schema = "ErrorEnvelope"
            elif path.endswith("/evidence"):
                schema = "SupplementUploadEnvelope" if method == "POST" else "SupplementEvidencePageEnvelope"
            elif method == "POST":
                schema = "ExceptionRecordEnvelope"
            elif path == "/api/v1/tms/exceptions" or re.fullmatch(rf"/api/v1/tms/my-tasks/{u}/exceptions", path):
                schema = "ExceptionSummaryPageEnvelope"
            else:
                schema = "ExceptionDetailEnvelope"
            return "exception", schema
        if re.fullmatch(rf"/api/v1/tms/my-tasks/{u}/(?:progress-policy|progress|evidence(?:/{u}/content)?)", path):
            return "progress-evidence", ("ProgressPolicyEnvelope" if path.endswith("/progress-policy")
                                         else "ProgressEnvelope" if path.endswith("/progress")
                                         else "EvidenceEnvelope" if method == "POST" else "EvidencePageEnvelope")
        if re.fullmatch(rf"/api/v1/tms/my-tasks/{u}(?:/depart)?", path):
            return "execution", "DepartMyTaskEnvelope" if path.endswith("/depart") else "MyTaskExecutionEnvelope"
        if re.fullmatch(rf"/api/v1/tms/tasks/{u}/dispatch", path):
            return "tms", "DispatchDetailEnvelope" if method == "GET" else "CommandResultEnvelope"
        if path in ("/api/v1/tms/tasks", "/api/v1/tms/resources", "/api/v1/tms/my-tasks"):
            return "tms", {"/api/v1/tms/tasks": "TaskPageEnvelope", "/api/v1/tms/resources": "ResourcePageEnvelope",
                           "/api/v1/tms/my-tasks": "MyTaskPageEnvelope"}[path]
        if re.fullmatch(rf"/api/v1/tms/dispatches/{u}/(?:revoke|reassign)", path):
            return "tms", "CommandResultEnvelope"
        if re.fullmatch(rf"/api/v1/orders/{u}/accept", path):
            return "acceptance", "AcceptedOrderEnvelope"
        if path == "/api/v1/orders" or re.fullmatch(rf"/api/v1/orders/{u}", path):
            return "order", "OrderPageEnvelope" if path == "/api/v1/orders" and method == "GET" else "OrderEnvelope"
        if path == "/api/v1/fms/vehicles" or re.fullmatch(rf"/api/v1/fms/vehicles/{u}", path):
            return "vehicle", "VehicleEnvelope"
        if path == "/api/v1/fms/drivers" or re.fullmatch(rf"/api/v1/fms/drivers/{u}", path):
            return "driver", "DriverEnvelope"
        return None

    def record(self, method: str, path: str, status: int, payload: dict[str, Any]) -> None:
        destination = self._destination(method, path)
        if destination is not None:
            bucket, schema = destination
            self._buckets[bucket].append({"schema": "ErrorEnvelope" if status >= 400 else schema,
                                          "value": copy.deepcopy(payload)})

    def save(self) -> None:
        self.report_dir.mkdir(parents=True, exist_ok=True)
        for bucket, records in self._buckets.items():
            if records:
                destination = self.report_dir / (bucket + "-contract-responses.json")
                destination.write_text(json.dumps(records, ensure_ascii=True, separators=(",", ":")), encoding="utf-8")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


class Api:
    """A single private browser-like session, CA-verified and never retried."""

    def __init__(self, base_url: str, ca_cert: Path, collector: Collector, remaining: Callable[[], float]):
        origin = urllib.parse.urlsplit(base_url)
        _require(origin.scheme == "https" and origin.hostname in ("localhost", "127.0.0.1")
                 and origin.username is None and origin.password is None and origin.path in ("", "/")
                 and not origin.query and not origin.fragment, "origin")
        self.base_url = base_url.rstrip("/")
        self.ca_cert, self.collector, self.remaining = Path(ca_cert), collector, remaining
        self._csrf: dict[str, str] | None = None
        self._jar = http.cookiejar.CookieJar()
        self._context = ssl.create_default_context(cafile=str(self.ca_cert))
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=self._context), urllib.request.HTTPCookieProcessor(self._jar), _NoRedirect())

    def clone(self) -> Api:
        return Api(self.base_url, self.ca_cert, self.collector, self.remaining)

    def copy_session(self, base_url: str) -> Api:
        result = Api(base_url, self.ca_cert, self.collector, self.remaining)
        for cookie in self._jar:
            result._jar.set_cookie(copy.copy(cookie))
        result._csrf = copy.deepcopy(self._csrf)
        return result

    @staticmethod
    def _path(path: str) -> str:
        _require(isinstance(path, str) and path.startswith("/") and not path.startswith("//")
                 and "\r" not in path and "\n" not in path, "path")
        parsed = urllib.parse.urlsplit(path)
        _require(not parsed.scheme and not parsed.netloc and not parsed.fragment, "path")
        return path if parsed.path.startswith("/api/v1/") else "/api/v1" + path

    def _cookies(self) -> None:
        found = {cookie.name: cookie for cookie in self._jar if cookie.name in ("XKSESSION", "JSESSIONID")}
        _require(set(found) == {"XKSESSION", "JSESSIONID"}, "session")
        _require(found["XKSESSION"].value == found["JSESSIONID"].value, "session")
        for cookie in found.values():
            attributes = {key.lower(): value for key, value in cookie._rest.items()}
            _require(cookie.secure and cookie.path == "/" and "httponly" in attributes
                     and str(attributes.get("samesite", "")).lower() == "lax", "session")

    def _read(self, response: Any, limit: int) -> bytes:
        parts: list[bytes] = []
        size = 0
        while size <= limit:
            _require(self.remaining() > 0, "deadline")
            part = response.read1(min(65536, limit + 1 - size))
            if not part:
                return b"".join(parts)
            parts.append(part)
            size += len(part)
        raise HttpFailure("Controlled HTTPS response limit verification failed")

    def csrf(self) -> dict[str, str]:
        result = self.request("GET", "/auth/csrf")
        _exact(result.data, ("token", "headerName"))
        _require(isinstance(result.data["token"], str) and bool(result.data["token"])
                 and result.data["headerName"] == "X-CSRF-TOKEN", "CSRF")
        self._csrf = dict(result.data)
        self._cookies()
        return dict(self._csrf)

    def request(self, method: str, path: str, body: Any = None, key: str | None = None,
                expected_status: int = 200, expected_code: str = "0000", raw: bytes | None = None,
                headers: dict[str, str] | None = None) -> Reply:
        method, path = method.upper(), self._path(path)
        parsed = urllib.parse.urlsplit(path)
        supplied = dict(headers or {})
        _require(all(isinstance(k, str) and isinstance(v, str) and "\r" not in k + v and "\n" not in k + v
                     for k, v in supplied.items()), "headers")
        lower = {name.lower() for name in supplied}
        _require(not lower.intersection({"cookie", "host", "authorization", "content-length", "connection"}), "headers")
        supplied.setdefault("Accept", "image/png" if parsed.path.endswith("/content") else "application/json")
        if method not in ("GET", "HEAD"):
            if "x-csrf-token" not in lower:
                token = self.csrf()
                supplied[token["headerName"]] = token["token"]
            supplied.setdefault("Content-Type", "application/json")
        if key is not None:
            _require(isinstance(key, str) and str(uuid.UUID(key)) == key, "command key")
            supplied["Idempotency-Key"] = key
        _require(not (raw is not None and body is not None), "body")
        wire = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=True,
                           separators=(",", ":"), allow_nan=False).encode("utf-8"))
        _require(wire is None or isinstance(wire, bytes), "body")
        remaining = self.remaining()
        _require(remaining > 0, "deadline")
        req = urllib.request.Request(self.base_url + path, data=wire, headers=supplied, method=method)
        try:
            try:
                response = self._opener.open(req, timeout=max(0.1, min(15.0, remaining)))
            except urllib.error.HTTPError as failure:
                response = failure
            with response:
                status = response.code
                response_headers = response.headers
                data = self._read(response, 4 * 1024 * 1024)
            _require(len(data) <= 4 * 1024 * 1024, "response limit")
            _require(status == expected_status, "status")
            _require(response_headers.get_content_type() == "application/json", "media")
            payload = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=_object,
                                 parse_float=_noninteger, parse_constant=_noninteger)
            _require(isinstance(payload, dict), "envelope")
            old = expected_code == "OK" or parsed.path == "/api/v1/orders" or bool(re.fullmatch(
                rf"/api/v1/orders/{_UUID}(?:/accept)?", parsed.path))
            _exact(payload, ("code", "message", "data", "traceId", "fieldErrors") if old and status >= 400
                   else ("code", "message", "data", "traceId") if old else ("code", "message", "data", "timestamp"))
            _require(payload["code"] == expected_code and isinstance(payload["message"], str), "code")
            if old:
                _require(isinstance(payload["traceId"], str) and bool(payload["traceId"]), "trace")
                if status >= 400:
                    _require(isinstance(payload["fieldErrors"], list), "field errors")
            else:
                _require(isinstance(payload["timestamp"], str) and payload["timestamp"].endswith("+08:00"), "timestamp")
            replayed = response_headers.get("Idempotency-Replayed")
            _require(replayed in (None, "false", "true"), "replay header")
            if status >= 400:
                _require(replayed is None, "error replay")
            if "/tms/my-tasks/" in parsed.path or "/closure" in parsed.path or "/tms/completions" in parsed.path or parsed.path.endswith("/close"):
                if re.fullmatch(rf"/api/v1/tms/my-tasks/{_UUID}(?:/depart)?", parsed.path):
                    # Original M401 uses Spring Security's no-store response;
                    # later families deliberately specify the private value.
                    directives = response_headers.get("Cache-Control", "").lower().split(",")
                    _require("no-store" in [value.strip() for value in directives], "execution cache")
                else:
                    _require(response_headers.get("Cache-Control") == "no-store, private", "private cache")
                _require(response_headers.get("Location") is None, "location")
            self.collector.record(method, parsed.path, status, payload)
            return Reply(status, payload, payload["data"], replayed)
        except HttpFailure:
            raise
        except Exception:
            raise HttpFailure("Controlled HTTPS transport verification failed") from None

    def get(self, path: str, **kwargs: Any) -> Reply:
        return self.request("GET", path, **kwargs)

    def write(self, path: str, body: Any, key: str, **kwargs: Any) -> Reply:
        return self.request("POST", path, body, key, **kwargs)

    def login(self, credential: dict[str, Any], expected_status: int = 200) -> Reply:
        result = self.request("POST", "/auth/login", {"username": credential["username"], "password": credential["password"]},
                              expected_status=expected_status, expected_code="0000" if expected_status == 200 else "E0401")
        self._csrf = None
        if expected_status == 200:
            self._cookies()
            _require(result.data["id"] == credential["id"], "login identity")
        return result

    def password(self, current: str, new: str) -> Reply:
        result = self.request("POST", "/auth/password", {"currentPassword": current, "newPassword": new})
        _require(result.data is None, "password result")
        self._csrf = None
        self._cookies()
        return result

    def me(self, status: int = 200) -> Reply:
        return self.get("/auth/me", expected_status=status, expected_code="0000" if status == 200 else "E0401")

    def private_png(self, path: str, expected: bytes) -> None:
        # Binary content is intentionally excluded from Reply and Collector.
        req = urllib.request.Request(self.base_url + self._path(path), headers={"Accept": "image/png"})
        try:
            _require(self.remaining() > 0, "deadline")
            with self._opener.open(req, timeout=max(0.1, min(15.0, self.remaining()))) as response:
                actual = self._read(response, len(expected))
                _require(response.code == 200 and actual == expected, "private bytes")
                h = response.headers
                _require(h.get_content_type() == "image/png" and h.get("Content-Length") == str(len(expected))
                         and h.get("Cache-Control") == "no-store, private" and h.get("X-Content-Type-Options") == "nosniff"
                         and h.get("Content-Disposition") == 'inline; filename="evidence.png"', "private headers")
                _require(all(h.get(name) is None for name in ("ETag", "Last-Modified", "Accept-Ranges", "Location", "Content-Location")), "private headers")
        except HttpFailure:
            raise
        except Exception:
            raise HttpFailure("Controlled HTTPS private content verification failed") from None


def _key() -> str:
    return str(uuid.uuid4())


def _png(color: int) -> bytes:
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))
    header = struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)
    rows = b"".join(b"\x00" + bytes((color, 137, 211, 255)) * 2 for _ in range(2))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def _command(api: Api, path: str, body: Any, key: str | None = None, **kwargs: Any) -> tuple[Reply, str]:
    submitted = key or _key()
    response = api.write(path, body, submitted, **kwargs)
    _require(response.replayed == "false", "new command")
    return response, submitted


def _reason(reply: Reply, expected: str) -> None:
    _require(isinstance(reply.data, dict) and reply.data.get("reason") == expected, "refusal reason")


def _page(data: Any, size: int | None = None) -> list[Any]:
    _exact(data, ("content", "page", "size", "totalElements", "totalPages"))
    _require(isinstance(data["content"], list) and isinstance(data["totalElements"], int), "page")
    if size is not None:
        _require(data["size"] == size, "page size")
    return data["content"]


def _safe(data: dict[str, Any]) -> dict[str, Any]:
    return {name: copy.deepcopy(data[name]) for name in _SAFE}


def _new_order(admin: Api, customer: dict[str, Any], name: str, count: int) -> dict[str, Any]:
    refs = ["m407-" + name + "-" + str(index) for index in range(count)]
    draft = {"type": "LOCAL", "customerId": customer["id"],
        "customerReference": " 合成\x00外部參照 e\u0301 ", "remark": " 合成 M407 原文\r\n e\u0301 \x00 ",
        "items": [{"clientRef": "m407-item", "sequence": 1, "itemKind": "CARGO", "packageQty": 7,
                   "grossWeight": "0.0000000000000000001", "cbm": "0"}],
        "tasks": [{"clientRef": ref, "itemRef": "m407-item", "taskKind": "SYNTHETIC_TMS_IT_M407_" + name + "_" + str(index),
                   "pickupName": " 合成 e\u0301 起點 ", "pickupAddress": " 合成\x00地址\r\n1樓 ",
                   "deliveryName": "合成終點", "deliveryAddress": "合成終點地址",
                   "siteContact": {"name": " 合成 e\u0301 聯絡 ", "phone": None},
                   "plannedStartAt": "1500-01-02T08:00:00.123456+23:59",
                   "plannedEndAt": "1500-01-02T10:00:00.123456+23:59", "requiredTask": None if index else True,
                   "predecessorRefs": [refs[index - 1]] if index else []} for index, ref in enumerate(refs)]}
    created, create_key = _command(admin, "/orders", draft, expected_status=201, expected_code="OK")
    put_body = {"expectedVersion": created.data["version"], "draft": {**draft, "remark": draft["remark"] + " 修訂 "}}
    put_key = _key()
    edited = admin.request("PUT", "/orders/" + created.data["id"], put_body, put_key, expected_code="OK")
    _require(edited.replayed == "false", "draft command")
    accept_body = {"expectedVersion": edited.data["version"]}
    accepted, accept_key = _command(admin, "/orders/" + created.data["id"] + "/accept", accept_body, expected_code="OK")
    _require([created.data["version"], edited.data["version"], accepted.data["version"]] == [0, 1, 2], "order versions")
    _require(accepted.data["lifecycleStatus"] == "OPEN" and accepted.data["acceptanceStatus"] == "ACCEPTED", "accepted source")
    _require(accepted.data["customerReference"] == draft["customerReference"] and accepted.data["remark"] == put_body["draft"]["remark"], "raw order")
    return {"order": accepted.data, "created": created.data, "edited": edited.data, "draft": draft,
            "put_body": put_body, "accept_body": accept_body,
            "create_key": create_key, "put_key": put_key, "accept_key": accept_key, "tasks": []}


def _run_task(admin: Api, driver: Api, cohort: dict[str, Any], index: int,
              resources: dict[str, Any], complete: bool, color: int) -> dict[str, Any]:
    task = sorted(cohort["order"]["tasks"], key=lambda item: item["clientRef"])[index]
    body = {"expectedGeneration": 0, "driverId": resources["driver"]["id"],
            "mainVehicleId": resources["main"]["id"], "trailerId": None}
    assigned, assign_key = _command(admin, "/tms/tasks/" + task["id"] + "/dispatch", body)
    assignment = assigned.data["assignment"]
    _require(assigned.data["generation"] == 1 and len(assignment["reservations"]) == 2, "assignment")
    base = "/tms/my-tasks/" + assignment["id"]
    waiting = driver.get(base + "/completion").data
    _exact(waiting, list(_SAFE) + ["execution", "completion"])
    _require(waiting["execution"] == {"phase": "WAITING", "version": 0, "departedAt": None}
             and waiting["completion"] is None, "virtual execution")
    departed, depart_key = _command(driver, base + "/depart", {"expectedVersion": 0})
    _require(departed.data["execution"]["phase"] == "DEPARTED" and departed.data["execution"]["version"] == 1, "departure")
    image = _png(color)
    evidence: list[dict[str, Any]] = []
    progress_receipts: list[dict[str, Any]] = []
    for version, phase in ((1, "IN_PROGRESS"), (2, "AWAITING_COMPLETION")):
        uploaded, upload_key = _command(driver, base + "/evidence", None, raw=image,
            headers={"Content-Type": "image/png", "X-Execution-Version": str(version)})
        value = uploaded.data
        _require(value["sha256"] == hashlib.sha256(image).hexdigest() and value["byteSize"] == len(image)
                 and value["phase"] == ("DEPARTED" if version == 1 else "IN_PROGRESS")
                 and value["executionVersion"] == version and value["status"] == "VERIFIED", "photo metadata")
        driver.private_png(base + "/evidence/" + value["id"] + "/content", image)
        evidence.append(value)
        progress_body = {"expectedVersion": version, "targetPhase": phase, "evidenceIds": [value["id"]]}
        progressed, progress_key = _command(driver, base + "/progress", progress_body)
        _require(progressed.data["execution"] == {"phase": phase, "version": version + 1,
            "departedAt": departed.data["execution"]["departedAt"]}, "progress")
        progress_receipts.append({"body": progress_body, "key": progress_key, "result": progressed.data,
                                  "upload_key": upload_key, "evidence": value})
    policy = driver.get(base + "/completion-policy").data
    _require(policy["canComplete"] is True and policy["requiredEvidenceIds"] == [evidence[-1]["id"]]
             and policy["mode"] == "SYNTHETIC_TEST_ONLY", "completion eligibility")
    result = {"task_id": task["id"], "task_no": waiting["taskNo"], "assignment": assignment,
              "source": _safe(waiting), "assignment_body": body, "assignment_key": assign_key,
              "assignment_result": assigned.data, "depart_key": depart_key,
              "depart_result": departed.data, "evidence": evidence, "progress": progress_receipts,
              "execution": progress_receipts[-1]["result"]["execution"]}
    if complete:
        complete_body = {"expectedVersion": 3, "evidenceIds": [evidence[-1]["id"]], "confirmSafeRelease": True}
        completed, complete_key = _command(driver, base + "/complete", complete_body)
        _require(completed.data["execution"] == {"phase": "COMPLETED", "version": 4,
            "departedAt": departed.data["execution"]["departedAt"]}, "completion")
        witness = completed.data["completion"]
        _require(witness["safeReleaseConfirmed"] is True and witness["policyMode"] == "SYNTHETIC_TEST_ONLY"
                 and witness["completedAt"] == witness["releasedAt"], "release witness")
        result.update({"result": completed.data, "key": complete_key, "body": complete_body,
                       "execution": completed.data["execution"]})
        result["detail"] = driver.get(base + "/completion").data
        _require(result["detail"] == {**result["source"], "execution": completed.data["execution"], "completion": witness}, "completed detail")
        legacy = admin.get("/tms/tasks/" + task["id"] + "/dispatch").data
        old = legacy["current"]
        _require(old["id"] == assignment["id"] and old["status"] == "ASSIGNED" and old["version"] == 0
                 and all(row["releasedAt"] is None and row["releaseReason"] is None for row in old["reservations"]), "legacy assignment facts")
        result["legacy"] = legacy
    return result


def build_cohorts(admin: Api, driver: Api, fixture_group: dict[str, Any]) -> dict[str, Any]:
    """Create four independent cohorts via real APIs; return PRIVATE memory only."""
    categories = _page(admin.get("/adm/categories?page=0&size=100").data)
    matches = [row for row in categories if row["code"] == "VEHICLE_TYPE"]
    _require(len(matches) == 1 and matches[0]["enabled"] is True, "vehicle dictionary")
    main_type = admin.request("POST", "/adm/categories/" + matches[0]["id"] + "/items",
        {"code": "SYNTHETIC_TMS_IT_MAIN", "label": "SYNTHETIC_TMS_IT_MAIN", "enabled": True, "sortOrder": 0},
        expected_status=201).data
    physical_driver = admin.request("POST", "/fms/drivers", {"driverCode": "SYNTHETIC_TMS_IT_D8001",
        "name": "合成 M407 專用司機", "accountId": fixture_group["driver"]["id"],
        "employmentStatus": "ACTIVE", "dutyStatus": "ON_DUTY"}, expected_status=201).data
    vehicle = admin.request("POST", "/fms/vehicles", {"plateNo": "M407-" + uuid.uuid4().hex[:12],
        "vehicleType": main_type["id"], "capacityTon": "1.00", "administrativeStatus": "AVAILABLE"}, expected_status=201).data
    customer = admin.request("POST", "/crm/customers", {"customerCode": "M407_" + uuid.uuid4().hex[:12].upper(),
        "name": " 合成歷史客戶 e\u0301 "}, expected_status=201).data
    resources = {"driver": physical_driver, "main": vehicle, "main_type": main_type}
    context: dict[str, Any] = {"_resources": resources, "_customer": customer}
    for name, count in (("H", 2), ("P", 1), ("C", 1), ("A", 1)):
        cohort = _new_order(admin, customer, name, count)
        context[name] = cohort
        for index in range(count):
            task = _run_task(admin, driver, cohort, index, resources, name != "A", 70 + len(context) + index)
            cohort["tasks"].append(task)
            if name in ("H", "C"):
                reason = " 合成 M407 " + name + " 管理確認\x00 e\u0301 \n "
                confirm_body = {"expectedVersion": 0, "confirmCompletion": True, "reason": reason}
                confirmed, confirm_key = _command(admin, "/tms/completions/" + task["result"]["completion"]["id"] + "/confirm", confirm_body)
                fact = confirmed.data
                _require(fact["version"] == 1 and fact["sourceOrderVersion"] == cohort["order"]["version"]
                         and fact["confirmation"]["reason"] == reason and fact["confirmation"]["version"] == 1
                         and fact["confirmation"]["policyMode"] == "SYNTHETIC_TEST_ONLY", "confirmation fact")
                task["confirmation"] = {"body": confirm_body, "key": confirm_key, "result": fact}
        path = "/orders/" + cohort["order"]["id"]
        if name == "H":
            preclose = admin.get(path + "/closure").data
            _require(preclose["policy"]["canClose"] is True and preclose["counts"]["effectiveOccupationCount"] == 0, "closure eligibility")
            close_body = {"expectedVersion": cohort["order"]["version"], "confirmClosure": True,
                          "reason": " 合成 M407 結案\x00 e\u0301 \n "}
            closed, close_key = _command(admin, path + "/close", close_body)
            _require(closed.data["lifecycleStatus"] == "CLOSED" and closed.data["acceptanceStatus"] == "ACCEPTED"
                     and closed.data["version"] == 3 and closed.data["closure"]["sourceVersion"] == 2
                     and closed.data["closure"]["reason"] == close_body["reason"]
                     and closed.data["closure"]["taskCount"] == count, "closed fact")
            cohort["closure"] = {"body": close_body, "key": close_key, "result": closed.data}
        elif name == "P":
            detail = admin.get("/tms/completions/" + cohort["tasks"][0]["result"]["completion"]["id"] + "/confirmation").data
            _require(detail["record"]["version"] == 0 and detail["record"]["state"] == "PENDING"
                     and detail["confirmation"] is None and detail["policy"]["canConfirm"] is True, "pending confirmation cohort")
        elif name == "C":
            projection = admin.get(path + "/closure").data
            _require(projection["policy"]["canClose"] is True and projection["counts"]["confirmedTaskCount"] == count
                     and projection["counts"]["effectiveOccupationCount"] == 0, "eligible closure cohort")
        cohort["current_order"] = admin.get(path, expected_code="OK").data
        _require(cohort["current_order"]["version"] == (3 if name == "H" else 2), "cohort current version")
    # Facts below are observed after A really holds the reused resources, not
    # merely while each earlier completion happened to be the latest task.
    for name in ("H", "P", "C"):
        for task in context[name]["tasks"]:
            _require(driver.get("/tms/my-tasks/" + task["assignment"]["id"] + "/completion").data == task["detail"], "resource reuse historical access")
    for name in ("H", "P", "C", "A"):
        cohort = context[name]
        path = "/orders/" + cohort["order"]["id"]
        cohort["closure_view"] = admin.get(path + "/closure").data
        cohort["closure_tasks"] = admin.get(path + "/closure/tasks?page=0&size=100").data
        cohort["closure_history"] = admin.get(path + "/closure/history?page=0&size=100").data
        for task in cohort["tasks"]:
            base = "/tms/my-tasks/" + task["assignment"]["id"]
            # The task summary is a current TOM/policy projection, whereas the
            # assignment and history retain their recorded source snapshots.
            # Baseline all three only after H closed and A reused the resources.
            task["legacy"] = admin.get("/tms/tasks/" + task["task_id"] + "/dispatch").data
            task["policy"] = driver.get(base + "/completion-policy").data
            task["evidence_page"] = driver.get(base + "/evidence?page=0&size=100").data if name == "A" else None
            if "result" in task:
                completion_id = task["result"]["completion"]["id"]
                task["confirmation_detail"] = admin.get("/tms/completions/" + completion_id + "/confirmation").data
                task["confirmation_history"] = admin.get("/tms/completions/" + completion_id + "/confirmation/history?page=0&size=100").data
    h = context["H"]
    _require(sorted(task["requiredTask"] is None for task in h["order"]["tasks"]) == [False, True], "true and unspecified tasks")
    active = context["A"]["tasks"][0]
    _require(active["execution"]["phase"] == "AWAITING_COMPLETION" and active["execution"]["version"] == 3,
             "active last cohort")
    return context


def _same(actual: Any, expected: Any, stage: str) -> None:
    _require(actual == expected, stage)


def verify_restored(admin: Api, driver: Api, context: dict[str, Any]) -> dict[str, Any]:
    """Historical reads/receipts + independent default refusals; no auth changes."""
    h = context["H"]
    completion_replays = confirmation_replays = 0
    for name in ("H", "P", "C", "A"):
        cohort = context[name]
        path = "/orders/" + cohort["order"]["id"]
        _same(admin.get(path, expected_code="OK").data, cohort["current_order"], "restored original order")
        projection = admin.get(path + "/closure").data
        expected = copy.deepcopy(cohort["closure_view"])
        expected["policy"] = {"mode": "UNCONFIRMED", "canClose": False,
                              "blocker": "ALREADY_CLOSED" if name == "H" else "POLICY_NOT_CONFIRMED", "requirements": None}
        _same(projection, expected, "restored closure projection")
        _same(admin.get(path + "/closure/tasks?page=0&size=100").data, cohort["closure_tasks"], "restored exact task proof")
        _same(admin.get(path + "/closure/history?page=0&size=100").data, cohort["closure_history"], "restored closure history")
        for task in cohort["tasks"]:
            base = "/tms/my-tasks/" + task["assignment"]["id"]
            policy = driver.get(base + "/completion-policy").data
            expected_policy = copy.deepcopy(task["policy"])
            expected_policy.update({"mode": "UNCONFIRMED", "canComplete": False, "requirements": None,
                "requiredEvidenceIds": None, "blocker": "POLICY_NOT_CONFIRMED" if name == "A" else "ALREADY_COMPLETED"})
            _same(policy, expected_policy, "restored completion policy")
            expected_dispatch = copy.deepcopy(task["legacy"])
            current_task = expected_dispatch["task"]
            reasons = current_task["blockingReasons"]
            _require(current_task["policyMode"] == "SYNTHETIC_TEST_ONLY"
                     and "POLICY_NOT_CONFIRMED" not in reasons
                     and reasons.count("ALREADY_ASSIGNED") == 1, "source dispatch projection")
            current_task["policyMode"] = "UNCONFIRMED"
            # TmsService.summary preserves source blockers, then adds the
            # current policy blocker before the recorded-assignment blocker.
            reasons.insert(reasons.index("ALREADY_ASSIGNED"), "POLICY_NOT_CONFIRMED")
            _same(admin.get("/tms/tasks/" + task["task_id"] + "/dispatch").data,
                  expected_dispatch, "restored legacy dispatch facts")
            if name != "A":
                _same(driver.get(base + "/completion").data, task["detail"], "restored completed historical source")
                replayed = driver.write(base + "/complete", task["body"], task["key"])
                _require(replayed.replayed == "true", "completion receipt replay")
                _same(replayed.data, task["result"], "completion historical receipt")
                completion_replays += 1
                completion_path = "/tms/completions/" + task["result"]["completion"]["id"]
                observed = admin.get(completion_path + "/confirmation").data
                expected_confirmation = copy.deepcopy(task["confirmation_detail"])
                expected_confirmation["policy"] = {"mode": "UNCONFIRMED", "canConfirm": False,
                    "blocker": "POLICY_NOT_CONFIRMED" if name == "P" else "ALREADY_CONFIRMED"}
                _same(observed, expected_confirmation, "restored confirmation detail")
                _same(admin.get(completion_path + "/confirmation/history?page=0&size=100").data, task["confirmation_history"], "restored confirmation history")
                if "confirmation" in task:
                    receipt = task["confirmation"]
                    replayed = admin.write(completion_path + "/confirm", receipt["body"], receipt["key"])
                    _require(replayed.replayed == "true", "confirmation receipt replay")
                    _same(replayed.data, receipt["result"], "confirmation historical receipt")
                    confirmation_replays += 1
    # Original five-API receipts remain the historical OPEN data even after H
    # has a real CLOSED root/revision. No new command key is substituted.
    hpath = "/orders/" + h["order"]["id"]
    for method, path, body, key, original, status in (
            ("POST", "/orders", h["draft"], h["create_key"], h["created"], 201),
            ("PUT", hpath, h["put_body"], h["put_key"], h["edited"], 200),
            ("POST", hpath + "/accept", h["accept_body"], h["accept_key"], h["order"], 200)):
        replayed = admin.request(method, path, body, key, expected_status=status, expected_code="OK")
        _require(replayed.replayed == "true" and replayed.data["lifecycleStatus"] == "OPEN", "original historical replay")
        _same(replayed.data, original, "original historical order receipt")
    closed = h["closure"]
    replayed_close = admin.write(hpath + "/close", closed["body"], closed["key"])
    _require(replayed_close.replayed == "true", "closure receipt replay")
    _same(replayed_close.data, closed["result"], "closure historical receipt")
    listing = admin.get("/orders?" + urllib.parse.urlencode({"page": 0, "size": 100, "customerId": context["_customer"]["id"]}), expected_code="OK").data
    _require(any(row["id"] == h["order"]["id"] and row["lifecycleStatus"] == "CLOSED" and row["version"] == 3
                 for row in _page(listing)), "original closed list")
    pending = context["P"]["tasks"][0]
    p = admin.write("/tms/completions/" + pending["result"]["completion"]["id"] + "/confirm",
                    {"expectedVersion": 0, "confirmCompletion": True, "reason": "合成 normal policy refusal"}, _key(),
                    expected_status=409, expected_code="E2103")
    _reason(p, "POLICY_NOT_CONFIRMED")
    c = admin.write("/orders/" + context["C"]["order"]["id"] + "/close",
                    {"expectedVersion": 2, "confirmClosure": True, "reason": "合成 normal policy refusal"}, _key(),
                    expected_status=409, expected_code="E2103")
    _reason(c, "POLICY_NOT_CONFIRMED")
    active = context["A"]["tasks"][0]
    base = "/tms/my-tasks/" + active["assignment"]["id"]
    a = driver.write(base + "/complete", {"expectedVersion": 3, "confirmSafeRelease": True,
                     "evidenceIds": [active["evidence"][-1]["id"]]}, _key(), expected_status=409, expected_code="E2003")
    _reason(a, "POLICY_NOT_CONFIRMED")
    private = driver.get(base + "/evidence/" + active["evidence"][-1]["id"] + "/content",
                         expected_status=409, expected_code="E1803")
    _reason(private, "POLICY_NOT_CONFIRMED")
    _same(driver.get(base + "/evidence?page=0&size=100").data, active["evidence_page"], "restored immutable photo metadata")
    return {"historical_closed_order": True, "historical_raw_source_preserved": True,
            "historical_completed_after_resource_reuse": True, "original_open_receipts": 3,
            "historical_completion_replays": completion_replays, "historical_confirmation_replays": confirmation_replays,
            "historical_closure_replay": True, "current_modes_unconfirmed": True,
            "pending_confirmation_refusal": p.payload["code"], "eligible_closure_refusal": c.payload["code"],
            "live_completion_refusal": a.payload["code"], "private_photo_refusal": private.payload["code"],
            "private_photo_bytes_returned": False}
