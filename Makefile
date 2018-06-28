VERSION = $(shell cat pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
DIST_TARGET=datashare-dist/target/datashare-dist-$(VERSION)-all
PATH_TO_APP_DIST=../datashare-client/dist/

$(DIST_TARGET): dist

clean:
		mvn clean

.PHONY: dist
dist:
		mvn validate package -Dmaven.test.skip=true

install:
		mvn install

version:
		mvn versions:set -Dnew

docker: $(DIST_TARGET)
		cp -a $(PATH_TO_APP_DIST) $(DIST_TARGET)/app || exit 1
		docker build -t icij/datashare:$(VERSION) $(DIST_TARGET)

unit:
		mvn test
