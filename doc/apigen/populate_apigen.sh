#!/bin/bash

curl -XDELETE elasticsearch:9200/apigen-datashare
./launchBack.sh -p apigen-datashare -m CLI --stages SCAN,INDEX,NLP --nlpp CORENLP -d $PWD/doc/apigen/docs --dataSourceUrl jdbc:sqlite:$PWD/doc/apigen/apigen.db --queueType memory --busType memory
