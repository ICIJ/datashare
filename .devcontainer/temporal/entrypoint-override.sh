#!/bin/sh
set -eu
echo "Setting up temporal DB..."
/etc/temporal/.config/temporalio/setup.sh

echo "Starting temporal server..."
/etc/temporal/entrypoint.sh