#!/bin/bash

export DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
## Find a free port for Java remote debugging
BASE_PORT=8090
port=$BASE_PORT
isfree=$(lsof -i ":$port" -t)

while [ -n "$isfree" ] && [ $port -lt 8093 ]; do
    port=$((port+1))
    isfree=$(lsof -i ":$port" -t)
done

if [ -n "$isfree" ]; then
  echo "Warning : run.sh couldn't find an available port available between 8090 and 8190 for java debugging. Use default 8090, probably already in use"
  port=$BASE_PORT
else
  echo "Use port $port"
fi

export JDWP_TRANSPORT_PORT=${JDWP_TRANSPORT_PORT:-$port}
export DS_JAVA_OPTS="
  -agentlib:jdwp=transport=dt_socket,server=y,address=$JDWP_TRANSPORT_PORT,suspend=n,quiet=y
  -Djava.net.preferIPv4Stack=true
  -Ddatashare.loghost=udp:localhost
  -Dlogback.configurationFile=logback.xml
  -Djavax.net.ssl.trustStorePassword=changeit
"

DATASHARE_POM_VERSION=$(head $DIR/pom.xml | grep '<version>[0-9.]\+' | sed -E 's/<version>([0-9a-z.-]+)<\/version>/\1/' | tr -d '[:space:]')
export DATASHARE_HOME=$DIR
export DATASHARE_VERSION=${DATASHARE_VERSION:-$DATASHARE_POM_VERSION}
export DATASHARE_JAR=${DATASHARE_JAR:-$DIR/datashare-dist/target/datashare-dist-${DATASHARE_VERSION}-all.jar}
export DATASHARE_SYNC_NLP_MODELS=${DATASHARE_SYNC_NLP_MODELS:-true}

if [ ! -f "$DATASHARE_JAR" ]; then
  echo "Datashare's JAR was not found: $DATASHARE_JAR"
  read -p "Would you like to build it? [Y/n] " answer
  if [ -z "$answer" ] || [ "$answer" = "y" ] || [ "$answer" = "Y" ] || [ "$answer" = "yes" ]; then
    make -C "$DIR" build || exit 1
  else
    echo "Run 'make build' first, then try again."
    exit 1
  fi
fi

./datashare-dist/src/main/deb/bin/datashare "$@"
