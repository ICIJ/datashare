#!/bin/sh
set -e

MAIN_CLASS=org.icij.datashare.cli.DatashareCli

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    source /etc/profile.d/java.sh
    CLASSPATH=$(find /home/datashare/lib/ -name '*.jar' -exec echo {} \+ | sed 's/ /:/g')
    exec java -cp "/home/datashare/dist/:${CLASSPATH}" ${MAIN_CLASS} "$@"
fi
