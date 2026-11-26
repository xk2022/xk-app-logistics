#!/usr/bin/env python3
"""Synthetic M4 recovery acceptance. No production hooks, hidden policies or artifacts.

Only an explicit invocation runs Docker. Private resource intents and secret
inventory survive failures so the CI always-cleanup/privacy chain can finish.
"""
from __future__ import annotations

import argparse
from contextlib import suppress
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import pty
import re
import secrets
import select
import shutil
import signal
import subprocess
import sys
import time
import traceback
from typing import Any
import urllib.parse
import urllib.request
import ssl
import zipfile

from m4_integration_db import (snapshot, assert_equal, assert_business_equal,
                               assert_upms, audit_event, DbFailure)
from m4_integration_http import Api, Collector, build_cohorts, verify_restored

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DB = "xk_logistics_e2e"
DB_PATTERN = re.compile(r"xk_logistics_m407_(?:restore|init)_[0-9a-f]{12}\Z")
IDENTIFIER = re.compile(r"[a-z_][a-z0-9_]{0,62}\Z")
LABEL = "xk.m407.owner"


class DrillFailure(RuntimeError):
    pass


def require(value: Any, code: str = "acceptance_failed") -> None:
    if not value:
        raise DrillFailure(code)


def atomic_json(path: Path, value: Any, private: bool = True) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + secrets.token_hex(6) + ".new")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600 if private else 0o644)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=True, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600 if private else 0o644)
    finally:
        with suppress(FileNotFoundError):
            temporary.unlink()


