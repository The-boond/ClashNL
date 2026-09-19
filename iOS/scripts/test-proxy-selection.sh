#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/clashnl-selection.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

swiftc -swift-version 5 \
  "${ROOT_DIR}/Library/Network/RuntimeProxyRouting.swift" \
  "${ROOT_DIR}/scripts/tests/RuntimeProxyRoutingTests.swift" \
  -o "${BUILD_DIR}/routing-tests"
"${BUILD_DIR}/routing-tests"

# Syntax-check app integration separately; this does not replace an Xcode build.
for source in \
  Library/Network/ExtensionProvider.swift \
  Library/Network/ExtensionProfile.swift \
  Library/Network/CommandClient.swift \
  ApplicationLibrary/Views/Dashboard/Cards/DashboardServiceCard.swift \
  ApplicationLibrary/Views/Dashboard/Cards/CardManagementSheet.swift \
  ApplicationLibrary/Views/Groups/GroupListViewModel.swift \
  ApplicationLibrary/Views/Groups/GroupListView.swift \
  ApplicationLibrary/Views/Groups/GroupItemView.swift; do
  swiftc -frontend -parse "${ROOT_DIR}/${source}"
done
