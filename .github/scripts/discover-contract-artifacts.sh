#!/usr/bin/env bash
# Emit Apicurio `group/artifact` coordinates from every *-contracts POM's
# apicurio-registry-maven-plugin <artifacts> list (one per line, sorted).
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
poms=("${ROOT}"/*-contracts/pom.xml)
if [ "${#poms[@]}" -eq 0 ]; then
  echo "ERROR: no *-contracts/pom.xml under ${ROOT}" >&2
  exit 1
fi

# Scope to <artifacts>...</artifacts> so dependency / plugin / project coordinates
# are ignored. Within each <artifact>, take the first <groupId> and <artifactId>.
for pom in "${poms[@]}"; do
  awk '
    /<artifacts>/  { in_arts = 1; next }
    /<\/artifacts>/ { in_arts = 0; next }
    !in_arts { next }
    /<artifact>/ {
      in_art = 1
      group = ""
      artifact = ""
      next
    }
    in_art && /<\/artifact>/ {
      if (group != "" && artifact != "") {
        print group "/" artifact
      }
      in_art = 0
      next
    }
    in_art && /<groupId>/ && group == "" {
      line = $0
      sub(/.*<groupId>/, "", line)
      sub(/<\/groupId>.*/, "", line)
      gsub(/^[ \t]+|[ \t]+$/, "", line)
      group = line
      next
    }
    in_art && /<artifactId>/ && artifact == "" {
      line = $0
      sub(/.*<artifactId>/, "", line)
      sub(/<\/artifactId>.*/, "", line)
      gsub(/^[ \t]+|[ \t]+$/, "", line)
      artifact = line
      next
    }
  ' "${pom}"
done | sort -u