def private_text(path: Path, text: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(text)
    os.chmod(path, 0o600)


def passwords(value: Any):
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "password" and isinstance(item, str) and item:
                yield item
            else:
                yield from passwords(item)
    elif isinstance(value, list):
        for item in value:
            yield from passwords(item)


def identities(value: Any) -> set[str]:
    if isinstance(value, dict):
        found = {value["username"]} if isinstance(value.get("username"), str) and "password" in value else set()
        for item in value.values():
            found |= identities(item)
        return found
    if isinstance(value, list):
        result: set[str] = set()
        for item in value:
            result |= identities(item)
        return result
    return set()


def source_environment(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        require("=" in line, "source_environment_invalid")
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    require(result.get("POSTGRES_DB") == SOURCE_DB, "wrong_source_database")
    require(bool(IDENTIFIER.fullmatch(result.get("POSTGRES_USER", ""))), "source_role_invalid")
    require(bool(result.get("POSTGRES_PASSWORD")) and "\n" not in result["POSTGRES_PASSWORD"], "source_credential_missing")
    return result


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def private_path(path: Path) -> Path:
    absolute = Path(os.path.abspath(path))
    require(all(not part.is_symlink() for part in (absolute, *absolute.parents)), "private_symlink_invalid")
    return absolute


def failure_site(failure: BaseException) -> list[dict]:
    """Public code locations only: never source lines, arguments or locals."""
    locations = []
    for frame in traceback.extract_tb(failure.__traceback__):
        path = Path(frame.filename).resolve()
        if path.is_relative_to(ROOT / "tests/e2e"):
            locations.append({"file": path.name, "line": frame.lineno,
                              "function": frame.name if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", frame.name) else "<module>"})
    return locations[-8:]


class Drill:
    def __init__(self, args):
        self.args = args
        self.started = time.monotonic()
        self.end = self.started + args.deadline_seconds
        self.phase = "initialization"
        self.state_path = private_path(args.state_file)
        self.work = private_path(args.work_dir)
        self.report = args.report_dir.resolve()
        require(self.report == ROOT / "test-results/m4-integration-drill", "report_path_invalid")
        self.inventory = private_path(args.secret_inventory)
        fixture_path = private_path(args.fixture)
        private_inputs = (self.inventory, fixture_path, self.state_path)
        require(len(set(private_inputs)) == 3 and not self.work.exists()
                and self.work.parent.is_dir() and self.work.parent != self.work
                and all(not path.is_relative_to(self.work) for path in private_inputs)
                and not self.work.is_relative_to(ROOT)
                and all(not path.is_relative_to(ROOT) for path in private_inputs), "private_path_invalid")
        require(not self.state_path.exists(), "owned_state_already_exists")
        self.env = source_environment(args.source_env.resolve())
        self.fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        self.group = self.fixture["m4Integration"]
        require(set(self.group) == {"admin", "controlAdmin", "driver"}, "fixture_group_invalid")
        require(all(set(value) == {"id", "username", "password"} for value in self.group.values()), "fixture_credentials_invalid")
        inventory = json.loads(self.inventory.read_text(encoding="utf-8"))
        require(set(passwords(self.fixture)) <= set(passwords(inventory)), "base_secret_inventory_incomplete")
        self.secret_values = list(passwords(inventory))
        self.initial_password_values = len(set(self.secret_values))
        self.browser_password_values = len(set(passwords(inventory.get("_generatedSecrets", []))))
        self.remember(self.env["POSTGRES_PASSWORD"], "connection")
        self.state = {"version": 1, "nonce": secrets.token_hex(6), "project": None,
                      "source": {}, "network": None, "containers": [], "databases": [],
                      "workDir": str(self.work), "phase": self.phase, "cleanupComplete": False}
        self.save_state()
        self.work.mkdir(mode=0o700)
        atomic_json(self.work / ".m407-owner.json", self.work_identity())
        self.summary = {"status": "running", "phase": self.phase,
                        "fixtureAccounts": len(identities(self.fixture)),
                        "browserGeneratedPasswordValues": self.browser_password_values,
                        "checks": {}}
        self.collector = Collector(self.report)

    def remaining(self) -> float:
        value = self.end - time.monotonic()
        if value <= 0:
            raise DrillFailure("drill_deadline")
        return value

    def work_identity(self) -> dict:
        return {"version": 1, "nonce": self.state["nonce"], "stateFile": str(self.state_path)}

    def remember(self, value: str, kind: str) -> None:
        require(bool(value), "empty_secret")
        content = json.loads(self.inventory.read_text(encoding="utf-8"))
        entries = content.setdefault("m407PrivateSecrets", [])
        require(isinstance(entries, list), "secret_inventory_invalid")
        entries.append({"password": value, "kind": kind})
        atomic_json(self.inventory, content)
        self.secret_values = list(passwords(content))

    def new_secret(self, kind: str) -> str:
        value = "M407-" + secrets.token_urlsafe(30)
        self.remember(value, kind)
        return value

    def check_output(self, output: bytes) -> None:
        if any(secret.encode("utf-8") in output for secret in self.secret_values):
            raise DrillFailure("private_subprocess_output_contains_secret")

    def save_state(self) -> None:
        atomic_json(self.state_path, self.state)

    def checkpoint(self, phase: str) -> None:
        self.phase = phase
        self.state["phase"] = phase
        self.save_state()
        self.summary["phase"] = phase
        print(json.dumps({"m407Phase": phase}), flush=True)

    def command(self, argv: list[str], *, data: bytes | None = None,
                timeout: float = 30, output_file=None, input_file=None,
                check: bool = True, secret_output: bool = False,
                merge_errors: bool = False) -> bytes:
        limit = max(0.1, min(timeout, self.remaining()))
        process = subprocess.Popen(argv, stdin=input_file if input_file is not None else subprocess.PIPE,
                                   stdout=output_file if output_file is not None else subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        try:
            output, errors = process.communicate(data, timeout=limit)
        except subprocess.TimeoutExpired:
            with suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)
            raise DrillFailure("subprocess_deadline") from None
        except BaseException:
            with suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            with suppress(subprocess.TimeoutExpired):
                process.communicate(timeout=5)
            raise
        output = output or b""
        errors = errors or b""
        require(len(errors) <= 2_097_152 and (output_file is not None or len(output) <= 4_194_304), "subprocess_output_limit")
        # Private SQL checkpoints contain credential hashes; they are never emitted.
        if not secret_output:
            self.check_output(output + errors)
        require(not check or process.returncode == 0, "subprocess_failed")
        return output + errors if merge_errors else output

    def compose(self, *arguments: str, **options) -> bytes:
        command = ["docker", "compose", "--env-file", str(self.args.source_env)]
        for file in self.args.compose_file or []:
            command += ["-f", file]
        if self.args.project:
            command += ["--project-name", self.args.project]
        return self.command(command + list(arguments), **options)

    def inspect(self, target: str, field: str):
        return json.loads(self.command(["docker", "inspect", "--format", "{{json " + field + "}}", target]).decode())

    def discover(self) -> None:
        self.checkpoint("source_identity")
        for service in ("db", "backend", "gateway"):
            identifier = self.compose("ps", "-q", service).decode().strip()
            require(bool(re.fullmatch(r"[0-9a-f]{12,64}", identifier)), "source_service_missing")
            self.state["source"][service] = self.inspect(identifier, ".Id")
        labels = [self.inspect(value, ".Config.Labels") for value in self.state["source"].values()]
        projects = {item.get("com.docker.compose.project") for item in labels}
        require(len(projects) == 1 and None not in projects, "compose_project_mismatch")
        self.state["project"] = projects.pop()
        require(not self.args.project or self.args.project == self.state["project"], "compose_project_mismatch")
        published = self.inspect(self.state["source"]["gateway"], ".NetworkSettings.Ports").get("443/tcp")
        requested_port = urllib.parse.urlsplit(self.args.base_url).port
        require(isinstance(published, list) and len(published) == 1
                and published[0].get("HostPort") == str(requested_port), "source_gateway_port_mismatch")
        networks = [set(self.inspect(value, ".NetworkSettings.Networks")) for value in self.state["source"].values()]
        common = set.intersection(*networks)
        require(len(common) == 1, "source_network_ambiguous")
        self.state["network"] = common.pop()
        self.image = self.inspect(self.state["source"]["backend"], ".Image")
        self.gateway_image = self.inspect(self.state["source"]["gateway"], ".Image")
        require(self.inspect(self.image, ".Config.Entrypoint") == ["java", "-jar", "/app/app.jar"], "production_entrypoint_invalid")
        self.state["image"] = self.image
        self.state["gatewayImage"] = self.gateway_image
        self.state["sourceMounts"] = self.inspect(self.state["source"]["db"], ".Mounts")
        self.save_state()
        require(self.query(SOURCE_DB, "SELECT current_database()") == SOURCE_DB, "wrong_source_database")
        # A private copy is inspected and removed; never an uploaded artifact.
        jar = self.work / "production.jar"
        self.command(["docker", "cp", self.state["source"]["backend"] + ":/app/app.jar", str(jar)], timeout=60)
        os.chmod(jar, 0o600)
        with zipfile.ZipFile(jar) as archive:
            require(not any(name.startswith("BOOT-INF/classes/com/xksystem/logistics/support/") for name in archive.namelist()), "test_support_in_production_jar")
            manifest = archive.read("META-INF/MANIFEST.MF").decode()
            require("Start-Class: com.xksystem.logistics.LogisticsApplication" in manifest, "production_main_invalid")
        self.jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
        jar.unlink()
        self.summary["productionJarSha256"] = self.jar_hash
        self.summary["productionImage"] = self.image

    def query(self, database: str, sql: str) -> str:
        require(database in (SOURCE_DB, "postgres") or DB_PATTERN.fullmatch(database), "database_name_invalid")
        return self.command(["docker", "exec", "-i", "-e", "PGCONNECT_TIMEOUT=5",
                             "-e", "PGOPTIONS=-c statement_timeout=30000 -c lock_timeout=5000",
                             self.state["source"]["db"], "psql", "-X", "-q", "-A", "-t",
                             "-v", "ON_ERROR_STOP=1", "-U", self.env["POSTGRES_USER"], "-d", database],
                            data=sql.encode(), timeout=60, secret_output=True).decode().strip()

    def snap(self, database: str) -> dict:
        value = snapshot(lambda sql: self.query(database, sql))
        require(value["database"] == database, "checkpoint_database_mismatch")
        return value

    def wait_db(self) -> None:
        until = time.monotonic() + min(90, self.remaining())
        while time.monotonic() < until:
            try:
                if self.query(SOURCE_DB, "SELECT current_database()") == SOURCE_DB:
                    return
            except DrillFailure:
                pass
            select.select([], [], [], min(1, max(0, until-time.monotonic())))
        raise DrillFailure("database_startup_deadline")

    def restart_source(self, baseline: dict) -> None:
        self.checkpoint("populated_restart")
        self.compose("stop", "gateway", "frontend", "backend", timeout=45)
        self.compose("restart", "db", timeout=45)
        self.wait_db()
        self.compose("start", "backend", timeout=30)
        self.wait_health(self.state["source"]["backend"])
        require(self.inspect(self.state["source"]["db"], ".Mounts") == self.state["sourceMounts"], "source_volume_changed")
        self.summary["checks"]["populatedRestart"] = assert_equal(baseline, self.snap(SOURCE_DB))
        self.compose("stop", "backend", timeout=30)
        assert_equal(baseline, self.snap(SOURCE_DB))

    def wait_health(self, container: str) -> None:
        until = time.monotonic() + min(90, self.remaining())
        while time.monotonic() < until:
            state = self.inspect(container, ".State")
            if state.get("Health", {}).get("Status") == "healthy":
                return
            if not state.get("Running"):
                raise DrillFailure("application_stopped")
            select.select([], [], [], min(1, max(0, until-time.monotonic())))
        raise DrillFailure("application_health_deadline")

    def create_database(self, kind: str) -> str:
        name = "xk_logistics_m407_" + kind + "_" + self.state["nonce"]
        require(DB_PATTERN.fullmatch(name) is not None and name != SOURCE_DB, "target_database_invalid")
        require(self.query("postgres", "SELECT count(*) FROM pg_database WHERE datname=" + sql_literal(name)) == "0", "target_database_exists")
        self.state["databases"].append(name)
        self.save_state()
        settings = json.loads(self.query(SOURCE_DB, "SELECT json_build_object('encoding',pg_encoding_to_char(encoding),'collate',datcollate,'ctype',datctype,'provider',datlocprovider) FROM pg_database WHERE datname=current_database()"))
        require(settings["encoding"] == "UTF8" and settings["provider"] == "c", "database_locale_unsupported")
        self.query("postgres", "CREATE DATABASE \"" + name + "\" TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE " + sql_literal(settings["collate"]) + " LC_CTYPE " + sql_literal(settings["ctype"]) + " OWNER \"" + self.env["POSTGRES_USER"] + "\"")
        require(self.query(name, "SELECT current_database()") == name, "target_database_mismatch")
        require(self.query(name, "SELECT count(*) FROM pg_tables WHERE schemaname='logistics'") == "0", "restore_target_not_empty")
        return name

    def dump_restore(self, baseline: dict) -> tuple[str, dict]:
        self.checkpoint("backup_restore")
        name = self.create_database("restore")
        dump = self.work / "application.dump"
        fd = os.open(dump, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "wb") as stream:
            self.command(["docker", "exec", self.state["source"]["db"], "pg_dump", "-U", self.env["POSTGRES_USER"], "-d", SOURCE_DB, "--format=custom", "--no-owner", "--no-acl"], output_file=stream, timeout=120)
        with dump.open("rb") as stream:
            require(stream.read(5) == b"PGDMP", "backup_format_invalid")
        with dump.open("rb") as stream:
            self.command(["docker", "exec", "-i", self.state["source"]["db"], "pg_restore", "-U", self.env["POSTGRES_USER"], "-d", name, "--single-transaction", "--exit-on-error", "--no-owner", "--no-acl"], input_file=stream, timeout=120)
        target = self.snap(name)
        self.summary["checks"]["restoredBeforeStartup"] = assert_equal(baseline, target)
        assert_equal(baseline, self.snap(SOURCE_DB))
        for function, table in (("validate_tms_completion", "tms_completion"), ("validate_management_confirmation", "tms_completion_confirmation"), ("validate_order_closure", "tom_order_closure")):
            # Side-effect-free proof functions; no raw row is returned to a report.
            self.query(name, "BEGIN READ ONLY; SELECT logistics." + function + "(id) FROM logistics." + table + "; COMMIT;")
        dump.unlink()
        return name, target

    def app_env(self, database: str) -> Path:
        path = self.work / (database + ".env")
        private_text(path, "DB_URL=jdbc:postgresql://db:5432/" + database + "?loggerLevel=OFF\nDB_USER=" + self.env["POSTGRES_USER"] + "\nDB_PASSWORD=" + self.env["POSTGRES_PASSWORD"] + "\nSERVER_PORT=8084\n")
        return path

    def reject_synthetic_wrong_database(self, database: str, before: dict) -> None:
        self.checkpoint("synthetic_wrong_database_refusal")
        mounts = self.inspect(self.state["source"]["backend"], ".Mounts")
        matches = [item for item in mounts if item.get("Destination") == "/run/tms-e2e"]
        require(len(matches) == 1 and matches[0].get("Type") == "bind" and matches[0].get("RW") is False,
                "source_test_classpath_invalid")
        test_classes = Path(matches[0]["Source"])
        require(test_classes.resolve() == ROOT / "artifacts/tms-e2e-runtime" and test_classes.is_dir(),
                "source_test_classpath_invalid")
        name = self.container_intent("wrong-db-negative")
        identifier = self.command(["docker", "run", "--detach", "--name", name,
            "--network", self.state["network"], "--label", LABEL + "=" + self.state["nonce"],
            "--label", "xk.m407.project=" + self.state["project"], "--env-file", str(self.app_env(database)),
            "--env", "LOADER_MAIN=com.xksystem.logistics.support.TmsE2eApplication",
            "--env", "LOADER_PATH=/run/tms-e2e", "--mount",
            "type=bind,src=" + str(test_classes) + ",dst=/run/tms-e2e,readonly",
            "--entrypoint", "java", self.image, "-cp", "/app/app.jar",
            "org.springframework.boot.loader.launch.PropertiesLauncher"], timeout=30).decode().strip()
        self.record_container(name, identifier)
        status = self.command(["docker", "wait", name], timeout=90).decode().strip()
        require(status.isdecimal() and int(status) != 0, "synthetic_wrong_database_started")
        output = self.command(["docker", "logs", name], timeout=10, merge_errors=True)
        require((b"Synthetic dispatch policy requires the isolated xk_logistics_e2e database" in output
                 or b"Synthetic evidence requires the isolated xk_logistics_e2e database" in output)
                and b"Tomcat started" not in output, "synthetic_wrong_database_gate_missing")
        assert_equal(before, self.snap(database))
        self.remove_container(name)
        self.summary["checks"]["syntheticFactoryWrongDatabase"] = {"actualDatabaseDifferent": True,
            "startupRefused": True, "all47Unchanged": True}

    def container_intent(self, kind: str) -> str:
        name = "xk-m407-" + kind + "-" + self.state["nonce"]
        require(not self.command(["docker", "ps", "-aq", "--filter", "name=^/" + name + "$"], check=True).strip(), "owned_container_exists")
        self.state["containers"].append({"name": name, "id": None, "kind": kind})
        self.save_state()
        return name

    def start_normal(self, database: str, kind: str) -> tuple[str, str]:
        self.checkpoint(kind + "_normal_jar")
        backend = self.container_intent(kind + "-backend")
        common = ["--network", self.state["network"], "--label", LABEL + "=" + self.state["nonce"], "--label", "xk.m407.project=" + self.state["project"]]
        identifier = self.command(["docker", "run", "--detach", "--name", backend] + common + ["--env-file", str(self.app_env(database)), "--health-cmd", "curl --fail --silent http://localhost:8084/actuator/health", "--health-interval", "2s", "--health-timeout", "3s", "--health-retries", "40", self.image], timeout=60).decode().strip()
        self.record_container(backend, identifier)
        self.wait_health(identifier)
        require(self.inspect(identifier, ".Image") == self.image and self.inspect(identifier, ".Config.Entrypoint") == ["java", "-jar", "/app/app.jar"], "normal_runtime_identity_invalid")
        require(self.inspect(identifier, ".Mounts") == [], "normal_runtime_has_external_classpath")
        actual = self.command(["docker", "exec", identifier, "sha256sum", "/app/app.jar"]).decode().split()[0]
        require(actual == self.jar_hash, "normal_jar_changed")
        gateway = self.container_intent(kind + "-gateway")
        config = self.work / (gateway + ".conf")
        private_text(config, "server { listen 443 ssl; server_name localhost; ssl_certificate /cert/localhost.crt; ssl_certificate_key /cert/localhost.key; ssl_protocols TLSv1.2 TLSv1.3; server_tokens off; access_log off; client_max_body_size 16k; location /api/ { proxy_pass http://" + backend + ":8084; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-Host $http_host; proxy_set_header X-Forwarded-Port " + str(self.args.restore_port) + "; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header Forwarded \"\"; } location = /actuator/health { proxy_pass http://" + backend + ":8084; } location / { return 404; } }\n")
        cert = self.args.ca_cert.resolve()
        key = self.args.tls_key.resolve() if self.args.tls_key else cert.with_name("localhost.key")
        require(cert.is_file() and key.is_file(), "tls_pair_missing")
        identifier = self.command(["docker", "run", "--detach", "--name", gateway] + common + ["--publish", "127.0.0.1:" + str(self.args.restore_port) + ":443", "--mount", "type=bind,src=" + str(config) + ",dst=/etc/nginx/conf.d/default.conf,readonly", "--mount", "type=bind,src=" + str(cert) + ",dst=/cert/localhost.crt,readonly", "--mount", "type=bind,src=" + str(key) + ",dst=/cert/localhost.key,readonly", self.gateway_image], timeout=45).decode().strip()
        self.record_container(gateway, identifier)
        base = "https://localhost:" + str(self.args.restore_port)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                    urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cert))))
        until = time.monotonic() + min(45, self.remaining())
        while time.monotonic() < until:
            try:
                with opener.open(base + "/actuator/health", timeout=min(5, self.remaining())) as response:
                    if response.status == 200 and json.loads(response.read(1024)) == {"status": "UP"}:
                        return backend, gateway
            except Exception:
                pass
            select.select([], [], [], min(1, max(0, until-time.monotonic())))
        raise DrillFailure("gateway_startup_deadline")

    def record_container(self, name: str, identifier: str) -> None:
        require(bool(re.fullmatch(r"[0-9a-f]{64}", identifier)), "owned_container_id_invalid")
        next(item for item in self.state["containers"] if item["name"] == name)["id"] = identifier
        self.save_state()

    def remove_container(self, name: str) -> None:
        matches = self.command(["docker", "ps", "-aq", "--filter", "name=^/" + name + "$"], check=True).decode().strip()
        if not matches:
            return
        labels = self.inspect(matches, ".Config.Labels")
        require(labels.get(LABEL) == self.state["nonce"] and labels.get("xk.m407.project") == self.state["project"], "cleanup_container_ownership_mismatch")
        expected = next(item for item in self.state["containers"] if item["name"] == name)
        require(expected["id"] is None or matches == expected["id"] or self.inspect(matches, ".Id") == expected["id"], "cleanup_container_identity_mismatch")
        require(matches not in self.state["source"].values(), "cleanup_source_forbidden")
        self.command(["docker", "rm", "--force", name], timeout=30)

    def offline(self, database: str, command: str, credential: dict, password: str, *, success: bool = True) -> None:
        require(command in ("init", "recover"), "offline_command_invalid")
        name = self.container_intent("cli-" + command + "-" + secrets.token_hex(3))
        argv = ["docker", "run", "--rm", "-it", "--name", name, "--network", self.state["network"], "--label", LABEL + "=" + self.state["nonce"], "--label", "xk.m407.project=" + self.state["project"], "--env-file", str(self.app_env(database)), self.image, "--upms.command=" + command]
        prompts = [("操作者識別（須經責任人核實）：", "m407-automated-tty"), ("操作原因（勿輸入秘密）：", "Synthetic " + command + " acceptance"), ("目標帳號：", credential["username"])]
        if command == "init":
            prompts.append(("顯示名稱：", "M407 synthetic initial admin"))
        prompts += [("臨時密碼（15–128 字元，輸入隱藏）：", password), ("再次輸入臨時密碼：", password)]
        pid, master = pty.fork()
        if pid == 0:
            os.execvp(argv[0], argv)
        output = bytearray()
        index = 0
        end = time.monotonic() + min(90, self.remaining())
        # Boot startup is bounded by the total cap; subsequent Console prompts by20s.
        prompt_deadline = end
        status = None
        try:
            while time.monotonic() < end:
                ready, _, _ = select.select([master], [], [], min(0.5, max(0, end-time.monotonic())))
                if ready:
                    try:
                        block = os.read(master, 8192)
                    except OSError as error:
                        if error.errno != 5:
                            raise
                        block = b""
                    if not block:
                        break
                    output.extend(block)
                    require(len(output) <= 2_097_152, "pty_output_limit")
                    self.check_output(bytes(output))
                    if index < len(prompts) and prompts[index][0].encode() in output:
                        os.write(master, prompts[index][1].encode() + b"\n")
                        index += 1
                        prompt_deadline = time.monotonic() + 20
                if status is None:
                    done, child_status = os.waitpid(pid, os.WNOHANG)
                    if done:
                        # Keep draining the PTY until EOF: waitpid can win while
                        # the final success line is still in the terminal buffer.
                        status = child_status
                require(time.monotonic() < prompt_deadline or index == len(prompts), "console_prompt_deadline")
            while status is None and time.monotonic() < end:
                done, child_status = os.waitpid(pid, os.WNOHANG)
                if done:
                    status = child_status
                else:
                    select.select([], [], [], min(0.05, max(0, end-time.monotonic())))
            require(status is not None, "offline_process_deadline")
            self.check_output(bytes(output))
            complete = "操作完成。".encode() in output
            require(index == len(prompts) and (os.waitstatus_to_exitcode(status) == 0) == success and complete == success, "offline_transaction_failed")
            require(b"Tomcat started" not in output and b"Tomcat initialized" not in output, "offline_started_web_server")
        finally:
            with suppress(OSError):
                os.close(master)
            if status is None:
                with suppress(ProcessLookupError):
                    os.kill(pid, signal.SIGKILL)
                with suppress(ChildProcessError):
                    os.waitpid(pid, 0)
            self.remove_container(name)

    def run(self) -> None:
        self.discover()
        self.checkpoint("source_https_cohorts")
        admin = Api(self.args.base_url, self.args.ca_cert, self.collector, self.remaining)
        driver = admin.clone()
        admin.login(self.group["admin"])
        driver.login(self.group["driver"])
        context = build_cohorts(admin, driver, self.group)
        baseline = self.snap(SOURCE_DB)
        require(baseline["photo"]["count"] > 0, "source_photo_proof_missing")
        self.summary["databaseAccountRows"] = len(baseline["accounts"])
        self.restart_source(baseline)
        restored, before = self.dump_restore(baseline)
        self.reject_synthetic_wrong_database(restored, before)
        restored_backend, restored_gateway = self.start_normal(restored, "restored")
        self.summary["checks"]["normalStartupNoWrites"] = assert_equal(before, self.snap(restored))
        self.checkpoint("normal_history_and_default_refusals")
        base = "https://localhost:" + str(self.args.restore_port)
        target_admin = Api(base, self.args.ca_cert, self.collector, self.remaining)
        target_driver = target_admin.clone()
        if hasattr(admin, "copy_session"):
            admin.copy_session(base).me(status=401)
        target_admin.login(self.group["admin"])
        target_driver.login(self.group["driver"])
        self.summary["checks"]["normalPolicies"] = verify_restored(target_admin, target_driver, context)
        after = self.snap(restored)
        assert_business_equal(before, after)
        assert_upms(before, after, [audit_event("LOGIN_SUCCESS", self.group["admin"]), audit_event("LOGIN_SUCCESS", self.group["driver"])])
        self.recover(restored, target_admin, target_driver, after)
        self.remove_container(restored_gateway)
        self.remove_container(restored_backend)
        self.initialize()
        self.summary["checks"]["sourceUnchanged"] = assert_equal(baseline, self.snap(SOURCE_DB))
        self.summary["status"] = "passed"

    def recover(self, database: str, admin: Api, driver: Api, before: dict) -> None:
        self.checkpoint("real_cross_process_recovery")
        victim = self.group["admin"]
        old_second = admin.clone()
        old_second.login(victim)
        control = admin.clone()
        control.login(self.group["controlAdmin"])
        prepared = self.snap(database)
        assert_business_equal(before, prepared)
        assert_upms(before, prepared, [audit_event("LOGIN_SUCCESS", victim), audit_event("LOGIN_SUCCESS", self.group["controlAdmin"])])
        wrong = admin.clone()
        bad = {**victim, "password": self.group["controlAdmin"]["password"]}
        require(bad["password"] != victim["password"], "synthetic_password_collision")
        for _ in range(10):
            wrong.login(bad, expected_status=401)
        wrong.login(victim, expected_status=401)
        locked = self.snap(database)
        assert_business_equal(prepared, locked)
        assert_upms(prepared, locked, [audit_event("LOGIN_FAILURE", victim)] * 10)
        temporary = self.new_secret("recoveryTemporary")
        self.offline(database, "recover", victim, temporary)
        recovered = self.snap(database)
        assert_business_equal(locked, recovered)
        assert_upms(locked, recovered, [audit_event("ACCOUNT_RECOVER", victim, "m407-automated-tty", "Synthetic recover acceptance")], {"id": victim["id"], "kind": "recover"})
        admin.me(status=401)
        old_second.me(status=401)
        control.me()
        driver.me()
        wrong.login(victim, expected_status=401)
        temp = admin.clone()
        reply = temp.login({**victim, "password": temporary})
        require(reply.data["mustChangePassword"] is True, "temporary_password_barrier_missing")
        temp.get("/api/v1/upms/accounts", expected_status=403, expected_code="E0403")
        current = self.snap(database)
        assert_upms(recovered, current, [audit_event("LOGIN_FAILURE", victim), audit_event("LOGIN_SUCCESS", victim)])
        assert_business_equal(recovered, current)
        final = self.new_secret("recoveryFinal")
        temp.password(temporary, final)
        temp.me(status=401)
        changed = self.snap(database)
        assert_upms(current, changed, [audit_event("PASSWORD_CHANGE", victim)], {"id": victim["id"], "kind": "password"})
        assert_business_equal(current, changed)
        fresh = admin.clone()
        require(fresh.login({**victim, "password": final}).data["mustChangePassword"] is False, "fresh_regular_login_failed")
        wrong.login({**victim, "password": temporary}, expected_status=401)
        control.me()
        final_state = self.snap(database)
        assert_upms(changed, final_state, [audit_event("LOGIN_SUCCESS", victim), audit_event("LOGIN_FAILURE", victim)])
        assert_business_equal(before, final_state)
        self.summary["checks"]["offlineRecovery"] = {"realTty": True, "syntheticOperatorOnly": True,
            "authVersionIncrements": 2, "oldSessionsRevoked": 2, "otherSessionPreserved": True,
            "oldLimiterNamespaceNotReused": True, "businessUnchanged": True}

    def initialize(self) -> None:
        self.checkpoint("real_offline_initialization")
        database = self.create_database("init")
        temporary = self.new_secret("initializationTemporary")
        credential = {"username": "m407-init-" + self.state["nonce"], "password": temporary}
        self.offline(database, "init", credential, temporary)
        initialized = self.snap(database)
        require(len(initialized["accounts"]) == 1 and len(initialized["audit"]) == 1, "bootstrap_rows_invalid")
        account = next(iter(initialized["accounts"].values()))
        observed = datetime.fromisoformat(initialized["observedAt"].replace("Z", "+00:00"))
        expiry = datetime.fromisoformat(account["temporary_password_expires_at"].replace("Z", "+00:00"))
        require(account["username"] == credential["username"] and account["role"] == "ADMIN"
                and account["enabled"] and account["must_change_password"]
                and account["auth_version"] == 0 and account["version"] == 0
                and timedelta() < expiry - observed <= timedelta(minutes=15, seconds=1),
                "bootstrap_identity_invalid")
        credential["id"] = account["id"]
        event = audit_event("ACCOUNT_BOOTSTRAP", credential, "m407-automated-tty", "Synthetic init acceptance")
        recorded = next(iter(initialized["audit"].values()))
        require(all(recorded[key] == value for key, value in event.items()), "bootstrap_audit_invalid")
        self.offline(database, "init", credential, temporary, success=False)
        assert_equal(initialized, self.snap(database))
        self.start_normal(database, "init")
        assert_equal(initialized, self.snap(database))
        api = Api("https://localhost:" + str(self.args.restore_port), self.args.ca_cert, self.collector, self.remaining)
        require(api.login(credential).data["mustChangePassword"] is True, "bootstrap_temporary_login_failed")
        api.get("/api/v1/upms/accounts", expected_status=403, expected_code="E0403")
        final = self.new_secret("initializationFinal")
        api.password(temporary, final)
        api.me(status=401)
        fresh = api.clone()
        require(fresh.login({**credential, "password": final}).data["mustChangePassword"] is False, "bootstrap_password_change_failed")
        api.login(credential, expected_status=401)
        after = self.snap(database)
        assert_upms(initialized, after, [audit_event("LOGIN_SUCCESS", credential), audit_event("PASSWORD_CHANGE", credential), audit_event("LOGIN_SUCCESS", credential), audit_event("LOGIN_FAILURE", credential)], {"id": credential["id"], "kind": "password"})
        assert_business_equal(initialized, after)
        self.summary["checks"]["offlineInitialization"] = {"realTty": True, "syntheticOperatorOnly": True,
            "nonemptyRefused": True, "firstPasswordBarrier": True, "accounts": 1}

    def cleanup(self) -> None:
        self.end = time.monotonic() + 60
        failures = []
        for item in reversed(self.state.get("containers", [])):
            try:
                self.remove_container(item["name"])
            except Exception:
                failures.append("container")
        for name in reversed(self.state.get("databases", [])):
            try:
                allowed = {"xk_logistics_m407_" + kind + "_" + self.state["nonce"] for kind in ("restore", "init")}
                require(name in allowed and name != SOURCE_DB, "cleanup_database_forbidden")
                if self.query("postgres", "SELECT count(*) FROM pg_database WHERE datname=" + sql_literal(name)) == "1":
                    self.query("postgres", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=" + sql_literal(name) + " AND pid<>pg_backend_pid()")
                    self.query("postgres", 'DROP DATABASE "' + name + '"')
                require(self.query("postgres", "SELECT count(*) FROM pg_database WHERE datname=" + sql_literal(name)) == "0", "cleanup_database_remaining")
            except Exception:
                failures.append("database")
        if not failures and self.work.exists():
            require(private_path(self.work) == self.work and self.work.is_dir(), "cleanup_work_path_invalid")
            sentinel = self.work / ".m407-owner.json"
            require(not sentinel.is_symlink() and json.loads(sentinel.read_text(encoding="utf-8")) == self.work_identity(),
                    "cleanup_work_ownership_mismatch")
            shutil.rmtree(self.work)
        self.state["cleanupComplete"] = not failures
        self.save_state()
        require(not failures, "scoped_cleanup_incomplete")


def arguments():
    parser = argparse.ArgumentParser(description="Isolated synthetic M4 integration and recovery drill")
    parser.add_argument("--cleanup-only", action="store_true")
    parser.add_argument("--source-env", type=Path, default=ROOT / ".env")
    parser.add_argument("--base-url", default="https://localhost:8443")
    parser.add_argument("--ca-cert", type=Path, default=ROOT / "infrastructure/dev-tls/localhost.crt")
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--fixture", type=Path, default=os.environ.get("E2E_FIXTURE_PATH"))
    parser.add_argument("--secret-inventory", type=Path)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path)
    parser.add_argument("--report-dir", type=Path, default=ROOT / "test-results/m4-integration-drill")
    parser.add_argument("--restore-port", type=int, default=8445)
    parser.add_argument("--project")
    parser.add_argument("--compose-file", action="append")
    parser.add_argument("--deadline-seconds", type=int, default=600)
    args = parser.parse_args()
    if args.project is not None and not args.project:
        parser.error("Explicit project must be nonempty")
    if not args.cleanup_only and (not args.fixture or not args.secret_inventory or not args.work_dir):
        parser.error("Run requires private fixture, secret inventory and work directory paths")
    if not 1024 <= args.restore_port <= 65535 or not 60 <= args.deadline_seconds <= 1800:
        parser.error("Port/deadline outside the technical bounds")
    return args


