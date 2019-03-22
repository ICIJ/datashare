#!/usr/bin/env bash

if [[ "$#" -ne 2 ]]; then
    echo "usage: $0 URL PIPELINE"
    exit 1
fi

URL=$1
PIPELINE=$2

read -r -d '' BODY <<EOF
{
  "query":{
    "bool":{
        "must":{
            "match":{"type":"Document"}
        },
        "filter":{
            "terms":{"nerTags":["${PIPELINE}"]}
        }
    }
  }
}
EOF

curl -s "$URL/_count" -H 'Content-Type: application/json' -d "$BODY"
