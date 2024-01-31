#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

VERSION=$(cat $DIR/pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9a-z.\-]\+\)<\/version>/\1/g' | tr -d '[:space:]')
CLASSPATH=${CLASSPATH:-$DIR/datashare-dist/target/datashare-dist-${VERSION}-all.jar}
JDWP_TRANSPORT_PORT=${JDWP_TRANSPORT_PORT:-8000}
DS_SYNC_NLP_MODELS=${DS_SYNC_NLP_MODELS:-true}

if [[ $@ == *"SERVER"* ]]; then
  OAUTH_LOCAL_OPTIONS="--oauthAuthorizeUrl http://xemx:3001/oauth/authorize --oauthTokenUrl http://xemx:3001/oauth/token --oauthApiUrl http://xemx:3001/api/v1/me.json"
else
  OAUTH_LOCAL_OPTIONS=""
fi

if [ -z "$JAVA_HOME" ]; then
  JAVA=java
else
  JAVA=${JAVA_HOME}/bin/java
fi

if [[ $(uname) == "Darwin" ]]; then
  DOCKER_PS=docker ps
else
  DOCKER_PS=sudo docker ps
fi

export DS_DOCKER_PREVIEW_HOST="http://localhost:5000"
export DS_DOCKER_BACK_HOST="http://localhost:$($DOCKER_PS|grep 8080|sed 's/.*0.0.0.0:\([0-9]\{4\}\)->8080.*/\1/g')"
export DS_DOCKER_FRONT_HOST="http://localhost:$($DOCKER_PS|grep 9090|sed 's/.*0.0.0.0:\([0-9]\{4\}\)->9090.*/\1/g')"
export DS_DOCKER_USER_ADMIN="icij"

mkdir -p $DIR/dist

$JAVA -agentlib:jdwp=transport=dt_socket,server=y,address=$JDWP_TRANSPORT_PORT,suspend=n \
 --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
 -Djava.system.class.loader=org.icij.datashare.DynamicClassLoader \
 -DDS_SYNC_NLP_MODELS=$DS_SYNC_NLP_MODELS \
 -Djavax.net.ssl.trustStorePassword=changeit \
 -Ddatashare.loghost=udp:localhost -Dlogback.configurationFile=logback.xml \
 -Xmx4g -DPROD_MODE=true -cp "$DIR/dist/:${CLASSPATH}" org.icij.datashare.Main $OAUTH_LOCAL_OPTIONS "$@"
