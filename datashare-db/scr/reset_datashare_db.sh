#!/bin/sh

psql -h postgres -Utest test -c 'drop database datashare'
psql -h postgres -Utest test -c 'create database datashare'
