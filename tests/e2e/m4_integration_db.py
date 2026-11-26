"""Private PostgreSQL restore proofs for the synthetic M4 integration drill.

Only the caller executes SQL. Returned account/audit rows are PRIVATE and must
never be copied into reports; public proof helpers return aggregate outcomes.
"""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
import re
from typing import Any, Callable
from uuid import UUID

Query = Callable[[str], str]


class DbFailure(RuntimeError):
    """A deliberately generic error that never includes SQL, row data or hashes."""


TABLES = (
    "adm_change_log", "adm_dict_category", "adm_dict_item", "adm_parameter",
    "adm_parameter_definition", "api_idempotency", "cargo_item", "crm_address",
    "crm_contact", "crm_customer", "flyway_schema_history", "fms_driver",
    "fms_vehicle", "order_acceptance_event", "order_core", "order_draft_revision",
    "task_dependency", "tms_assignment", "tms_completion", "tms_completion_command",
    "tms_completion_confirmation", "tms_completion_confirmation_command",
    "tms_dispatch_event", "tms_exception", "tms_exception_command",
    "tms_exception_event", "tms_exception_event_evidence",
    "tms_exception_supplement_evidence", "tms_exception_supplement_request",
    "tms_execution", "tms_execution_command", "tms_execution_event",
    "tms_execution_event_evidence", "tms_execution_evidence",
    "tms_execution_evidence_command", "tms_execution_evidence_object",
    "tms_idempotency", "tms_resource_reservation", "tms_task_dispatch", "tom_order",
    "tom_order_closure", "tom_order_closure_command", "tom_order_closure_task",
    "transport_task", "upms_account", "upms_audit", "upms_lock",
)
_UPMS = frozenset({"upms_account", "upms_audit", "upms_lock"})
_HEX = re.compile(r"[0-9a-f]{64}\Z")
_IDENTIFIER = re.compile(r"[a-z_][a-z0-9_]*\Z")
_ACCOUNT_FIELDS = frozenset({
    "id", "username", "display_name", "role", "enabled", "must_change_password",
    "password_hash", "temporary_password_expires_at", "auth_version", "version",
    "created_at", "updated_at",
})
_AUDIT_FIELDS = frozenset({"id", "actor", "target_id", "action", "reason", "occurred_at"})
_EVENT_FIELDS = ("action", "actor", "target_id", "reason")
_MUTABLE_ACCOUNT = frozenset({
    "password_hash", "must_change_password", "temporary_password_expires_at",
    "auth_version", "version", "updated_at",
})


def _fail() -> None:
    raise DbFailure("Database integrity acceptance failed") from None


def _require(condition: bool) -> None:
    if not condition:
        _fail()


def _identifier(value: str) -> str:
    _require(isinstance(value, str) and _IDENTIFIER.fullmatch(value) is not None)
    return '"' + value + '"'


def _check_tokens(sql: str) -> list[tuple[str, str, int, int]] | None:
    """Tokenize only deparser SQL, keeping quoted material out of the grammar.

    Unsupported string syntaxes/comments deliberately leave the whole definition
    unchanged. No general SQL parser or cast-elimination rule is intended here.
    """
    tokens = []
    at = 0
    while at < len(sql):
        start = at
        char = sql[at]
        if char.isspace():
            at += 1
            continue
        if sql.startswith(("--", "/*"), at) or char == "$":
            return None
        if char in "'\"":
            quote = char
            at += 1
            while at < len(sql):
                if sql[at] == quote:
                    at += 1
                    if at < len(sql) and sql[at] == quote:
                        at += 1
                        continue
                    break
                at += 1
            else:
                return None
            tokens.append(("string" if quote == "'" else "quoted_identifier",
                           sql[start:at], start, at))
            continue
        if char.isalpha() or char == "_":
            at += 1
            while at < len(sql) and (sql[at].isalnum() or sql[at] in "_$"):
                at += 1
            # E'', B'', X'', U&'' are outside the exact observed deparser forms.
            if at < len(sql) and (sql[at] == "'" or sql.startswith("&'", at)):
                return None
            tokens.append(("word", sql[start:at], start, at))
            continue
        if sql.startswith("::", at):
            at += 2
        elif char in "+-*/<>=~!@#%^&|`?":
            at += 1
            while at < len(sql) and sql[at] in "+-*/<>=~!@#%^&|`?":
                at += 1
        else:
            at += 1
        tokens.append(("symbol", sql[start:at], start, at))
    return tokens


