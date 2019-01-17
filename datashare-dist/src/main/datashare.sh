#!/bin/bash

datashare_version=1.10
redis_image=redis:4.0.1-alpine
elasticsearch_image=docker.elastic.co/elasticsearch/elasticsearch:6.3.0

function create_docker_compose_file {
cat > /tmp/datashare.yml << EOF
version: '2'
services:
  redis:
    image: ${redis_image}

  elasticsearch:
    image: ${elasticsearch_image}
    environment:
      - "http.host=0.0.0.0"
      - "transport.host=0.0.0.0"
      - "cluster.name=datashare"
      - "discovery.type=single-node"
      - "discovery.zen.minimum_master_nodes=1"
      - "xpack.license.self_generated.type=basic"
      - "http.cors.enabled=true"
      - "http.cors.allow-origin=*"
      - "http.cors.allow-methods=OPTIONS, HEAD, GET, POST, PUT, DELETE"
EOF
}

function usage {
  cmd=$(basename $0)
  echo "Usage : ${cmd} [-D data/directory][-M ner/models/directory][-B][-h] datashare args"
  echo "    Data directory: where the documents are located. Default value current dir."
  echo "    Ner models directory: where NER models are stored. Default value /tmp/dist"
  echo "    -B: background mode"
  echo "    -h: this help"
  echo "    datashare args: type ${cmd} without arguments to see the datashare args list"

  exit 1
}

NB_ARGS=0
BACKGROUND=""
while getopts ":D:M:hB" opt
do
  case "${opt}" in
    D)
      data_path=$OPTARG
      ((NB_ARGS=NB_ARGS+2))
      ;;
    M)
      dist_path=$OPTARG
      ((NB_ARGS=NB_ARGS+2))
      ;;
    B)
      ((NB_ARGS=NB_ARGS+1))
      BACKGROUND="-d"
      ;;
    h)
      ((NB_ARGS=NB_ARGS+1))
      usage
      ;;
    :)
      echo "Option -$OPTARG requires an argument." >&2
      usage
      ;;
  esac
done

create_docker_compose_file
docker-compose -f /tmp/datashare.yml -p datashare up -d

data_path=${data_path:-${PWD}}
dist_path=${dist_path:-/tmp/dist}
echo "binding data directory to ${data_path}"
echo "binding NER models directory to ${dist_path}"

shift ${NB_ARGS}
image_running=$(docker inspect --format='{{.Config.Image}}' datashare 2>/dev/null)
if [ -n "${image_running}" ]; then
  docker rm -f datashare > /dev/null
fi

docker run -ti ${BACKGROUND} -p 8080:8080 --network datashare_default --name datashare --rm -e DS_JAVA_OPTS="${DS_JAVA_OPTS}" \
 -v ${data_path}:/home/datashare/data:ro -v ${dist_path}:/home/datashare/dist icij/datashare:${datashare_version} "$@"
