#!/bin/bash
#
# Download and install datashare-client frontend from GitHub releases
#
# Usage: ./download-frontend.sh [VERSION]
#   VERSION - optional, if not provided downloads the latest release
#
# Environment variables:
#   APP_DIR - target directory (default: ./app relative to repo root)
#   CLIENT_REPO - GitHub repository (default: ICIJ/datashare-client)
#

set -e

CLIENT_REPO="${CLIENT_REPO:-ICIJ/datashare-client}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_DIR="${APP_DIR:-$REPO_ROOT/app}"

get_latest_version() {
    curl -fsSL "https://api.github.com/repos/$CLIENT_REPO/releases/latest" \
        | grep '"tag_name"' \
        | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/'
}

download_release() {
    local version="$1"
    local url="https://github.com/$CLIENT_REPO/releases/download/$version/datashare-client-$version.tgz"
    curl -fsSL -o /tmp/datashare-client.tgz "$url"
}

extract_release() {
    tar -xzf /tmp/datashare-client.tgz -C "$APP_DIR" --strip-components=1
    rm -f /tmp/datashare-client.tgz
}

app_exists() {
    [ -d "$APP_DIR" ] && [ -n "$(ls -A "$APP_DIR" 2>/dev/null)" ]
}

main() {
    local version="${1:-}"

    if app_exists; then
        echo "app/ already exists, skipping download"
        exit 0
    fi

    mkdir -p "$APP_DIR"

    if [ -n "$version" ]; then
        echo "Trying to download datashare-client $version..."
        if ! download_release "$version" 2>/dev/null; then
            echo "Version $version not found, falling back to latest..."
            version=""
        fi
    fi

    if [ -z "$version" ] || [ ! -f /tmp/datashare-client.tgz ]; then
        version=$(get_latest_version)
        echo "Downloading datashare-client $version..."
        download_release "$version"
    fi

    extract_release
    echo "Frontend $version installed to app/"
}

main "$@"
