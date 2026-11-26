"""Reject browser artifacts that contain temporary fixture credentials or traces."""

import base64
import io
import json
import os
from pathlib import Path
import re
import zipfile


def passwords(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "password" and isinstance(item, str) and item:
                yield item.encode()
            else:
                yield from passwords(item)
    elif isinstance(value, list):
        for item in value:
            yield from passwords(item)


fixture_path = Path(os.environ.get("E2E_FIXTURE_PATH", "/tmp/xk-m2-e2e-fixture.json"))
try:
    credentials = list(passwords(json.loads(fixture_path.read_text())))
except (OSError, ValueError):
    raise SystemExit("The private fixture is required to check report privacy.") from None

if not credentials:
    raise SystemExit("The fixture contains no credentials to check.")

report = Path("playwright-report/index.html")
if not report.is_file():
    raise SystemExit("The browser report is required to check artifact privacy.")

artifacts = []
for directory in (Path("playwright-report"), Path("test-results"), Path("artifacts")):
    for path in directory.rglob("*"):
        if path.is_file():
            if path.name == "trace.zip":
                raise SystemExit("Authentication traces must remain disabled.")
            artifacts.append(path.read_bytes())

archive = re.search(rb'application/zip;base64,([^"\s<]+)', report.read_bytes())
if not archive:
    raise SystemExit("Unable to locate the embedded browser report archive.")
try:
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(archive.group(1)))) as bundle:
        artifacts.extend(bundle.read(name) for name in bundle.namelist())
except (ValueError, zipfile.BadZipFile):
    raise SystemExit("Unable to inspect the embedded browser report archive.") from None

if any(secret in artifact for secret in credentials for artifact in artifacts):
    raise SystemExit("Browser artifacts contain a temporary credential; upload refused.")

print("Browser artifact privacy checks passed; no fixture credentials or traces found.")
