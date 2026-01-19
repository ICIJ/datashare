VERSION = $(shell head pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
DIST_TARGET = datashare-dist/target/datashare-dist-$(VERSION)-docker
DEVENV_PROPERTIES = datashare-devenv.properties
DEVENV_PROPERTIES_TEMPLATE = datashare-devenv.properties.template

.PHONY: help build dist install test run clean devenv migrate generate docker release

help:
	@echo "Datashare Makefile - Available targets:"
	@echo ""
	@echo "  Development:"
	@echo "    devenv    - Create development environment configuration file"
	@echo "    install   - Install dependencies and build all modules"
	@echo "    build     - Build distribution JARs (alias for 'dist')"
	@echo "    test      - Run all tests"
	@echo "    run       - Start Datashare (requires 'build' first)"
	@echo "    clean     - Clean all build artifacts"
	@echo ""
	@echo "  Database:"
	@echo "    migrate   - Apply database migrations (Liquibase)"
	@echo "    generate  - Generate sources from database (jOOQ)"
	@echo "    reset-db  - Reset database and reapply migrations (DESTRUCTIVE)"
	@echo ""
	@echo "  Release:"
	@echo "    release   - Create a new release (requires NEW_VERSION=x.y.z)"
	@echo "    docker    - Build Docker image"
	@echo ""

## Create development environment configuration
devenv: $(DEVENV_PROPERTIES)
	@echo "Development environment ready: $(DEVENV_PROPERTIES)"

$(DEVENV_PROPERTIES):
	cp $(DEVENV_PROPERTIES_TEMPLATE) $(DEVENV_PROPERTIES)

## Install dependencies and build all modules
install: devenv
	mvn clean install -DskipTests

## Build distribution JARs (alias for dist)
build: dist

## Build distribution package
dist: devenv
	mvn clean package -DskipTests

## Run all tests
test: devenv
	mvn test

## Start Datashare locally
run:
	./launchBack.sh

## Clean all build artifacts
clean:
	mvn clean

## Apply database migrations
migrate: devenv
	mvn -pl commons-test -am install -DskipTests -q
	mvn -pl datashare-db liquibase:update

## Generate sources from database schema (jOOQ)
generate: migrate
	mvn -pl datashare-db generate-sources

## Reset database and reapply all migrations (DESTRUCTIVE)
reset-db:
	bash datashare-db/scr/reset_datashare_db.sh
	mvn -pl datashare-db liquibase:update

## Create a new release (usage: make release NEW_VERSION=x.y.z)
release:
ifndef NEW_VERSION
	$(error NEW_VERSION is required. Usage: make release NEW_VERSION=x.y.z)
endif
	mvn versions:set -DnewVersion=$(NEW_VERSION)
	git commit -am "[release] $(NEW_VERSION) [skip ci]"
	git tag $(NEW_VERSION)
	@echo "Release $(NEW_VERSION) created. Push with: git push origin main --tags"

## Create a module-specific release (usage: make release-api NEW_VERSION=x.y.z)
release-%:
ifndef NEW_VERSION
	$(error NEW_VERSION is required. Usage: make release-$* NEW_VERSION=x.y.z)
endif
	mvn -pl datashare-$* versions:set -DnewVersion=$(NEW_VERSION)
	sed -i "s|<datashare\-$*.version>\([0-9.]\+\)<\/datashare\-$*.version>|<datashare\-$*.version>$(NEW_VERSION)<\/datashare\-$*.version>|g" pom.xml
	git commit -am "[release] datashare-$*/$(NEW_VERSION) [skip ci]"
	git tag datashare-$*/$(NEW_VERSION)
	@echo "Release datashare-$*/$(NEW_VERSION) created. Push with: git push origin main --tags"

## Build Docker image
docker: $(DIST_TARGET)
	docker build -t icij/datashare:$(VERSION) $(DIST_TARGET)

$(DIST_TARGET): dist
