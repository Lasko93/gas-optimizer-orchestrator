#!/usr/bin/env bash
set -euo pipefail

# -----------------------------
# Defaults / CLI args
# -----------------------------
SOLC="${SOLC_VERSION:-0.8.24}"

# Allow overriding compiler via --solc X.Y.Z
while [[ $# -gt 0 ]]; do
  case "$1" in
    --solc) SOLC="$2"; shift 2 ;;
    *) shift ;;
  esac
done

# Paths (can be overridden via env)
PROJECT_DIR="${PROJECT_DIR:-/workspace/truffle}"
CONTRACTS_DIR="${CONTRACTS_DIR:-/workspace/contracts}"
BUILD_DIR="${BUILD_DIR:-${PROJECT_DIR}/build/contracts}"
STAGED_DIR="${PROJECT_DIR}/staged"

mkdir -p "${STAGED_DIR}" "${BUILD_DIR}" "${CONTRACTS_DIR}"


if [[ ! -d "${CONTRACTS_DIR}" ]]; then
  echo "[truffle] Contracts dir not found: ${CONTRACTS_DIR}" >&2
  exit 1
fi

## Helper to find out if lib has content
skip_if_deep_leaf_has_file() {
  local d="${CONTRACTS_DIR}/lib"
  [[ -d "$d" ]] || return 0  # kein lib/ vorhanden -> keine Entscheidung hier

  while :; do
    # Gibt es in diesem Ordner Dateien? -> skip
    if find "$d" -maxdepth 1 -type f -print -quit 2>/dev/null | grep -q .; then
      echo "[truffle] Datei gefunden in ${d} — skipping submodule fetch."
      return 0
    fi

    local next
    next="$(find "$d" -mindepth 1 -maxdepth 1 -type d ! -name '.git' -print 2>/dev/null | head -n1)"

    [[ -n "$next" ]] || break
    d="$next"
  done

  return 1  # keine Datei im tiefsten Ordner -> NICHT skippen
}

## Init necessary modules that might be referenced
init_git_submodules() {
  cd "${CONTRACTS_DIR}"

  if skip_if_deep_leaf_has_file; then
    return 0
  fi

  mkdir -p lib

  git config -f .gitmodules --get-regexp 'submodule\..*\.url' | while read -r key url; do
    name="${key#submodule.}"
    name="${name%.url}"
    path="$(git config -f .gitmodules --get "submodule.${name}.path")"

    # Ensure parent directory exists (e.g., lib/openzeppelin-contracts)
    mkdir -p "$(dirname "$path")"

    echo "[truffle] Cloning ${url} -> ${path}"
    git clone --depth 1 "$url" "$path"
  done
}


ensure_npm_basics() {
  mkdir -p "${PROJECT_DIR}"
  cd "${PROJECT_DIR}"
  if [[ ! -f package.json ]]; then
    npm init -y >/dev/null 2>&1
  fi
  npm config set fund false >/dev/null 2>&1 || true
  npm config set audit false >/dev/null 2>&1 || true
  npm config set unsafe-perm true >/dev/null 2>&1 || true
  mkdir -p node_modules
}


