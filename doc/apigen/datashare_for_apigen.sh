#!/bin/bash

mkdir /tmp/plugins
./launchBack.sh -p apigen-datashare -u apigen -d $PWD/doc/apigen/docs --dataSourceUrl jdbc:sqlite:file:$PWD/doc/apigen/apigen.db --pluginsDir /tmp/plugins
