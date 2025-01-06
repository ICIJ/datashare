#!/bin/bash

web_package_has_changed=$(git diff-index --exit-code HEAD~1 datashare-app/src/main/java/org/icij/datashare/web/)
db_package_has_changed=$(git diff-index --exit-code HEAD~1 datashare-db/src/main/resources/liquibase/)
CIRCLE_API="https://circleci.com/api"

if [[ -n "$web_package_has_changed" || -n "$db_package_has_changed" ]]; then
  echo >&2 "Something changed in web package"
  URL="${CIRCLE_API}/v2/project/github/ICIJ/datashare/pipeline"
  DATA='{"branch": "main", "parameters": { "publish_backend_doc": true}}'
  HTTP_RESPONSE=$(curl -s -u "${CIRCLE_API_USER_TOKEN}:" -o response.txt -w "%{http_code}" -X POST --header "Content-Type: application/json" -d "$DATA" "$URL")

  if [ "$HTTP_RESPONSE" -ge "200" ] && [ "$HTTP_RESPONSE" -lt "300" ]; then
    echo "API call succeeded."
    echo "Response:"
    cat response.txt
  else
    echo -e "\e[93mReceived status code: ${HTTP_RESPONSE}\e[0m"
    echo "Response:"
    cat response.txt
    exit 1
  fi
else
  echo >&2 "Nothing to publish"
fi
