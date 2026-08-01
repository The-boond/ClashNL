#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="${CORE_DIR:-${ROOT_DIR}/.build/sing-box}"
CORE_REPOSITORY="${CORE_REPOSITORY:-https://github.com/SagerNet/sing-box.git}"
CORE_REF="${CORE_REF:-03c3bf4c01e7b1fd165d0c46ff376828fa878aab}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"

GO_BIN_DIR="$(go env GOPATH)/bin"
if command -v cygpath >/dev/null 2>&1; then
  GO_BIN_DIR="$(cygpath -u "${GO_BIN_DIR}")"
fi
export PATH="${GO_BIN_DIR}:${PATH}"

if ! command -v gomobile >/dev/null 2>&1; then
  go install "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VERSION}"
fi
if ! command -v gobind >/dev/null 2>&1; then
  go install "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VERSION}"
fi

if [[ ! -d "${CORE_DIR}/.git" ]]; then
  mkdir -p "$(dirname "${CORE_DIR}")"
  git clone --filter=blob:none --no-checkout "${CORE_REPOSITORY}" "${CORE_DIR}"
fi

ACTUAL_ORIGIN="$(git -C "${CORE_DIR}" remote get-url origin)"
if [[ "${ACTUAL_ORIGIN}" != "${CORE_REPOSITORY}" ]]; then
  echo "Refusing to clean unexpected repository at ${CORE_DIR}: ${ACTUAL_ORIGIN}" >&2
  exit 1
fi

git -C "${CORE_DIR}" fetch --depth 1 origin "${CORE_REF}"
git -C "${CORE_DIR}" config core.autocrlf false
git -C "${CORE_DIR}" checkout --detach --force FETCH_HEAD
git -C "${CORE_DIR}" reset --hard FETCH_HEAD
git -C "${CORE_DIR}" clean -fdx

CONVERTER_DEST="${CORE_DIR}/experimental/libbox/internal/clashconv"
mkdir -p "${CONVERTER_DEST}"
cp "${ROOT_DIR}/Core/clashconv/converter.go" "${CONVERTER_DEST}/converter.go"
cp "${ROOT_DIR}/Core/overlay/experimental/libbox/clash_converter.go" \
  "${CORE_DIR}/experimental/libbox/clash_converter.go"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"

(
  cd "${CORE_DIR}"
  go run ./cmd/internal/build_libbox -target apple
)

if [[ -d "${CORE_DIR}/Libbox.xcframework" ]]; then
  rm -rf "${ROOT_DIR}/Libbox.xcframework"
  mv "${CORE_DIR}/Libbox.xcframework" "${ROOT_DIR}/Libbox.xcframework"
fi

if [[ ! -d "${ROOT_DIR}/Libbox.xcframework" ]]; then
  echo "Libbox.xcframework was not produced" >&2
  exit 1
fi

echo "Built ${ROOT_DIR}/Libbox.xcframework from ${CORE_REF}"
