#!/bin/bash

# exporting psql db password to not require it for each actions
export PGUSER=${PGUSER:-test}
export PGPASSWORD=${PGPASSWORD:-test}
export PGDB=${PGDB:-datashare}
export PGHOST=${PGHOST:-postgres}

# Function to get a list of table from PostGreSQL
get_tables() {
  psql -h ${PGHOST} -U ${PGUSER} ${PGDB} -c "\a" -c "\t" -c "\dt" \
    | awk -F "|" 'NR>2 {print $2}' \
    | grep -v "^databasechangelog*"
}

# Function to get table information from PostGreSQL
get_table_info() {
  psql -h ${PGHOST} -U ${PGUSER} ${PGDB} -c "\a" -c "\d $1"
}

# Function to print table fields
print_fields() {
  local TABLE=$1

  # Print the fields titles
  echo "Column | Type | Nullable | Default"
  echo "--- | --- | --- | ---"

  # Get table definition and print fields
  psql -h ${PGHOST} -U ${PGUSER} ${PGDB} -c "\a" -c "\t" -c "\d ${TABLE}" \
    | awk -F "|" 'NR>2 {print "`"$1"`", "`"$2"`", "`"$4"`", "`"$5"`"}' OFS=" | " \
    | sed 's/``//g'

  echo
}

# Function to print indexes
print_indexes() {
  INDEXES=$(
      awk -v RS="Referenced by:" -v FS="Indexes:" 'NF>1{print $2}' \
    | awk -v RS="Foreign-key constraints:" 'NF>1{print $0}')

  if [ -n "${INDEXES}" ]; then
    echo "### Constraints and indexes"
    echo
    echo "$INDEXES" | while IFS= read -r INDEX
    do
      if [ -n "${INDEX}" ]; then
        echo "* \`$(echo "$INDEX" | xargs)\`"
      fi
    done
    echo
  fi
}

# Function to print references
print_references() {
  REFERENCES=$(awk -v FS="Referenced by:" -v RS="Indexes:" 'NF>1{print $0}')

  if [ -n "${REFERENCES}" ]; then
    echo "### Referenced by"
    echo
    echo "$REFERENCES" | while IFS= read -r REFERENCE
    do
      if [ -n "${REFERENCE}" ]; then
        echo "* \`$(echo "$REFERENCE" | xargs)\`"
      fi
    done
    echo
  fi
}


for TABLE in $(get_tables)
do
  # Print table title
  echo "## \`$TABLE\`"
  echo

  # Print the fields titles
  print_fields $TABLE
  # Print indexes
  get_table_info "$TABLE" | print_indexes
  get_table_info "$TABLE" | print_references

  echo "*****"
  echo
done
