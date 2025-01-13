#!/bin/sh

SWAGGER_VERSION=5.18.2

mkdir -p app/api
curl -s -L https://github.com/swagger-api/swagger-ui/archive/refs/tags/v${SWAGGER_VERSION}.tar.gz | tar --strip-components=2 -xzC app/api  swagger-ui-${SWAGGER_VERSION}/dist/
sed -i '/url:/c\    url: "http://localhost:8888/api/openapi",' app/api/swagger-initializer.js && sed -i 's|\./|\./api/|' app/api/index.html && sed -i 's|index\.css|./api/index.css|' app/api/index.html