def normalize_check_definition(definition: str) -> str:
    """Canonicalize the two observed PG17 literal text-array deparser forms.

    pg_dump reparses `(ARRAY['X'::character varying])::text[]` as
    `ARRAY[('X'::character varying)::text]`. Both are the same text[] factor.
    Only these complete, nonempty literal-only factors directly inside `= ANY`
    are recognized. Literal spelling/order and every surrounding SQL character
    remain intact; typmods, expressions, other casts and quoted lookalikes stay
    raw. In particular this does not strip casts from columns or arbitrary SQL.
    """
    _require(isinstance(definition, str))
    tokens = _check_tokens(definition)
    if tokens is None:
        return definition

    def matches(index: int, values: tuple[str, ...]) -> bool:
        return index + len(values) <= len(tokens) and all(
            tokens[index + offset][1] == value
            and tokens[index + offset][0] != "quoted_identifier"
            for offset, value in enumerate(values))

    def factor(index: int) -> tuple[int, list[str]] | None:
        source = matches(index, ("(", "ARRAY", "["))
        if not source and not matches(index, ("ARRAY", "[")):
            return None
        cursor = index + (3 if source else 2)
        literals = []
        while cursor < len(tokens):
            if not source:
                if not matches(cursor, ("(",)):
                    return None
                cursor += 1
            if cursor >= len(tokens) or tokens[cursor][0] != "string":
                return None
            literals.append(tokens[cursor][1])
            cursor += 1
            if not matches(cursor, ("::", "character", "varying")):
                return None
            cursor += 3
            if not source:
                if not matches(cursor, (")", "::", "text")):
                    return None
                cursor += 3
            if matches(cursor, (",",)):
                cursor += 1
                continue
            suffix = ("]", ")", "::", "text", "[", "]") if source else ("]",)
            if not matches(cursor, suffix):
                return None
            return cursor + len(suffix), literals
        return None

    replacements = []
    index = 3
    while index < len(tokens):
        # This context restriction excludes other operators and array uses.
        if matches(index - 3, ("=", "ANY", "(")):
            found = factor(index)
            if found is not None:
                end, literals = found
                if matches(end, (")",)):
                    replacements.append((tokens[index][2], tokens[end - 1][3],
                                         "ARRAY[" + ", ".join(value + "::text" for value in literals) + "]"))
                    index = end
                    continue
        index += 1
    for start, end, replacement in reversed(replacements):
        definition = definition[:start] + replacement + definition[end:]
    return definition


def _canonical_schema(schema: dict[str, Any]) -> None:
    """Hash private same-snapshot catalog rows, then discard their definitions."""
    _require(isinstance(schema, dict))
    items = schema.pop("items", None)
    _require(isinstance(items, list) and len(items) == schema.get("count"))
    canonical = []
    for item in items:
        _require(isinstance(item, dict) and set(item) == {"kind", "identity", "details"}
                 and all(isinstance(value, str) for value in item.values()))
        copy = dict(item)
        if item["kind"] == "constraint":
            details = json.loads(item["details"])
            _require(isinstance(details, list) and len(details) == 7)
            if details[0] == "c":
                _require(isinstance(details[1], str))
                details[1] = normalize_check_definition(details[1])
                # The complete type/definition and all flags remain in the hash.
                copy["details"] = json.dumps(details, ensure_ascii=False, separators=(",", ":"))
        canonical.append(copy)
    canonical.sort(key=lambda item: (item["kind"], item["identity"], item["details"]))
    _require(dict(Counter(item["kind"] for item in canonical)) == schema.get("kinds"))
    payload = json.dumps(canonical, ensure_ascii=False, separators=(",", ":"))
    schema["sha256"] = hashlib.sha256(payload.encode("utf-8", errors="strict")).hexdigest()


