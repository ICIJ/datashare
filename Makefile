VERSION = $(shell head pom.xml | grep '<version>[0-9.]\+' | sed 's/<version>\([0-9.]\+\)<\/version>/\1/g' | tr -d '[:space:]')
DIST_TARGET = datashare-dist/target/datashare-dist-$(VERSION)-docker
DEVENV_PROPERTIES = datashare-devenv.properties
DEVENV_PROPERTIES_TEMPLATE = datashare-devenv.properties.template
MVN = mvn

.PHONY: help build dist install test run clean devenv migrate generate docker release app

help:
	@echo "Datashare Makefile - Available targets:"
	@echo ""
	@echo "  Development:"
	@echo "    devenv    - Create development environment configuration file"
	@echo "    install   - Install dependencies and build all modules (runs migrate first)"
	@echo "    build     - Build distribution JARs (alias for 'dist')"
	@echo "    test      - Run all tests"
	@echo "    run       - Start Datashare (requires 'build' first)"
	@echo "    clean     - Clean all build artifacts"
	@echo "    app       - Download and install frontend (uses VERSION from pom.xml)"
	@echo ""
	@echo "  Database:"
	@echo "    migrate   - Apply database migrations (Liquibase)"
	@echo "    generate  - Generate jOOQ sources (run after schema changes)"
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
install: migrate
	$(MVN) clean install -DskipTests -Dgpg.skip=true

## Build distribution JARs (alias for dist)
build: dist

## Build distribution package
dist: migrate
	$(MVN) clean package -DskipTests

## Run all tests
test:
	$(MVN) test

## Start Datashare locally
run:
	./launchBack.sh

## Download and install frontend from GitHub releases
app:
	@./datashare-app/scripts/download-frontend.sh $(VERSION)

## Clean all build artifacts
clean:
	$(MVN) clean

## Apply database migrations (uses dsbuild DB via postgresBuildUri)
migrate: devenv
	$(MVN) -pl commons-test -am install -DskipTests -Dgpg.skip=true -q
	$(MVN) -pl datashare-db initialize liquibase:update

## Generate sources from database schema (jOOQ)
generate: migrate
	$(MVN) -pl datashare-db generate-sources

## Reset database and reapply all migrations (DESTRUCTIVE)
reset-db: devenv
	bash datashare-db/scripts/reset-datashare-db.sh
	$(MVN) -pl datashare-db liquibase:update

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
