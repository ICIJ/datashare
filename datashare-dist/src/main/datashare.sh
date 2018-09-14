#!/bin/bash

datashare_version=0.30
redis_image=redis:4.0.1-alpine
elasticsearch_image=docker.elastic.co/elasticsearch/elasticsearch:6.3.0

function create_elasticsearch_config_file {
cat > /tmp/elasticsearch.yml << EOF
http.host: 0.0.0.0
transport.host: 0.0.0.0
cluster.name: "datashare"
discovery.type: "single-node"
discovery.zen.minimum_master_nodes: 1
xpack.license.self_generated.type: basic
# CORS
http.cors.enabled : true
http.cors.allow-origin : "*"
http.cors.allow-methods : OPTIONS, HEAD, GET, POST, PUT, DELETE
EOF
}

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
    volumes:
      - /tmp/elasticsearch.yml:/usr/share/elasticsearch/config/elasticsearch.yml
    ports:
      - "9200:9200"
EOF
}

function wait_idx_is_up {
    echo -n "waiting for index to be up..."
    for i in `seq 1 300`; do
        sleep 0.1
        curl --silent localhost:9200 > /dev/null
        if [ $? -eq 0 ]; then
            echo "OK"
            return
        fi
    done
    echo "KO"
}

create_elasticsearch_config_file
create_docker_compose_file
docker-compose -f /tmp/datashare.yml -p datashare up -d

read -p "Folder path that contains documents [${PWD}] : " data_path
read -p 'Folder path for cache (datashare will store models here) [/tmp/dist] :' dist_path

data_path=${data_path:-${PWD}}
dist_path=${dist_path:-/tmp/dist}

wait_idx_is_up

docker run -ti -p 8080:8080 --network datashare_default -e DS_JAVA_OPTS="${DS_JAVA_OPTS}" \
 -v ${data_path}:/home/datashare/data:ro -v ${dist_path}:/home/datashare/dist icij/datashare:${datashare_version} "$@"
