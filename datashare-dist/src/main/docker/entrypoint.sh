#!/bin/sh
set -e

MAIN_CLASS=org.icij.datashare.Main

join_by() {
    local d=${1-} f=${2-}
    if shift 2; then
        printf %s "$f" "${@/#/$d}"
    fi
}

filter_classpath() {
    local filtered
    filtered=()
    for path in $(echo "$CLASSPATH" | tr ":" "\n"); do
        # Lets expand the path and add matching JARS
        for jar in $path; do
            if [[ $jar = *datashare-extension-*.jar ]]; then
                # Let's put back the unexpended path
                filtered+=("$path")
            fi
        done
    done
    join_by ":" "${filtered[@]}"
}

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    # Let's filter the CLASSPATH to keep only DS extension
    if [[ "$CLASSPATH" ]]; then
      EXTENSION_CLASSPATH=$(filter_classpath "$CLASSPATH")
    fi
    DS_CLASSPATH="$(find /home/datashare/lib/ -name '*.jar' -print0 | xargs | sed 's/ /:/g')${EXTENSION_CLASSPATH:+:$EXTENSION_CLASSPATH}"
    exec java "${DS_JAVA_OPTS}" -DPROD_MODE=true -Djava.system.class.loader=org.icij.datashare.DynamicClassLoader -cp "/home/datashare/dist/:${DS_CLASSPATH}" ${MAIN_CLASS} "$@"
fi
