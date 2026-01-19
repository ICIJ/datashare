#!/bin/sh
export PGPASSWORD=admin

psql -h postgres -Uadmin admin -c 'drop database datashare'
psql -h postgres -Uadmin admin -c 'drop database dstest'
psql -h postgres -Uadmin admin -c 'REVOKE ALL ON schema public FROM dstest;drop user dstest;'

psql -h postgres -Uadmin admin -c 'create database datashare'
psql -h postgres -Uadmin admin -c "create user dstest with password 'test'"
psql -h postgres -Uadmin datashare -c 'grant all on schema public to dstest'
psql -h postgres -Uadmin admin -c "grant all on database datashare to dstest"

psql -h postgres -Uadmin admin -c 'create database dstest'
psql -h postgres -Uadmin dstest -c 'grant all on schema public to dstest'
psql -h postgres -Uadmin admin -c "grant all on database dstest to dstest"
