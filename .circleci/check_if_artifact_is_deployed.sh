#!/bin/bash

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <group_id> <artifact_id> <version>"
  exit 1
fi

GROUP_ID="$1"
ARTIFACT_ID="$2"
VERSION="$3"

if mvn dependency:get -Dartifact="$GROUP_ID:$ARTIFACT_ID:$VERSION" > /dev/null 2>&1; then
  echo "$GROUP_ID:$ARTIFACT_ID:$VERSION is already deployed."
  exit 0
else
  echo "$GROUP_ID:$ARTIFACT_ID:$VERSION is not deployed. Please make sure that $GROUP_ID:$ARTIFACT_ID:$VERSION jar has been deployed before."
  exit 1
fi