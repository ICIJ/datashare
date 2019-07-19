#!/usr/bin/env bash

INDEX_URL=$1

SETTINGS=$(<../src/main/resources/datashare_index_settings.json)
MAPPINGS=$(<../src/main/resources/datashare_index_mappings.json)

curl -XPUT -H 'Content-Type: application/json' $INDEX_URL -d "\
{
\"settings\":$SETTINGS,
\"mappings\":$MAPPINGS
}"
