#!/bin/sh
set -eu

# Validate required environment variables
: "${POSTGRES_SEEDS:?ERROR: POSTGRES_SEEDS environment variable is required}"
: "${POSTGRES_PWD:?ERROR: POSTGRES_PWD environment variable is required}"
: "${POSTGRES_USER:?ERROR: POSTGRES_USER environment variable is required}"
: "${POSTGRES_PORT:?ERROR: POSTGRES_PORT environment variable is required}"
: "${DBNAME:?ERROR: DBNAME environment variable is required}"
: "${VISIBILITY_DBNAME:?ERROR: VISIBILITY_DBNAME environment variable is required}"

setup_postgres() {
  echo 'Creating temporal base DB'

  # Create and setup temporal database
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" create
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned

  echo 'Creating temporal visibility DB'
  # Create and setup temporal database
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" create
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned

  echo 'PostgreSQL schema setup complete'
}


setup_postgres