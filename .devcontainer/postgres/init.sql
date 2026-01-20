-- Create databases for datashare development
-- dstest: created by POSTGRES_DB env var, used by tests
-- dsbuild: used by Maven for migrations and jOOQ code generation

CREATE DATABASE dsbuild OWNER dstest;
