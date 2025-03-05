#!/bin/bash

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <artifact_id_as_displayed_by_mvn_tree>"
  echo "Example: $0 org.icij.datashare:datashare-api:jar:14.5.0:provided"
  exit 1
fi

ARTIFACT_ID=$(echo "$1" | awk -F ':' '{print $1":"$2":"$4":"$3}')


if mvn dependency:get -Dartifact="$ARTIFACT_ID" > /dev/null 2>&1; then
  echo "$ARTIFACT_ID is already deployed."
  exit 0
else
  echo "$ARTIFACT_ID is not deployed. Please make sure that $ARTIFACT_ID has been deployed before."
  exit 1
fi