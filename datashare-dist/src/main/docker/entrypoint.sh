#!/bin/sh
set -e

MAIN_CLASS=org.icij.datashare.Main

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    CLASSPATH=$(find /home/datashare/lib/ -name '*.jar' | xargs | sed 's/ /:/g')${CLASSPATH:+:$CLASSPATH}
    # shellcheck disable=SC2086
    # https://github.com/koalaman/shellcheck/wiki/Sc2086
    exec java ${DS_JAVA_OPTS} \
      -DPROD_MODE=true \
      -Djava.system.class.loader=org.icij.datashare.DynamicClassLoader \
      -cp "/home/datashare/dist/:${CLASSPATH}" \
      ${MAIN_CLASS} "$@"
fi
