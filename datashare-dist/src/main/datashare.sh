#!/bin/bash

datashare_version=0.45
redis_image=redis:4.0.1-alpine
elasticsearch_image=docker.elastic.co/elasticsearch/elasticsearch:6.3.0

function create_docker_compose_file {
cat > /tmp/datashare.yml << EOF
version: '2'
services:
  redis:
    image: ${redis_image}
    ports:
      - 6379:6379

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
    ports:
      - "9200:9200"
EOF
}

function usage {
  echo "Usage : $0 [-D data_directory][-M ner_models_directory] datashare args"
  exit 1
}

while getopts ":D:M:h" opt; do
  case $opt in
    D)
      data_path=$OPTARG
      shift "$((OPTIND-1))"
      ;;
    M)
      dist_path=$OPTARG
      shift "$((OPTIND-1))"
      ;;
    h)
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

docker run -ti -p 8080:8080 --network datashare_default -e DS_JAVA_OPTS="${DS_JAVA_OPTS}" \
 -v ${data_path}:/home/datashare/data:ro -v ${dist_path}:/home/datashare/dist icij/datashare:${datashare_version} "$@"
