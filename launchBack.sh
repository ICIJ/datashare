#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
JDWP_TRANSPORT_PORT=${JDWP_TRANSPORT_PORT:-8090}
JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,address=$JDWP_TRANSPORT_PORT,suspend=n \
  -Djava.net.preferIPv4Stack=true \
  -Ddatashare.loghost=udp:localhost \
  -Dlogback.configurationFile=logback.xml \
  -Djavax.net.ssl.trustStorePassword=changeit"

DATASHARE_VERSION=$(cat $DIR/pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9a-z.\-]\+\)<\/version>/\1/g' | tr -d '[:space:]')
DATASHARE_JAR=${DATASHARE_JAR:-$DIR/datashare-dist/target/datashare-dist-${DATASHARE_VERSION}-all.jar}
DATASHARE_SYNC_NLP_MODELS=${DATASHARE_SYNC_NLP_MODELS:-true}

./datashare-dist/src/main/deb/bin/datashare $@