"""Validate the preserved 0.2.0 contract and synthetic HTTP evidence without network refs."""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import glob
import hashlib
import json
import re
from collections import Counter, deque
from pathlib import Path

import jsonschema
import yaml
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
SOURCE_MANIFEST = ROOT / "tests/contracts/order-sources.sha256"
SCHEMA_URI = "urn:xk:orders:0.2.0"
DATE_TIME = re.compile(
    r"([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})"
    r"(?:\.([0-9]{1,6}))?([Zz]|([+-])([0-9]{2}):([0-9]{2}))"
)
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
    match = DATE_TIME.fullmatch(value)
    if not match:
        return False
    try:
        dt.date.fromisoformat(match[1])
        return (
            int(match[2]) < 24 and int(match[3]) < 60 and int(match[4]) < 60
            and (match[8] is None or int(match[8]) < 24 and int(match[9]) < 60)
        )
    except ValueError:
        return False


def instant(value):
    """Compare RFC3339 instants, including contract offsets beyond ZoneOffset's 18h."""
    match = DATE_TIME.fullmatch(value)
    stamp = dt.datetime.combine(dt.date.fromisoformat(match[1]), dt.time(
        int(match[2]), int(match[3]), int(match[4]), int((match[5] or "").ljust(6, "0") or "0")))
    offset = 0 if match[8] is None else (int(match[8]) * 60 + int(match[9])) * (1 if match[7] == "+" else -1)
    return stamp - dt.timedelta(minutes=offset)


def valid_graph(draft):
    items, tasks = draft.get("items", []), draft.get("tasks", [])
    item_refs = [item["clientRef"] for item in items]
    task_refs = [task["clientRef"] for task in tasks]
    sequences = [item["sequence"] for item in items if item.get("sequence") is not None]
    if len(set(item_refs)) != len(item_refs) or len(set(task_refs)) != len(task_refs) or len(set(sequences)) != len(sequences):
        return False
    indegrees, children = Counter({ref: 0 for ref in task_refs}), {ref: [] for ref in task_refs}
    for task in tasks:
        ref = task["clientRef"]
        if task["itemRef"] not in item_refs:
            return False
        for predecessor in task.get("predecessorRefs", []):
            if predecessor == ref or predecessor not in children:
                return False
            children[predecessor].append(ref)
            indegrees[ref] += 1
        if task.get("plannedStartAt") and task.get("plannedEndAt") and instant(task["plannedEndAt"]) < instant(task["plannedStartAt"]):
            return False
    queue, seen = deque(ref for ref in task_refs if indegrees[ref] == 0), 0
    while queue:
        seen += 1
        for child in children[queue.popleft()]:
            indegrees[child] -= 1
            if indegrees[child] == 0:
                queue.append(child)
    return seen == len(tasks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--responses", nargs="+", help="Glob(s) for sanitized synthetic [{schema,value}] response records")
    args = parser.parse_args()
    for line in SOURCE_MANIFEST.read_text().splitlines():
        expected, filename = line.split("  ", 1)
        if hashlib.sha256((ROOT / filename).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Preserved source changed: {filename}")
    spec = yaml.safe_load((ROOT / "docs/06_api/openapi.yaml").read_text())
    registry = Registry().with_resource(SCHEMA_URI, Resource.from_contents(spec, default_specification=DRAFT202012))

    def errors(record):
        validator = jsonschema.Draft202012Validator(
            {"$ref": f"{SCHEMA_URI}#/components/schemas/{record['schema']}"}, registry=registry, format_checker=CHECKER)
        return list(validator.iter_errors(record["value"]))

    def require_valid(record, label):
        failures = errors(record)
        if failures:
            issue = failures[0]
            pointer = "/" + "/".join(str(part) for part in issue.path)
            # Do not print submitted data or an exception message containing it.
            raise SystemExit(f"{label}: {record['schema']} failed {issue.validator} at {pointer}")

    examples = json.loads((ROOT / "docs/06_api/order-draft-contract-examples.json").read_text())
    for record in examples["schemaPositive"]:
        require_valid(record, record["id"])
    for record in examples["schemaNegative"]:
        if not errors(record):
            raise SystemExit(f"Negative contract example unexpectedly accepted: {record['id']}")
    for record in examples["graphCases"]:
        require_valid({"schema": "CreateDraftRequest", "value": record["draft"]}, record["id"])
        if valid_graph(record["draft"]) != (record["expected"] == "valid_graph"):
            raise SystemExit(f"Graph example disagrees with contract: {record['id']}")
    roles = json.loads((ROOT / "docs/06_api/order-draft-access-policy.json").read_text())["bindings"]
    if len(roles) != 15 or len({(r["operationId"], r["singleLogicalRole"]) for r in roles}) != 15:
        raise SystemExit("Expected five operations times three logical roles")
    if any((r["access"] == "DENY_403") != (r["singleLogicalRole"] == "DRIVER") for r in roles):
        raise SystemExit("Preserved role matrix changed")
    with (ROOT / "docs/05_database/first-slice-fields.csv").open() as handle:
        if len(list(csv.DictReader(handle))) != 40:
            raise SystemExit("Expected preserved forty-field dictionary")
    print(f"Contract: {len(examples['schemaPositive'])} positive, {len(examples['schemaNegative'])} negative, "
          f"{len(examples['graphCases'])} graph, 15 role bindings, 40 fields passed")
    if args.responses:
        files = sorted({Path(path) for pattern in args.responses for path in glob.glob(str(ROOT / pattern), recursive=True)})
        if not files:
            raise SystemExit("No synthetic HTTP response files matched; validation was not run")
        count = 0
        for path in files:
            records = json.loads(path.read_text())
            if not isinstance(records, list) or not records:
                raise SystemExit("Synthetic response evidence must be a nonempty array")
            for index, record in enumerate(records):
                require_valid(record, f"{path.name}[{index}]")
                count += 1
        print(f"Actual HTTP responses: {count} passed across {len(files)} files")


if __name__ == "__main__":
    main()
