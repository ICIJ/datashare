#!/bin/bash
set -e
datashare_version=0.7
redis_container_name=redis:4.0.1-alpine
elasticsearch_container_name=docker.elastic.co/elasticsearch/elasticsearch:6.1.0

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

cat > /tmp/datashare.yml << EOF
version: '2'
services:
  redis:
    image: ${redis_container_name}

  elasticsearch:
    image: ${elasticsearch_container_name}
    volumes:
      - /tmp/elasticsearch.yml:/usr/share/elasticsearch/config/elasticsearch.yml
    ports:
      - "9200:9200"
EOF

docker-compose -f /tmp/datashare.yml -p datashare up -d

read -p "Folder path that contains documents [${PWD}] : " data_path
read -p 'Folder path for cache (datashare will store models here) [/tmp/dist] :' dist_path

data_path=${data_path:-${PWD}}
dist_path=${dist_path:-/tmp/dist}

docker run -ti -p 8080:8080 --network datashare_default -e DS_JAVA_OPTS="${DS_JAVA_OPTS}" \
 -v ${data_path}:/home/datashare/data:ro -v ${dist_path}:/home/datashare/dist icij/datashare:${datashare_version} $@