#!/bin/bash

curl -XDELETE elasticsearch:9200/apigen-datashare
./launchBack.sh -p apigen-datashare -m CLI --stages SCAN,INDEX,NLP --nlpp CORENLP -d doc/apigen/docs --dataSourceUrl jdbc:sqlite:file:/home/dev/src/datashare/doc/apigen/apigen.db --queueType memory --busType memory
