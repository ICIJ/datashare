#!/bin/sh
set -e

MAIN_CLASS=org.icij.datashare.Main

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    CLASSPATH=$(ls /home/datashare/lib/)
    exec java "${DS_JAVA_OPTS}" -DPROD_MODE=true -cp "/home/datashare/dist/:${CLASSPATH}" ${MAIN_CLASS} "$@"
fi