def _read(query: Query, sql: str) -> Any:
    # psql must use -X -q -A -t so only the SELECT scalar reaches this parser.
    statement = (
        "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
        "SET LOCAL TIME ZONE 'UTC';\n"
        "SET LOCAL DateStyle='ISO, YMD';\n"
        "SET LOCAL bytea_output='hex';\n"
        "SET LOCAL search_path=pg_catalog,public;\n"
        "SET LOCAL extra_float_digits=3;\n"
        + sql + ";\nCOMMIT;"
    )
    try:
        return json.loads(query(statement))
    except Exception:
        raise DbFailure("Unable to obtain a private database checkpoint") from None


_PK_SQL = r"""
SELECT coalesce(json_object_agg(table_name, keys ORDER BY table_name),'{}'::json)
FROM (
 SELECT c.relname AS table_name,
   (SELECT json_agg(a.attname ORDER BY k.ordinality)
      FROM pg_constraint p
      CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY AS k(attnum,ordinality)
      JOIN pg_attribute a ON a.attrelid=p.conrelid AND a.attnum=k.attnum
      WHERE p.conrelid=c.oid AND p.contype='p') AS keys
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='logistics' AND c.relkind IN ('r','p')
) tables
"""

# Definitions resolve types/relations to names under the fixed search_path.
# No catalog OIDs, owner/ACL identities, or generated RI trigger names enter it.
_SCHEMA_CTE = r"""
schema_items AS (
 SELECT 'relation'::text AS kind,c.relname::text AS identity,
   json_build_array(c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,
      CASE WHEN c.relispartition THEN pg_get_expr(c.relpartbound,c.oid,false) ELSE NULL END)::text AS details
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='logistics' AND c.relkind IN ('r','p','v','m','S')
 UNION ALL
 SELECT 'column',c.relname||'.'||a.attnum::text,
   json_build_array(a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,
      a.attidentity,a.attgenerated,cn.nspname,coll.collname,pg_get_expr(d.adbin,d.adrelid,false))::text
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
 LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 LEFT JOIN pg_collation coll ON coll.oid=a.attcollation
 LEFT JOIN pg_namespace cn ON cn.oid=coll.collnamespace
 WHERE n.nspname='logistics' AND c.relkind IN ('r','p','v','m')
 UNION ALL
 SELECT 'constraint',c.relname||'.'||p.conname,
   json_build_array(p.contype,pg_get_constraintdef(p.oid,false),p.condeferrable,
      p.condeferred,p.convalidated,p.conislocal,p.connoinherit)::text
 FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='logistics'
 UNION ALL
 SELECT 'index',c.relname||'.'||i.relname,
   json_build_array(pg_get_indexdef(x.indexrelid,0,false),x.indisunique,x.indisprimary,
      x.indisvalid,x.indisready,x.indimmediate,x.indnullsnotdistinct)::text
 FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid
 JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='logistics'
 UNION ALL
 SELECT 'function',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',
   json_build_array(pg_get_functiondef(p.oid),p.provolatile,p.prosecdef,p.proparallel,p.proconfig)::text
 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='logistics' AND p.prokind IN ('f','p')
 UNION ALL
 SELECT 'user_trigger',c.relname||'.'||t.tgname,
   json_build_array(pg_get_triggerdef(t.oid,false),t.tgenabled,t.tgdeferrable,t.tginitdeferred)::text
 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='logistics' AND NOT t.tgisinternal
 UNION ALL
 SELECT 'internal_constraint_trigger',
   owner_table.relname||'.'||p.conname||':'||actual_table.relname||':'||fn.proname||':'||t.tgtype::text,
   json_build_array(p.contype,t.tgenabled,t.tgdeferrable,t.tginitdeferred,count(*))::text
 FROM pg_trigger t JOIN pg_constraint p ON p.oid=t.tgconstraint
 JOIN pg_class owner_table ON owner_table.oid=p.conrelid
 JOIN pg_namespace owner_ns ON owner_ns.oid=owner_table.relnamespace
 JOIN pg_class actual_table ON actual_table.oid=t.tgrelid
 JOIN pg_proc fn ON fn.oid=t.tgfoid
 WHERE owner_ns.nspname='logistics' AND t.tgisinternal
 GROUP BY owner_table.relname,p.conname,actual_table.relname,fn.proname,t.tgtype,
   p.contype,t.tgenabled,t.tgdeferrable,t.tginitdeferred
), schema_proof AS (
 SELECT json_build_object(
   'count',count(*),
   'sha256',encode(sha256(convert_to(coalesce(json_agg(row_to_json(s)
      ORDER BY kind,identity,details)::text,'[]'),'UTF8')),'hex'),
   'items',json_agg(row_to_json(s) ORDER BY kind,identity,details),
   'kinds',(SELECT json_object_agg(kind,amount ORDER BY kind)
      FROM (SELECT kind,count(*) AS amount FROM schema_items GROUP BY kind) grouped)
 ) AS value FROM schema_items s
)
"""


