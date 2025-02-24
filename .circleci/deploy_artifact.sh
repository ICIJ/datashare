#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <project_name>"
  exit 1
fi

SCRIPT_DIR=$(dirname "${BASH_SOURCE[0]}")
DEPENDENT_ARTIFACTS="$SCRIPT_DIR/get_maven_dependent_projects.sh $1"

while IFS= read -r line; do
  ARTIFACT_ID=$(echo "$line" | awk '{print $1}')
  VERSION=$(echo "$line" | awk '{print $2}')

  if "$SCRIPT_DIR/check_if_artifact_is_deployed.sh org.icij.datashare $ARTIFACT_ID $VERSION"; then
    continue
  else
    mvn -s "$(dirname "${BASH_SOURCE[0]}")/maven-release-settings.xml" -pl "$ARTIFACT_ID" deploy
  fi

  deploy_artifact "$GROUP_ID" "$ARTIFACT_ID" "$VERSION"
done < "$DEPENDENT_ARTIFACTS"