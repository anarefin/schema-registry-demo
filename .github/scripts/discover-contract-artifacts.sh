#!/usr/bin/env bash
# Emit Apicurio `group/artifact` coordinates from every *-contracts/apicurio-artifacts.json
# (one per line, sorted).
#
# Usage: discover-contract-artifacts.sh [repo-root]
# Default repo-root is two levels above this script (.github/scripts → repo root).
set -euo pipefail

ROOT="${1:-}"
if [ -z "${ROOT}" ]; then
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fi

if [ ! -d "${ROOT}" ]; then
  echo "ERROR: repo root not found: ${ROOT}" >&2
  exit 1
fi

shopt -s nullglob
catalogs=("${ROOT}"/*-contracts/apicurio-artifacts.json)
if [ "${#catalogs[@]}" -eq 0 ]; then
  echo "ERROR: no *-contracts/apicurio-artifacts.json under ${ROOT}" >&2
  exit 1
fi

for catalog in "${catalogs[@]}"; do
  # Prefer jq when present; fall back to python3 (standard on CI runners).
  if command -v jq >/dev/null 2>&1; then
    jq -r '.[] | "\(.group)/\(.artifact)"' "${catalog}"
  else
    python3 - "${catalog}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    for item in json.load(f):
        print(f"{item['group']}/{item['artifact']}")
PY
  fi
done | sort -u