def cleanup_only(args) -> int:
    state_path = private_path(args.state_file)
    if not state_path.exists():
        print(json.dumps({"m407Cleanup": "no_registered_resources"}))
        return 0
    state = json.loads(state_path.read_text(encoding="utf-8"))
    require(state.get("version") == 1 and re.fullmatch(r"[0-9a-f]{12}", state.get("nonce", "")), "cleanup_state_invalid")
    require(not args.project or args.project == state.get("project"), "cleanup_project_mismatch")
    drill = object.__new__(Drill)
    drill.args = args
    drill.state = state
    drill.state_path = private_path(args.state_file)
    drill.work = private_path(Path(state["workDir"]))
    require(not drill.work.is_relative_to(ROOT) and not drill.state_path.is_relative_to(drill.work), "cleanup_path_invalid")
    drill.env = source_environment(args.source_env)
    drill.end = time.monotonic() + 60
    drill.secret_values = [drill.env["POSTGRES_PASSWORD"]]
    # Fail closed if the source project/container has been replaced before cleanup.
    if state.get("source", {}).get("db"):
        labels = drill.inspect(state["source"]["db"], ".Config.Labels")
        require(labels.get("com.docker.compose.project") == state.get("project")
                and labels.get("com.docker.compose.service") == "db"
                and drill.inspect(state["source"]["db"], ".Id") == state["source"]["db"], "cleanup_source_identity_mismatch")
    drill.cleanup()
    print(json.dumps({"m407Cleanup": "complete", "inventoryRetainedForCiPrivacy": True}))
    return 0


