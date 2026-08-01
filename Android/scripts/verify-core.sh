#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="${CORE_DIR:-${ROOT_DIR}/.build/sing-box}"
CORE_REPOSITORY="${CORE_REPOSITORY:-https://github.com/SagerNet/sing-box.git}"
CORE_REF="${CORE_REF:-03c3bf4c01e7b1fd165d0c46ff376828fa878aab}"
CORE_TAG="${CORE_TAG:-v1.14.0-beta.2}"
GENERATED_CONFIG="${ROOT_DIR}/.build-clashnl-example.json"

(
  cd "${ROOT_DIR}/Core/clashconv"
  go test ./...
  go run ./cmd/clashnl-convert "${ROOT_DIR}/Examples/clash-modern.yaml" >"${GENERATED_CONFIG}"
)

if [[ ! -d "${CORE_DIR}/.git" ]]; then
  mkdir -p "$(dirname "${CORE_DIR}")"
  git clone --filter=blob:none --no-checkout "${CORE_REPOSITORY}" "${CORE_DIR}"
fi

ACTUAL_ORIGIN="$(git -C "${CORE_DIR}" remote get-url origin)"
if [[ "${ACTUAL_ORIGIN}" != "${CORE_REPOSITORY}" ]]; then
  echo "Refusing to clean unexpected repository at ${CORE_DIR}: ${ACTUAL_ORIGIN}" >&2
  exit 1
fi

git -C "${CORE_DIR}" fetch --force --depth 1 origin \
  "${CORE_REF}" "refs/tags/${CORE_TAG}:refs/tags/${CORE_TAG}"
TAG_REF="$(git -C "${CORE_DIR}" rev-list -n 1 "${CORE_TAG}")"
if [[ "${TAG_REF}" != "${CORE_REF}" ]]; then
  echo "Core tag ${CORE_TAG} resolves to ${TAG_REF}, expected ${CORE_REF}" >&2
  exit 1
fi
git -C "${CORE_DIR}" checkout --detach FETCH_HEAD
git -C "${CORE_DIR}" reset --hard "${CORE_REF}"
git -C "${CORE_DIR}" clean -fdx

mkdir -p "${CORE_DIR}/experimental/libbox/internal/clashconv"
cp "${ROOT_DIR}/Core/clashconv/converter.go" \
  "${CORE_DIR}/experimental/libbox/internal/clashconv/converter.go"
cp "${ROOT_DIR}/Core/overlay/experimental/libbox/clash_converter.go" \
  "${CORE_DIR}/experimental/libbox/clash_converter.go"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"

(
  cd "${CORE_DIR}"
  go test ./experimental/libbox/internal/clashconv
  go test -run '^$' ./experimental/libbox
  go run \
    -tags "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api" \
    ./cmd/sing-box check -c "${GENERATED_CONFIG}"
)

echo "ClashNl converter and generated sing-box config verified against ${CORE_REF}."