def snapshot(query: Query) -> dict[str, Any]:
    """Take one atomic data/schema/photo/UPMS checkpoint; return PRIVATE data."""
    try:
        keys = _read(query, _PK_SQL)
        _require(isinstance(keys, dict) and set(keys) == set(TABLES))
        fingerprints = []
        for table in TABLES:
            columns = keys[table]
            _require(isinstance(columns, list) and bool(columns))
            ordering = ",".join(_identifier(name) for name in columns)
            fingerprints.append(
                "SELECT '" + table + "'::text AS name,count(*) AS amount,"
                "encode(sha256(convert_to(coalesce(json_agg(row_to_json(r) ORDER BY "
                + ordering + ")::text,'[]'),'UTF8')),'hex') AS digest FROM logistics."
                + _identifier(table) + " r"
            )
        sql = "WITH data_rows AS (" + " UNION ALL ".join(fingerprints) + ")," + _SCHEMA_CTE + r"""
SELECT json_build_object(
 'database',current_database(),
 'serverVersion',current_setting('server_version_num'),
 'encoding',current_setting('server_encoding'),
 'collation',(SELECT json_build_array(datcollate,datctype,datlocprovider,datcollversion)
    FROM pg_database WHERE datname=current_database()),
 'observedAt',transaction_timestamp(),
 'data',(SELECT json_object_agg(name,json_build_object('count',amount,'sha256',digest)
    ORDER BY name) FROM data_rows),
 'schema',(SELECT value FROM schema_proof),
 'photo',(SELECT json_build_object(
    'count',count(*),
    'valid',count(*) FILTER(WHERE octet_length(content)=byte_size AND encode(sha256(content),'hex')=sha256),
    'sha256',encode(sha256(convert_to(coalesce(json_agg(json_build_array(id,byte_size,sha256)
      ORDER BY id)::text,'[]'),'UTF8')),'hex')) FROM logistics.tms_execution_evidence_object),
 'history',(SELECT coalesce(json_agg(row_to_json(h) ORDER BY installed_rank),'[]'::json)
    FROM logistics.flyway_schema_history h),
 'accounts',(SELECT coalesce(json_object_agg(id::text,row_to_json(a) ORDER BY id),'{}'::json)
    FROM logistics.upms_account a),
 'audit',(SELECT coalesce(json_object_agg(id::text,row_to_json(a) ORDER BY id),'{}'::json)
    FROM logistics.upms_audit a),
 'lock',(SELECT coalesce(json_agg(row_to_json(l) ORDER BY id),'[]'::json) FROM logistics.upms_lock l)
)
"""
        result = _read(query, sql)
        _canonical_schema(result.get("schema"))
        _validate_snapshot(result)
        return result
    except DbFailure:
        raise
    except Exception:
        raise DbFailure("Unable to establish a database integrity checkpoint") from None


