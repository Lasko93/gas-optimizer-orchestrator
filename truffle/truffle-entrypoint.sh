#!/usr/bin/env bash
set -euo pipefail

# Defaults come from ENV; allow overriding with --solc X.Y.Z
SOLC="${SOLC_VERSION:-0.8.24}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --solc)
      SOLC="$2"; shift 2;;
    *)
      shift;;
  esac
done

PROJECT_DIR="/workspace/truffle"

mkdir -p "${PROJECT_DIR}/contracts" "${PROJECT_DIR}/build/contracts"

if [[ ! -d "${CONTRACTS_DIR}" ]]; then
  echo "[truffle] Contracts dir not found: ${CONTRACTS_DIR}" >&2
  exit 1
fi

# Always write a fresh config to match requested solc + paths
cat > "${PROJECT_DIR}/truffle-config.js" <<EOF
module.exports = {
  contracts_directory: "${CONTRACTS_DIR}",
  contracts_build_directory: "${BUILD_DIR}",
  compilers: {
    solc: {
      version: "${SOLC}",
      settings: { optimizer: { enabled: true, runs: 200 } }
    }
  }
};
EOF



cd "${PROJECT_DIR}"
truffle compile

echo "[truffle] Done. Artifacts at: ${BUILD_DIR}"
