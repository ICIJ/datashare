#!/bin/sh


for f in META-INF/services/*; do jar uvf datashare-dist/target/datashare-dist-11.1.9-alpha0-all.jar "$f" ; done