def _time(value: Any) -> datetime:
    _require(isinstance(value, str))
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        _fail()
    _require(parsed.tzinfo is not None)
    return parsed.astimezone(timezone.utc)


def _uuid(value: Any) -> str:
    _require(isinstance(value, str))
    try:
        normalized = str(UUID(value))
    except (ValueError, TypeError, AttributeError):
        _fail()
    _require(value == normalized)
    return normalized


def _validate_snapshot(value: Any) -> None:
    _require(isinstance(value, dict))
    _require(isinstance(value.get("database"), str))
    _require(value.get("encoding") == "UTF8")
    _require(isinstance(value.get("serverVersion"), str)
             and value["serverVersion"].startswith("17"))
    _time(value.get("observedAt"))
    data = value.get("data")
    _require(isinstance(data, dict) and set(data) == set(TABLES))
    for item in data.values():
        _require(isinstance(item, dict) and set(item) == {"count", "sha256"})
        _require(type(item["count"]) is int and item["count"] >= 0)
        _require(isinstance(item["sha256"], str) and _HEX.fullmatch(item["sha256"]) is not None)
    schema = value.get("schema")
    _require(isinstance(schema, dict) and type(schema.get("count")) is int
             and schema["count"] > 0 and isinstance(schema.get("kinds"), dict))
    _require(isinstance(schema.get("sha256"), str) and _HEX.fullmatch(schema["sha256"]) is not None)
    photo = value.get("photo")
    _require(isinstance(photo, dict) and type(photo.get("count")) is int
             and photo["count"] >= 0 and photo.get("valid") == photo["count"])
    _require(isinstance(photo.get("sha256"), str) and _HEX.fullmatch(photo["sha256"]) is not None)
    history = value.get("history")
    _require(isinstance(history, list))
    versions = [row.get("version") for row in history if row.get("version") is not None]
    _require(versions == [str(number) for number in range(1, 15)])
    _require(all(row.get("success") is True for row in history))
    for field in ("accounts", "audit"):
        _require(isinstance(value.get(field), dict))
        for identifier, row in value[field].items():
            _uuid(identifier)
            _require(isinstance(row, dict) and row.get("id") == identifier)
    _require(value.get("lock") == [{"id": 1}])
    _require(data["upms_account"]["count"] == len(value["accounts"]))
    _require(data["upms_audit"]["count"] == len(value["audit"]))


