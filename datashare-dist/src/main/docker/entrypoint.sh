#!/bin/sh
set -e

java_bin=${JAVA_HOME:-/usr}/bin/java
java_opts=${DS_JAVA_OPTS:-''}
datashare_home=${DATASHARE_HOME:-$HOME/.local/share/datashare}
datashare_jars=$(find $datashare_home/lib/ -name '*.jar' | xargs | sed 's/ /:/g')
datashare_jna_tmpdir=${DATASHARE_JNA_TMPDIR:-$datashare_home/index/tmp}
datashare_sync_nlp_models=${DATASHARE_SYNC_NLP_MODELS:-true}
datashare_class_path="$datashare_home/dist:$datashare_jars:$datashare_home/extensions/*${CLASSPATH:+:$CLASSPATH}"

# OpenMP thread limit (process-wide): cap each Tesseract child to one OpenMP
# thread so concurrent OCR (one per INDEX worker) does not oversubscribe the
# CPU and trip Tika's 120s timeout. Exported process-wide, so it also reaches
# any other OpenMP/BLAS native code in the JVM or its children (e.g. the spaCy
# NLP worker); single-thread-per-worker is intended there too. Override by
# exporting these yourself.
export OMP_THREAD_LIMIT=${OMP_THREAD_LIMIT-1}
export OMP_WAIT_POLICY=${OMP_WAIT_POLICY-passive}

if [ "$1" = 'sh' ];
then
    exec "$@"
else
    exec $java_bin $java_opts \
      --add-opens java.base/java.lang=ALL-UNNAMED \
      --add-opens java.base/java.util=ALL-UNNAMED \
      --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED \
      --add-opens java.base/java.net=ALL-UNNAMED \
      -DPROD_MODE=true \
      -Dfile.encoding=UTF-8 \
      -Djava.system.class.loader=org.icij.datashare.DynamicClassLoader \
      -Djna.tmpdir=$datashare_jna_tmpdir \
      -DDS_SYNC_NLP_MODELS=$datashare_sync_nlp_models \
      -cp "$datashare_class_path" org.icij.datashare.Main \
        "$@"
fi
