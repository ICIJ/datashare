#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <project_name>"
  exit 1
fi

mvn clean install -pl "$1" -am -s "$(dirname "${BASH_SOURCE[0]}")/maven-release-settings.xml" -Dexec.executable='echo' -Dmaven.test.skip=true -Dexec.args='${project.artifactId} ${project.version}' exec:exec -q 2>&1 | grep -v '\[.*\]' | head -n -1