def assert_equal(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    """Assert full47 restored equality without comparing the different DB name."""
    _validate_snapshot(before)
    _validate_snapshot(after)
    for key in ("serverVersion", "encoding", "collation", "data", "schema", "photo",
                "history", "accounts", "audit", "lock"):
        _require(before[key] == after[key])
    return {"tablesCompared": len(TABLES), "tablesEqual": len(TABLES),
            "schemaEqual": True, "photoObjects": before["photo"]["count"],
            "photoIntegrity": True, "historyEqual": True}


def assert_business_equal(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    """UPMS changes need their separate exact allowlist; every other table stays equal."""
    _validate_snapshot(before)
    _validate_snapshot(after)
    for table in TABLES:
        if table not in _UPMS:
            _require(before["data"][table] == after["data"][table])
    for key in ("serverVersion", "encoding", "collation", "schema", "photo", "history"):
        _require(before[key] == after[key])
    return {"businessTablesCompared": len(TABLES) - len(_UPMS),
            "businessTablesEqual": len(TABLES) - len(_UPMS)}


def audit_event(action: str, account: dict[str, Any], operator: str | None = None,
                reason: str | None = None) -> dict[str, Any]:
    """Build a PRIVATE exact event expectation, never including an account password."""
    username = account.get("username")
    _require(isinstance(username, str) and re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,63}", username) is not None)
    identifier = _uuid(account.get("id"))
    if action == "LOGIN_FAILURE":
        return {"action": action, "actor": "login:" + hashlib.sha256(username.encode()).hexdigest(),
                "target_id": None, "reason": "登入失敗；計數增加"}
    reasons = {"LOGIN_SUCCESS": "登入成功；失敗計數清除", "LOGOUT": "本人登出",
               "PASSWORD_CHANGE": "本人改密；撤銷所有認證，須重新登入"}
    if action in reasons:
        return {"action": action, "actor": username, "target_id": identifier,
                "reason": reasons[action]}
    if action in {"ACCOUNT_RECOVER", "ACCOUNT_BOOTSTRAP"}:
        _require(isinstance(operator, str) and bool(operator)
                 and isinstance(reason, str) and bool(reason))
        return {"action": action, "actor": operator, "target_id": identifier,
                "reason": reason + ("；失敗計數清除，撤銷認證" if action == "ACCOUNT_RECOVER" else "")}
    _fail()


def assert_upms(before: dict[str, Any], after: dict[str, Any],
                expected_events: list[dict[str, Any]],
                victim_change: dict[str, Any] | None = None) -> None:
    """Assert precise accounts/lock and immutable audit-prefix/appended-event changes."""
    try:
        _validate_snapshot(before)
        _validate_snapshot(after)
        _require(before["lock"] == after["lock"])
        old_accounts, new_accounts = before["accounts"], after["accounts"]
        _require(set(old_accounts) == set(new_accounts))
        victim = None
        if victim_change is not None:
            _require(set(victim_change) == {"id", "kind"})
            victim = _uuid(victim_change["id"])
            _require(victim in old_accounts and victim_change["kind"] in {"recover", "password"})
        for identifier, old in old_accounts.items():
            new = new_accounts[identifier]
            _require(set(old) == _ACCOUNT_FIELDS and set(new) == _ACCOUNT_FIELDS)
            if identifier != victim:
                _require(old == new)
                continue
            for field in _ACCOUNT_FIELDS - _MUTABLE_ACCOUNT:
                _require(old[field] == new[field])
            for field in ("auth_version", "version"):
                _require(type(old[field]) is int and type(new[field]) is int
                         and new[field] == old[field] + 1)
            _require(isinstance(new["password_hash"], str) and bool(new["password_hash"])
                     and old["password_hash"] != new["password_hash"])
            _require(_time(old["updated_at"]) <= _time(new["updated_at"])
                     <= _time(after["observedAt"]) + timedelta(seconds=1))
            if victim_change["kind"] == "recover":
                _require(new["must_change_password"] is True and new["temporary_password_expires_at"] is not None)
                remaining = _time(new["temporary_password_expires_at"]) - _time(after["observedAt"])
                _require(timedelta() < remaining <= timedelta(minutes=15, seconds=1))
            else:
                _require(new["must_change_password"] is False
                         and new["temporary_password_expires_at"] is None)
        old_audit, new_audit = before["audit"], after["audit"]
        _require(set(old_audit) <= set(new_audit))
        _require(all(new_audit[identifier] == row for identifier, row in old_audit.items()))
        added = [row for identifier, row in new_audit.items() if identifier not in old_audit]
        expected = []
        for event in expected_events:
            _require(isinstance(event, dict) and set(event) == set(_EVENT_FIELDS))
            expected.append(tuple(event[field] for field in _EVENT_FIELDS))
        actual = []
        lower = _time(before["observedAt"]) - timedelta(seconds=1)
        upper = _time(after["observedAt"]) + timedelta(seconds=1)
        for row in added:
            _require(set(row) == _AUDIT_FIELDS)
            _uuid(row["id"])
            _require(lower <= _time(row["occurred_at"]) <= upper)
            actual.append(tuple(row[field] for field in _EVENT_FIELDS))
        _require(Counter(actual) == Counter(expected))
    except DbFailure:
        raise
    except Exception:
        raise DbFailure("UPMS recovery mutation acceptance failed") from None
