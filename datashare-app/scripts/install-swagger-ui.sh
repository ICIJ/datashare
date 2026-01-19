#!/bin/bash
#
# Download and install Swagger UI into the app/api directory
#
# Usage: ./install-swagger-ui.sh [SWAGGER_VERSION] [DATASHARE_URL]
#   SWAGGER_VERSION - optional, defaults to 5.18.2
#   DATASHARE_URL   - optional, defaults to localhost:8888
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_DIR="$REPO_ROOT/app"

SWAGGER_VERSION="${1:-5.18.2}"
DATASHARE_URL="${2:-localhost:8888}"

download_swagger_ui() {
    local version="$1"
    local target_dir="$2"
    local url="https://github.com/swagger-api/swagger-ui/archive/refs/tags/v${version}.tar.gz"

    echo "Downloading Swagger UI v${version}..."
    curl -fsSL "$url" | tar --strip-components=2 -xzC "$target_dir" "swagger-ui-${version}/dist/"
}

configure_swagger_ui() {
    local target_dir="$1"
    local datashare_url="$2"
    local initializer="$target_dir/swagger-initializer.js"
    local index="$target_dir/index.html"

    sed -i "/url:/c\\    url: \"http://$datashare_url/api/openapi\"," "$initializer"
    sed -i 's|\./|\./api/|' "$index"
    sed -i 's|index\.css|./api/index.css|' "$index"
}

main() {
    mkdir -p "$APP_DIR/api"
    download_swagger_ui "$SWAGGER_VERSION" "$APP_DIR/api"
    configure_swagger_ui "$APP_DIR/api" "$DATASHARE_URL"
    echo "Swagger UI v${SWAGGER_VERSION} installed to app/api"
    echo "Access at http://${DATASHARE_URL}/api"
}

main
