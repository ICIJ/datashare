#!/bin/bash

CLASSPATH=$(find datashare-dist/target/datashare-dist-0.8-all/lib/ -name '*.jar' -exec echo {} \+ | sed 's/ /:/g')

java -agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n -DPROD_MODE=true -cp "dist/:${CLASSPATH}" org.icij.datashare.cli.DatashareCli $*
