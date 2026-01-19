#!/bin/bash
# Wrapper script to run Maven with optional properties from datashare-devenv.properties
# If the properties file exists, its values are passed as -D arguments to Maven.
# Usage: ./datashare-devenv.sh install

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS_FILE="$SCRIPT_DIR/datashare-devenv.properties"

# Read properties and convert to Maven -D arguments (if file exists)
MAVEN_OPTS=""
if [[ -f "$PROPS_FILE" ]]; then
    while IFS='=' read -r key value; do
        # Skip empty lines and comments
        [[ -z "$key" || "$key" =~ ^# ]] && continue
        MAVEN_OPTS="$MAVEN_OPTS -D$key=$value"
    done < "$PROPS_FILE"
fi

exec mvn $MAVEN_OPTS "$@"
