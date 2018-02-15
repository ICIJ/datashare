#!/bin/bash
set -e
datashare_version=0.7
redis_container_name=redis:4.0.1-alpine
elasticsearch_container_name=docker.elastic.co/elasticsearch/elasticsearch:6.1.0

cat > /tmp/datashare.yml << EOF
version: '2'
services:
  redis:
    image: ${redis_container_name}

  elasticsearch:
    image: ${elasticsearch_container_name}
    environment:
      - "discovery.type=single-node"
    ports:
      - "9200:9200"
EOF

docker-compose -f /tmp/datashare.yml -p datashare up -d

read -p "Folder path that contains documents [${PWD}] : " data_path
read -p 'Folder path for cache (datashare will store models here) [/tmp/dist] :' dist_path

data_path=${data_path:-${PWD}}
dist_path=${dist_path:-/tmp/dist}

mkdir -p dist_path

docker run -ti -u 1000 -p 8080:8080 --network datashare_default \
 -v ${data_path}:/home/datashare/data -v ${dist_path}:/home/datashare/dist icij/datashare:${datashare_version} $@