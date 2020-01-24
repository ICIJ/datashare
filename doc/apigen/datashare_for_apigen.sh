#!/bin/bash

./launchBack.sh -p apigen-datashare -m CLI -s SCAN,INDEX,NLP --nlpp CORENLP -d doc/apigen/docs --dataSourceUrl jdbc:sqlite:file:/home/dev/src/datashare/doc/apigen/apigen.db --queueType memory --busType memory
./launchBack.sh -u apigen -d doc/apigen/docs --dataSourceUrl jdbc:sqlite:file:/home/dev/src/datashare/doc/apigen/apigen.db --queueType memory --busType memory
