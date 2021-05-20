#!/usr/bin/env bash

ES_URL=$1
SOURCE_INDEX=$2
TARGET_INDEX=$3

echo '{"settings":' > /tmp/settings_mappings.json
cat ../src/main/resources/datashare_index_settings.json >> /tmp/settings_mappings.json
echo ', "mappings":' >> /tmp/settings_mappings.json
curl -s "$ES_URL/$SOURCE_INDEX/_mapping" | jq --arg idx "$SOURCE_INDEX" '.[$idx].mappings' >> /tmp/settings_mappings.json
echo '}' >> /tmp/settings_mappings.json

curl -XPUT -H 'Content-Type: application/json' "$ES_URL/$TARGET_INDEX" -d @/tmp/settings_mappings.json
