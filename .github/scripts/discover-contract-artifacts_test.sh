#!/usr/bin/env bash
# Verifies discover-contract-artifacts.sh emits the seven registry coordinates
# declared in order-contracts + customer-contracts apicurio-artifacts.json catalogs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="${ROOT}/.github/scripts/discover-contract-artifacts.sh"

# Independent expected set (issue 05 / README seven artifacts) — not derived by
# re-parsing the same way the script does.
EXPECTED=$(cat <<'EOF'
events.customers/CustomerAddressAdded
events.customers/CustomerRegistered
events.customers/CustomerTierChanged
events.orders/OrderCancelled
events.orders/OrderCreated
events.orders/OrderFulfilled
events.orders/OrderShipped
EOF
)

ACTUAL="$("${SCRIPT}" "${ROOT}")"

if [ "${ACTUAL}" != "${EXPECTED}" ]; then
  echo "FAIL: discovered artifacts differ from expected seven" >&2
  echo "--- expected ---" >&2
  echo "${EXPECTED}" >&2
  echo "--- actual ---" >&2
  echo "${ACTUAL}" >&2
  exit 1
fi

# Must not be empty / must cover FORWARD bootstrap surface
COUNT=$(printf '%s\n' "${ACTUAL}" | grep -c . || true)
if [ "${COUNT}" -ne 7 ]; then
  echo "FAIL: expected 7 artifacts, got ${COUNT}" >&2
  exit 1
fi

echo "OK: discovered ${COUNT} contract artifacts"
