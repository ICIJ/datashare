#!/bin/sh

SWAGGER_VERSION=5.18.2
DATASHARE_URL=localhost:8888

mkdir -p app/api
curl -s -L https://github.com/swagger-api/swagger-ui/archive/refs/tags/v${SWAGGER_VERSION}.tar.gz | tar --strip-components=2 -xzC app/api  swagger-ui-${SWAGGER_VERSION}/dist/
sed -i "/url:/c\    url: \"http://$DATASHARE_URL/api/openapi\"," app/api/swagger-initializer.js && sed -i 's|\./|\./api/|' app/api/index.html && sed -i 's|index\.css|./api/index.css|' app/api/index.html

echo "Swagger-ui installed. Check $DATASHARE_URL/api"
