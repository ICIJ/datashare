#!/bin/bash
# exporting psql db password to not require it for each actions
export PGPASSWORD=test

DB_NAME="datashare"
TABLES=$(psql -h postgres -U test ${DB_NAME} -c "\a" -c "\t" -c "\dt" | awk -F "|" 'NR>2 {print $2}' | grep -v "^databasechangelog*")
for TABLE in $TABLES
do
  # Print table title
  echo "# ${TABLE}"
  # Print the fields titles
  echo "Column | Type | Nullable | Default" 
  echo "--- | --- | --- | --- " 
  # Print the fields
  FIELDS=$(psql -h postgres -U test ${DB_NAME} -c "\a" -c "\t" -c "\d ${TABLE}" | awk -F "|" 'NR>2 {print $1,$2,$4,$5}' OFS="|")
  # Initialize Internal Field Separator
  SAVEIFS=$IFS
  IFS=$(echo -en "\n\b")
  for FIELD in $FIELDS
  do
    echo "${FIELD}" 
  done
  # Save table information
  TABLE_INFO=$(psql -h postgres -U test ${DB_NAME} -c "\a" -c "\d ${TABLE}")
  # Print indexes
  echo "### Constraints and Indexes" 
  INDEXES=$(echo "$TABLE_INFO" | awk -v RS="Referenced by:" -v FS="Indexes:" 'NF>1{print $2}' | awk -v RS="Foreign-key constraints:" 'NF>1{print $0}')
  for INDEX in $INDEXES
  do
    echo "* ${INDEX}" 
  done
  # Print references
  REFERENCES=$(echo "$TABLE_INFO" | awk -v FS="Referenced by:" -v RS="Indexes:" 'NF>1{print $2}')
  if [ -n "${REFERENCES}" ]; then
      echo "### Referenced by" 
      for REFERENCE in $REFERENCES
      do
        echo "* ${REFERENCE}" 
      done
  fi
  # Revert Internal Field Separator
  IFS=$SAVEIFS
done
