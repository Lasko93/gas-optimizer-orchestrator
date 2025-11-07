#!/usr/bin/env bash
set -euo pipefail

# Defaults (can be overridden via environment)
RPC_HOST="${RPC_HOST:-ganache}"
RPC_PORT="${RPC_PORT:-8545}"
TRUFFLE_NETWORK="${TRUFFLE_NETWORK:-docker}"
TRUFFLE_INIT="${TRUFFLE_INIT:-true}"
TRUFFLE_COMPILE="${TRUFFLE_COMPILE:-true}"
TRUFFLE_MIGRATE="${TRUFFLE_MIGRATE:-true}"
NPM_INSTALL="${NPM_INSTALL:-false}"

cd /workspace

echo "[truffle] Waiting for RPC at ${RPC_HOST}:${RPC_PORT}..."
until nc -z "${RPC_HOST}" "${RPC_PORT}"; do sleep 1; done
echo "[truffle] RPC is up."

# Initialize a project (only if no config yet)
if [[ "${TRUFFLE_INIT}" == "true" && ! -f truffle-config.js ]]; then
  echo "[truffle] No truffle-config.js found; running 'truffle init'..."
  truffle init

  # Create a docker-ready network config
  cat > truffle-config.js <<'EOF'
module.exports = {
  networks: {
    docker: {
      host: "ganache",
      port: 8545,
      network_id: "*",
      gas: 8000000
    }
  },
  compilers: {
    solc: { version: "0.8.24", settings: { optimizer: { enabled: true, runs: 200 } } }
  }
};
EOF
  echo "[truffle] Wrote truffle-config.js"
fi

# Optional install if your project has package.json
if [[ "${NPM_INSTALL}" == "true" && -f package.json ]]; then
  echo "[truffle] Running npm install..."
  (npm ci || npm install)
fi

# Compile
if [[ "${TRUFFLE_COMPILE}" == "true" ]]; then
  echo "[truffle] Compiling..."
  truffle compile
fi

# Migrate
if [[ "${TRUFFLE_MIGRATE}" == "true" ]]; then
  echo "[truffle] Migrating on network '${TRUFFLE_NETWORK}'..."
  truffle migrate --network "${TRUFFLE_NETWORK}"
fi

# Keep the container alive for interactive use (truffle console, etc.)
tail -f /dev/null
