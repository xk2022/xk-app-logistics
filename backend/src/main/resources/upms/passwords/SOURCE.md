# Common password blocklist source

This is a limited, offline common-password blocklist. It is not a complete breach corpus, an online breach lookup, or evidence that a password has never been disclosed. No customer account or credential data is included.

- Upstream: Daniel Miessler / SecLists, `Passwords/Common-Credentials/xato-net-10-million-passwords-100000.txt`.
- Pinned commit: `fcde9140df969b1e4d7bb056122646dace4ed4cc`.
- Retrieved: 2026-11-25.
- [Pinned source](https://github.com/danielmiessler/SecLists/blob/fcde9140df969b1e4d7bb056122646dace4ed4cc/Passwords/Common-Credentials/xato-net-10-million-passwords-100000.txt).
- [Pinned upstream license](https://github.com/danielmiessler/SecLists/blob/fcde9140df969b1e4d7bb056122646dace4ed4cc/LICENSE): MIT, copyright (c) 2018 Daniel Miessler; preserved verbatim as `LICENSE` beside the data.
- Original source: 781879 bytes, 100000 distinct lines; source SHA256 `1472aafa2561df5e3293aee252aee3ca660c12b399a283cf808bb01b39be388b`.
- Bundled `common-passwords-sha256.txt`: NFC-normalize, retain complete passwords of 15–128 Unicode code points, SHA256 of UTF-8 bytes, deduplicate, sort ascending, LF-terminate. It contains 72 SHA256 hex values (4680 bytes); the raw source is not bundled.
- Bundled file SHA256: `a0802be3bba37cde6697251c2a92cf2a9e66a4f4a5575a626d70b6a642db6493`.
- `LICENSE` SHA256: `3dbdc93d5f8829de0941744841730a09c106d0732e5ae0e98ca1d77be7ded66c`.

Reproduce the vendored bytes from the repository root:

```sh
curl --fail --silent --show-error --location \
  https://raw.githubusercontent.com/danielmiessler/SecLists/fcde9140df969b1e4d7bb056122646dace4ed4cc/Passwords/Common-Credentials/xato-net-10-million-passwords-100000.txt \
  --output /tmp/xk-upms-common-passwords.txt
echo '1472aafa2561df5e3293aee252aee3ca660c12b399a283cf808bb01b39be388b  /tmp/xk-upms-common-passwords.txt' | sha256sum --check --strict
python3 - <<'PY'
from pathlib import Path
import hashlib, unicodedata
source = Path('/tmp/xk-upms-common-passwords.txt').read_text(encoding='utf-8')
words = {unicodedata.normalize('NFC', line) for line in source.splitlines()}
hashes = sorted({hashlib.sha256(word.encode('utf-8')).hexdigest()
                 for word in words if 15 <= len(word) <= 128})
generated = ('\n'.join(hashes) + '\n').encode('ascii')
assert len(hashes) == 72
assert generated == Path('backend/src/main/resources/upms/passwords/common-passwords-sha256.txt').read_bytes()
PY
```

Only 72 entries contain at least 15 code points; most entries are already excluded by the product's minimum password length. The list provides a reproducible baseline with explicitly limited coverage. Application checks compare SHA256 of the complete NFC-normalized password rather than rejecting every password containing a listed substring. These unsalted SHA256 values index public weak-password data only; actual account password storage uses salted PBKDF2 and must never use this representation. Runtime authentication does not call an external service or download an updated list. Any list update requires a reviewed source/license/checksum change and regression checks.
