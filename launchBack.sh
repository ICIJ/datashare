#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

VERSION=$(cat $DIR/pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
CLASSPATH=$(find $DIR/datashare-dist/target/datashare-dist-${VERSION}-all/lib/ -name '*.jar' | sort -r | xargs | sed 's/ /:/g')

export DS_DOCKER_PREVIEW_HOST="http://localhost:5000"
export DS_DOCKER_BACK_HOST="http://localhost:$(docker ps|grep 8080|sed 's/.*0.0.0.0:\(.*\)->8080.*/\1/g')"
export DS_DOCKER_FRONT_HOST="http://localhost:$(docker ps|grep 9090|sed 's/.*0.0.0.0:\(.*\)->9090.*/\1/g')"

mkdir -p $DIR/dist

java -agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n -Djavax.net.ssl.trustStorePassword=changeit \
 -Xmx4g -DPROD_MODE=true -cp "$DIR/dist/:${CLASSPATH}" org.icij.datashare.Main --cors '*' \
 --oauthAuthorizeUrl http://xemx:3001/oauth/authorize \
 --oauthTokenUrl http://xemx:3001/oauth/token \
 --oauthApiUrl http://xemx:3001/api/v1/me.json "$@"
