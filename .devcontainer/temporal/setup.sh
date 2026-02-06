set -e

setup_postgres() {
  echo 'Starting PostgreSQL schema setup...'
  echo 'Waiting for PostgreSQL port to be available...'
  nc -z -w 10 postgres 5432
  echo 'PostgreSQL port is available'

  # Create and setup temporal database
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal create
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned

  # Create and setup visibility database
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal_visibility create
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal_visibility setup-schema -v 0.0
  temporal-sql-tool --plugin postgres12 --ep postgres -u dstest -pw test -p 5432 --db temporal_visibility update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned

  echo 'PostgreSQL schema setup complete'
}

setup_postgres
