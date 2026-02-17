#!/bin/bash

export DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
export JDWP_TRANSPORT_PORT=${JDWP_TRANSPORT_PORT:-8090}
export DS_JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,address=$JDWP_TRANSPORT_PORT,suspend=n \
  -Djava.net.preferIPv4Stack=true \
  -Ddatashare.loghost=udp:localhost \
  -Dlogback.configurationFile=logback.xml \
  -Djavax.net.ssl.trustStorePassword=changeit"

DATASHARE_POM_VERSION=$(head $DIR/pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9a-z.\-]\+\)<\/version>/\1/g' | tr -d '[:space:]')
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
