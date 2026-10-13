#!/bin/bash

curl -XDELETE elasticsearch:9200/apigen-datashare
./launchBack.sh -p apigen-datashare -m CLI --stages SCAN,INDEX,NLP --nlpp CORENLP -d $PWD/docs/apigen/docs --dataSourceUrl jdbc:sqlite:$PWD/docs/apigen/apigen.db
