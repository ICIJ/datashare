#!/bin/bash
set -e

# Validate required environment variables
: "${POSTGRES_SEEDS:?ERROR: POSTGRES_SEEDS environment variable is required}"
: "${POSTGRES_PWD:?ERROR: POSTGRES_PWD environment variable is required}"
: "${POSTGRES_USER:?ERROR: POSTGRES_USER environment variable is required}"
: "${POSTGRES_PORT:?ERROR: POSTGRES_PORT environment variable is required}"
: "${POSTGRES_STARTUP_TIMEOUT:?ERROR: POSTGRES_STARTUP_TIMEOUT environment variable is required}"
: "${DBNAME:?ERROR: DBNAME environment variable is required}"
: "${VISIBILITY_DBNAME:?ERROR: VISIBILITY_DBNAME environment variable is required}"

wait_for_postgres() {
  echo 'Creating temporal base DB'
  local max_attempts
  max_attempts=$POSTGRES_STARTUP_TIMEOUT
  local attempt
  attempt=0
  until nc -z "$POSTGRES_SEEDS" "$POSTGRES_PORT"; do
    if [ $attempt -ge "$max_attempts" ]; then
      echo "ERROR: Fail to reach Postgres in less than $max_attempts seconds"
      exit 1
    fi
    echo 'Waiting for postgres to be up...'
    sleep 1
  done
}

setup_postgres() {
  # Create and setup temporal database
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" create
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${DBNAME}" update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned

  echo 'Creating temporal visibility DB'
  # Create and setup temporal database
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" create
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -pw "${POSTGRES_PWD}" -p "${POSTGRES_PORT}" --db "${VISIBILITY_DBNAME}" update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned

  echo 'PostgresSQL schema setup complete'
}

wait_for_postgres
setup_postgres
