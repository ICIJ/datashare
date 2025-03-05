VERSION = $(shell head pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
DIST_TARGET=datashare-dist/target/datashare-dist-$(VERSION)-docker
PATH_TO_APP_DIST=../datashare-client/dist/

$(DIST_TARGET): dist

clean:
		mvn clean

.PHONY: dist
dist:
		mvn validate package -Dmaven.test.skip=true

build: install validate update-db package

install:
		mvn install

validate:
		mvn validate

package:
		mvn -Dmaven.test.skip=true package

generate-db:
		mvn clean generate-sources

update-db:
		mvn -pl commons-test -am install
		mvn -pl datashare-db liquibase:update

help-db:
		mvn help:describe -DgroupId=org.liquibase -DartifactId=liquibase-maven-plugin -Dversion=2.0.1 -Dfull=true

release-%:
		mvn -pl datashare-$* versions:set -DnewVersion=${NEW_VERSION}
		sed -i "s|<datashare\-$*.version>\([0-9.]\+\)<\/datashare\-$*.version>|<datashare\-$*.version>${NEW_VERSION}<\/datashare\-$*.version>|g" pom.xml
		git commit -am "[release] datashare-$*/${NEW_VERSION} [skip ci]"
		git tag datashare-$*/${NEW_VERSION}
		echo "If everything is OK, you can push with tags i.e. git push origin main --tags"

release:
		mvn versions:set -DnewVersion=${NEW_VERSION}
		git commit -am "[release] ${NEW_VERSION} [skip ci]"
		git tag ${NEW_VERSION}
		echo "If everything is OK, you can push with tags i.e. git push origin main --tags"

docker: $(DIST_TARGET)
		docker build -t icij/datashare:$(VERSION) $(DIST_TARGET)

unit:
		mvn test

run:
		./launchBack.sh