wire_remappings() {
  local map_file="${CONTRACTS_DIR}/remappings.txt"
  [[ -f "${map_file}" ]] || { echo "[truffle] No remappings.txt — nothing to wire."; return 0; }

  # ensure npm env
  mkdir -p "${PROJECT_DIR}"
  ( cd "${PROJECT_DIR}" && [[ -f package.json ]] || npm init -y >/dev/null 2>&1 )
  npm config set fund false >/dev/null 2>&1 || true
  npm config set audit false >/dev/null 2>&1 || true
  npm config set unsafe-perm true >/dev/null 2>&1 || true
  mkdir -p "${PROJECT_DIR}/node_modules"

  while IFS= read -r line; do
    [[ -z "${line// }" ]] && continue
    [[ "${line}" =~ ^# ]] && continue

    IFS='=' read -r prefix target <<<"${line}"
    prefix="${prefix%/}"
    target="${target%/}"

    if [[ "${target}" == node_modules/* ]]; then
      pkg="${target#node_modules/}"

      # If it's a scope-only mapping like "@ensdomains", skip install.
      if [[ "${pkg}" == @* && "${pkg}" != *@*/* ]]; then
        # just ensure the directory exists so further packages can land there
        mkdir -p "${PROJECT_DIR}/node_modules/${pkg}"
        echo "[truffle] scope mapping detected (${pkg}) — skipping npm install."
        continue
      fi

      # derive the real package to install
      if [[ "${pkg}" == @*/* ]]; then
        # take "@scope/name" (first two segments)
        pkg_name="$(echo "${pkg}" | awk -F/ '{print $1 "/" $2}')"
      else
        # take unscoped "name" (first segment)
        pkg_name="$(echo "${pkg}" | awk -F/ '{print $1}')"
      fi

      if [[ ! -d "${PROJECT_DIR}/node_modules/${pkg_name}" ]]; then
        echo "[truffle] npm i ${pkg_name}"
        ( cd "${PROJECT_DIR}" && npm i "${pkg_name}" )
      fi
      continue
    fi

    # lib/* → symlink from node_modules/<prefix> to the lib path
    if [[ "${target}" == lib/* ]]; then
      src="${CONTRACTS_DIR}/${target}"
      dest="${PROJECT_DIR}/node_modules/${prefix}"
      mkdir -p "$(dirname "${dest}")"
      echo "[truffle] link ${dest} -> ${src}"
      ln -sfn "${src}" "${dest}"
      continue
    fi

    # default: treat as project-relative path → symlink
    src="${CONTRACTS_DIR}/${target}"
    dest="${PROJECT_DIR}/node_modules/${prefix}"
    mkdir -p "$(dirname "${dest}")"
    echo "[truffle] link ${dest} -> ${src}"
    ln -sfn "${src}" "${dest}"
  done < "${map_file}"

  # Helpful heuristic: if sources import ENS contracts, ensure that package
  if grep -R --include='*.sol' -q '@ensdomains/ens-contracts' "${CONTRACTS_DIR}" 2>/dev/null; then
    if [[ ! -d "${PROJECT_DIR}/node_modules/@ensdomains/ens-contracts" ]]; then
      echo "[truffle] Detected ENS imports → installing @ensdomains/ens-contracts"
      ( cd "${PROJECT_DIR}" && npm i @ensdomains/ens-contracts )
    fi
  fi

  # And if hardhat is mapped, make sure it's installed
  if grep -q '^hardhat/=node_modules/hardhat/?$' "${map_file}"; then
    if [[ ! -d "${PROJECT_DIR}/node_modules/hardhat" ]]; then
      echo "[truffle] Installing hardhat due to remapping"
      ( cd "${PROJECT_DIR}" && npm i hardhat )
    fi
  fi
}



init_git_submodules
wire_remappings

# -----------------------------
# Stage only source files (ignore tests & scripts)
# -----------------------------
echo "[truffle] Staging Solidity sources from ${CONTRACTS_DIR} -> ${STAGED_DIR}"
rm -rf "${STAGED_DIR:?}"/*
mkdir -p "${STAGED_DIR}"

if command -v rsync >/dev/null 2>&1; then
  # Keep only .sol files outside of lib/, script/, test/, .git; drop *.t.sol / *.s.sol
  rsync -a --prune-empty-dirs \
    --exclude 'lib/**' \
    --exclude 'script/**' \
    --exclude 'test/**' \
    --exclude '.git/**' \
    --exclude 'node_modules/**' \
    --exclude '**/*.t.sol' \
    --exclude '**/*.s.sol' \
    --include '*/' \
    --include '*.sol' \
    --exclude '*' \
    "${CONTRACTS_DIR}/" "${STAGED_DIR}/"
else
  # Fallback: copy only *.sol while pruning test/script/lib dirs
  while IFS= read -r -d '' f; do
    rel="${f#${CONTRACTS_DIR}/}"
    mkdir -p "${STAGED_DIR}/$(dirname "$rel")"
    cp "$f" "${STAGED_DIR}/$rel"
  done < <(
    find "${CONTRACTS_DIR}" \
      -type d -name lib -prune -o \
      -type d -name script -prune -o \
      -type d -name test -prune -o \
      -type d -name node_modules -prune -o \
      -type f -name '*.sol' \
      ! -name '*.t.sol' ! -name '*.s.sol' \
      -print0
  )
fi

# -----------------------------
# Generate truffle-config.js
# -----------------------------
cat > "${PROJECT_DIR}/truffle-config.js" <<EOF
module.exports = {
  contracts_directory: "${STAGED_DIR}",
  contracts_build_directory: "${BUILD_DIR}",
  compilers: {
    solc: {
      version: "${SOLC}",
      settings: { optimizer: { enabled: true, runs: 200 } }
    }
  }
};
EOF

# -----------------------------
# Compile
# -----------------------------
cd "${PROJECT_DIR}"
echo "[truffle] Using solc ${SOLC}"
truffle compile

echo "[truffle] Done. Artifacts at: ${BUILD_DIR}"
