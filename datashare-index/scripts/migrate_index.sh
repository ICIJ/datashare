#!/usr/bin/env bash

if [[ "$#" -ne 2 ]]; then
    echo "usage: $0 INDEX_URL INDEX_NAME"
    echo "example: $0 http://localhost:9200 my_index"
    exit 1
fi

INDEX_URL=$1
INDEX_NAME=$2
INDEX_COPY="$INDEX_NAME-copy"

echo "creating $INDEX_COPY index"
./create_index.sh "$INDEX_URL/$INDEX_COPY"
echo

echo "reindexing $INDEX_NAME into $INDEX_COPY (that could take a while)"
reindex_result=$(curl -s -XPOST -H 'Content-Type: application/json' "$INDEX_URL/_reindex" --max-time 1800 -d "{\"source\": {\"index\": \"$INDEX_NAME\"}, \"dest\":{\"index\": \"$INDEX_COPY\"}}")
if [[ "$reindex_result" -ne 0 ]]; then
  echo "curl returned $reindex_result exiting..."
  exit "$reindex_result"
fi

echo "deleting $INDEX_NAME index"
read -rp "Are you sure (WARNING you need to make sure that the reindex is completed) ?[Ny]" delete_index

if [[ "$delete_index" != "y" ]]; then
  echo "exiting..."
  exit 0
fi

curl -XDELETE -H 'Content-Type: application/json' "$INDEX_URL/$INDEX_NAME"
echo

echo "re-creating $INDEX_NAME index"
./create_index.sh "$INDEX_URL/$INDEX_NAME"
echo

echo "reindexing $INDEX_COPY into $INDEX_NAME (that could take a while again)"
reindex_result=$(curl -s -XPOST -H 'Content-Type: application/json' "$INDEX_URL/_reindex" --max-time 1800 -d "{\"source\": {\"index\": \"$INDEX_COPY\"}, \"dest\":{\"index\": \"$INDEX_NAME\"}}")
if [[ "$reindex_result" -ne 0 ]]; then
  echo "curl returned $reindex_result exiting..."
  exit "$reindex_result"
fi

echo "deleting $INDEX_COPY index"
read -rp "Are you sure ?[Ny]" delete_index

if [[ "$delete_index" != "y" ]]; then
  echo "exiting..."
  exit 0
fi

curl -XDELETE -H 'Content-Type: application/json' "$INDEX_URL/$INDEX_COPY"
echo