def main() -> int:
    args = arguments()
    if args.cleanup_only:
        return cleanup_only(args)
    drill = None
    result = 1
    try:
        require(os.name == "posix" and sys.version_info >= (3, 12), "unsupported_runtime")
        require(args.base_url in ("https://localhost:8443", "https://127.0.0.1:8443",
                                 "https://localhost:8444", "https://127.0.0.1:8444"), "source_url_not_isolated")
        # Also interrupts an in-flight socket read; cleanup gets a separate60s cap.
        signal.setitimer(signal.ITIMER_REAL, args.deadline_seconds)
        drill = Drill(args)
        drill.run()
        result = 0
    except BaseException as failure:
        site = failure_site(failure)
        if drill is not None:
            drill.summary["status"] = "failed"
            drill.summary["failureType"] = type(failure).__name__
            drill.summary["failureSite"] = site
            drill.summary["phase"] = drill.phase
        print(json.dumps({"m407Result": "failed", "failureType": type(failure).__name__, "failureSite": site}), flush=True)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        if drill is not None:
            try:
                drill.cleanup()
                drill.summary["cleanup"] = {"ownedRuntimeRemoved": True, "inventoryRetainedForCiPrivacy": True}
            except Exception as failure:
                result = 1
                drill.summary["status"] = "failed"
                drill.summary["cleanup"] = {"ownedRuntimeRemoved": False, "failureType": type(failure).__name__}
            drill.summary["elapsedSeconds"] = round(time.monotonic()-drill.started, 3)
            try:
                drill.collector.save()
            except Exception:
                result = 1
                drill.summary["status"] = "failed"
                drill.summary["reportCollectionFailed"] = True
            drill.summary["secretPasswordValues"] = len(set(drill.secret_values))
            drill.summary["drillNewPasswordValues"] = len(set(drill.secret_values)) - drill.initial_password_values
            atomic_json(drill.report / "summary.json", drill.summary, private=False)
    return result


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(DrillFailure("interrupted")))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(DrillFailure("drill_deadline")))
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(json.dumps({"m407Result": "failed", "failureType": type(failure).__name__}), flush=True)
        raise SystemExit(1) from None
