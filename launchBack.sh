#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

VERSION=$(cat $DIR/pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
CLASSPATH=$DIR/datashare-dist/target/datashare-dist-${VERSION}-all.jar
JDWP_TRANSPORT_PORT=${JDWP_TRANSPORT_PORT:-8000}

if [ -z "$JAVA_HOME" ]; then
  JAVA=java
else
  JAVA=${JAVA_HOME}/bin/java
fi

export DS_DOCKER_PREVIEW_HOST="http://localhost:5000"
export DS_DOCKER_BACK_HOST="http://localhost:$(docker ps|grep 8080|sed 's/.*0.0.0.0:\(.*\)->8080.*/\1/g')"
export DS_DOCKER_FRONT_HOST="http://localhost:$(docker ps|grep 9090|sed 's/.*0.0.0.0:\(.*\)->9090.*/\1/g')"
export DS_DOCKER_USER_ADMIN="icij"

mkdir -p $DIR/dist

# options to force debug logs -Dlogback.debug=true -Dlog4j.debug 

$JAVA -agentlib:jdwp=transport=dt_socket,server=y,address=$JDWP_TRANSPORT_PORT,suspend=n -Djava.system.class.loader=org.icij.datashare.DynamicClassLoader \
 -Djavax.net.ssl.trustStorePassword=changeit \
 -Xmx4g -DPROD_MODE=true -cp "$DIR/dist/:${CLASSPATH}" org.icij.datashare.Main --cors '*' \
 --oauthAuthorizeUrl http://xemx:3001/oauth/authorize \
 --oauthTokenUrl http://xemx:3001/oauth/token \
 --oauthApiUrl http://xemx:3001/api/v1/me.json "$@"
