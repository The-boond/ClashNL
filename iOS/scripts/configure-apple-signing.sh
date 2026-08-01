#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 APPLE_TEAM_ID [BASE_BUNDLE_ID]" >&2
  exit 2
fi

TEAM_ID="$1"
BASE_BUNDLE_ID="${2:-com.clashnl.app}"
if [[ ! "${TEAM_ID}" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "APPLE_TEAM_ID must be the 10-character Team ID from Apple Developer." >&2
  exit 2
fi
if [[ ! "${BASE_BUNDLE_ID}" =~ ^[A-Za-z0-9]+([.-][A-Za-z0-9-]+)+$ ]]; then
  echo "BASE_BUNDLE_ID must be a reverse-DNS identifier such as com.example.clashnl." >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_FILE="${ROOT_DIR}/sing-box.xcodeproj/project.pbxproj"
CURRENT_BASE_BUNDLE_ID="$(
  sed -n -E 's/^[[:space:]]*BASE_PACKAGE_IDENTIFIER = ([^;]+);/\1/p' \
    "${PROJECT_FILE}" | head -n 1
)"
if [[ -z "${CURRENT_BASE_BUNDLE_ID}" ]]; then
  echo "Could not read BASE_PACKAGE_IDENTIFIER from ${PROJECT_FILE}." >&2
  exit 1
fi
CURRENT_BASE_PATTERN="${CURRENT_BASE_BUNDLE_ID//./\\.}"

TEMP_FILE="$(mktemp "${PROJECT_FILE}.XXXXXX")"
trap 'rm -f "${TEMP_FILE}"' EXIT
sed -E \
  -e "s/DEVELOPMENT_TEAM = ([A-Z0-9]+|\"\");/DEVELOPMENT_TEAM = ${TEAM_ID};/g" \
  -e "s/(\"DEVELOPMENT_TEAM\\[sdk=[^\"]+\\]\" = )([A-Z0-9]+|\"\");/\\1${TEAM_ID};/g" \
  -e "s/BASE_PACKAGE_IDENTIFIER = [^;]+;/BASE_PACKAGE_IDENTIFIER = ${BASE_BUNDLE_ID};/g" \
  "${PROJECT_FILE}" >"${TEMP_FILE}"
mv "${TEMP_FILE}" "${PROJECT_FILE}"
trap - EXIT

IDENTIFIER_FILES=(
  "SFI/SFI.entitlements"
  "Extension/Extension.entitlements"
  "FileProviderExtension/FileProviderExtension.entitlements"
  "IntentsExtension/IntentsExtension.entitlements"
  "WidgetExtension/WidgetExtension.entitlements"
  "SFM/SFM.entitlements"
  "SFM.System/SFM.entitlements"
  "SFT/SFT.entitlements"
  "TVExtension/TVExtension.entitlements"
  "fastlane/Fastfile"
  "fastlane/Snapfile"
)
for relative_path in "${IDENTIFIER_FILES[@]}"; do
  file="${ROOT_DIR}/${relative_path}"
  temp_file="$(mktemp "${file}.XXXXXX")"
  sed "s/${CURRENT_BASE_PATTERN}/${BASE_BUNDLE_ID}/g" "${file}" >"${temp_file}"
  mv "${temp_file}" "${file}"
done

echo "Configured Apple Team ID ${TEAM_ID} and base bundle ID ${BASE_BUNDLE_ID}."
