#!/bin/bash

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <artifact_id_as_displayed_by_mvn_tree>"
  echo "Example: $0 org.icij.datashare:datashare-api:jar:14.5.0:provided"
  exit 1
fi

GROUP_ID=$(echo "$1" | awk -F ':' '{print $1}')
ARTIFACT_ID=$(echo "$1" | awk -F ':' '{print $2}')
PACKAGING=$(echo "$1" | awk -F ':' '{print $3}')
VERSION=$(echo "$1" | awk -F ':' '{print $4}')
GROUP_PATH=$(echo "$GROUP_ID" | tr '.' '/')

URL="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.pom"
status_code=$(curl -s -o /dev/null -I -w "%{http_code}" "$URL")


if [[ "$status_code" -eq 200 ]]; then
  echo "$GROUP_ID:$ARTIFACT_ID:$PACKAGING:$VERSION is already deployed."
  exit 0
else
  echo "$GROUP_ID:$ARTIFACT_ID:$PACKAGING:$VERSION is not deployed. Please make sure that it has been deployed before."
  exit 1
fi