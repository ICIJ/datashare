#!/bin/sh
set -e

MAIN_CLASS=org.icij.datashare.Main

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    CLASSPATH=$(find /home/datashare/lib/ -name '*.jar' | sort -r | xargs | sed 's/ /:/g')
    exec java ${DS_JAVA_OPTS} -DPROD_MODE=true -cp "/home/datashare/dist/:${CLASSPATH}" ${MAIN_CLASS} "$@"
fi
