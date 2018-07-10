#!/bin/bash
VERSION=$(cat pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')

CLASSPATH=$(find datashare-dist/target/datashare-dist-${VERSION}-all/lib/ -name '*.jar' -exec echo {} \+ | sed 's/ /:/g')

java -agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n -DPROD_MODE=true -cp "dist/:${CLASSPATH}" org.icij.datashare.cli.DatashareCli --cors '*' $*
