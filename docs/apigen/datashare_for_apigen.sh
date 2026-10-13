#!/bin/bash

mkdir /tmp/plugins
./launchBack.sh -p apigen-datashare -u apigen -d $PWD/docs/apigen/docs --dataSourceUrl jdbc:sqlite:file:$PWD/docs/apigen/apigen.db --pluginsDir /tmp/plugins
