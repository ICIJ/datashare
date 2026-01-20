#!/bin/bash
# Reset datashare databases using configuration from datashare-devenv.properties
# This script drops and recreates the test and build databases.

set -e

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
props_file="$script_dir/../../datashare-devenv.properties"

if [[ ! -f "$props_file" ]]; then
    echo "Error: $props_file not found. Run 'make devenv' first." >&2
    exit 1
fi

# Parse a JDBC PostgreSQL URI and extract components
# Usage: parse_jdbc_uri "jdbc:postgresql://host/db?user=u&password=p"
# Sets: db_host, db_name, db_user, db_pass
parse_jdbc_uri() {
    local uri="$1"
    # Remove jdbc:postgresql:// prefix
    local rest="${uri#jdbc:postgresql://}"
    # Extract host (before /)
    db_host="${rest%%/*}"
    rest="${rest#*/}"
    # Extract database name (before ?)
    db_name="${rest%%\?*}"
    rest="${rest#*\?}"
    # Extract user and password from query params
    db_user=$(echo "$rest" | sed -n 's/.*user=\([^&]*\).*/\1/p')
    db_pass=$(echo "$rest" | sed -n 's/.*password=\([^&]*\).*/\1/p')
}

# Read properties from file
get_property() {
    grep "^$1=" "$props_file" | cut -d'=' -f2-
}

# Drop and recreate a database
# Usage: reset_database host user password database
reset_database() {
    local host="$1" user="$2" password="$3" db="$4"
    export PGPASSWORD="$password"
    psql -h "$host" -U "$user" -d postgres -c "DROP DATABASE IF EXISTS $db" || true
    psql -h "$host" -U "$user" -d postgres -c "CREATE DATABASE $db OWNER $user"
    echo "  Created $db (user: $user) on $host"
}

# Get URIs from properties file
postgres_uri=$(get_property "postgresUri")
postgres_build_uri=$(get_property "postgresBuildUri")

if [[ -z "$postgres_uri" ]]; then
    echo "Error: postgresUri not found in $props_file" >&2
    exit 1
fi

if [[ -z "$postgres_build_uri" ]]; then
    echo "Error: postgresBuildUri not found in $props_file" >&2
    exit 1
fi

# Parse and reset test database
parse_jdbc_uri "$postgres_uri"
echo "Resetting databases..."
reset_database "$db_host" "$db_user" "$db_pass" "$db_name"

# Parse and reset build database
parse_jdbc_uri "$postgres_build_uri"
reset_database "$db_host" "$db_user" "$db_pass" "$db_name"

echo "Done."
