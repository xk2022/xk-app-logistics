#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p dev-tls
if [[ -e dev-tls/localhost.crt || -e dev-tls/localhost.key ]]; then
  test -f dev-tls/localhost.crt && test -f dev-tls/localhost.key
  openssl x509 -in dev-tls/localhost.crt -checkend 86400 -noout
  echo 'Existing DEV certificate retained.'
  exit 0
fi
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
  -subj '/CN=localhost/O=XK Logistics DEV only' \
  -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout dev-tls/localhost.key -out dev-tls/localhost.crt 2>/dev/null
chmod 600 dev-tls/localhost.key
chmod 644 dev-tls/localhost.crt
echo 'Generated local-only DEV certificate; never use for production.'
