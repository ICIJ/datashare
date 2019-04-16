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

help-db:
		mvn help:describe -DgroupId=org.liquibase -DartifactId=liquibase-maven-plugin -Dversion=2.0.1 -Dfull=true

release:
		mvn versions:set -DnewVersion=${NEW_VERSION}
		git commit -am "[release] ${NEW_VERSION}"
		git tag ${NEW_VERSION}
		echo "If everything is OK, you can push with tags i.e. git push origin master --tags"

docker: $(DIST_TARGET)
		cp -a $(PATH_TO_APP_DIST) $(DIST_TARGET)/app || exit 1
		docker build -t icij/datashare:$(VERSION) $(DIST_TARGET)

unit:
		mvn test
