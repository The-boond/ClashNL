#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="${CORE_DIR:-${ROOT_DIR}/.build/sing-box}"
CORE_REPOSITORY="${CORE_REPOSITORY:-https://github.com/SagerNet/sing-box.git}"
CORE_REF="${CORE_REF:-03c3bf4c01e7b1fd165d0c46ff376828fa878aab}"
CORE_TAG="${CORE_TAG:-v1.14.0-beta.2}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"
ANDROID_LIBS_DIR="${ANDROID_LIBS_DIR:-${ROOT_DIR}/app/libs}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.25.12}"
export GOMAXPROCS="${GOMAXPROCS:-2}"
export GOFLAGS="${GOFLAGS:--p=2}"

GO_BIN_DIR="$(go env GOPATH)/bin"
if command -v cygpath >/dev/null 2>&1; then
  GO_BIN_DIR="$(cygpath -u "${GO_BIN_DIR}")"
fi
export PATH="${GO_BIN_DIR}:${PATH}"

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi

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
  echo "Unexpected repository at ${CORE_DIR}: ${ACTUAL_ORIGIN}" >&2
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

CONVERTER_DEST="${CORE_DIR}/experimental/libbox/internal/clashconv"
mkdir -p "${CONVERTER_DEST}"
cp "${ROOT_DIR}/Core/clashconv/converter.go" "${CONVERTER_DEST}/converter.go"
cp "${ROOT_DIR}/Core/overlay/experimental/libbox/clash_converter.go" \
  "${CORE_DIR}/experimental/libbox/clash_converter.go"
cp -R "${ROOT_DIR}/Core/overlay/transport/v2rayxhttp" \
  "${CORE_DIR}/transport/v2rayxhttp"
cp -R "${ROOT_DIR}/Core/overlay/transport/vlessencryption" \
  "${CORE_DIR}/transport/vlessencryption"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/experimental/libbox/http_response_headers.patch"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/sing_box_xhttp_vless_encryption.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/sing_box_xhttp_vless_encryption.patch"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/daemon/started_service_urltest_result.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/daemon/started_service_urltest_result.patch"
git -C "${CORE_DIR}" apply --check "${ROOT_DIR}/Core/overlay/experimental/libbox/command_server_urltest_result.patch"
git -C "${CORE_DIR}" apply "${ROOT_DIR}/Core/overlay/experimental/libbox/command_server_urltest_result.patch"

(
  cd "${CORE_DIR}"
  go run ./cmd/internal/build_libbox -target android -platform "${ANDROID_PLATFORM}"
)

mkdir -p "${ANDROID_LIBS_DIR}"
for artifact in libbox.aar libbox-legacy.aar; do
  if [[ ! -f "${CORE_DIR}/${artifact}" ]]; then
    echo "${artifact} was not produced" >&2
    exit 1
  fi
  cp "${CORE_DIR}/${artifact}" "${ANDROID_LIBS_DIR}/${artifact}"
done

echo "Built Android Libbox AARs in ${ANDROID_LIBS_DIR} from ${CORE_REF}"
