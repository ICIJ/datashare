#!/bin/bash

API_TOKEN=$1
JOB_NAME=$2

curl -H "Circle-Token: $API_TOKEN" 'https://circleci.com/api/v1.1/project/github/ICIJ/datashare?limit=100' | jq -r ".[] | {start_time, build_time_millis, job_name: .build_parameters.CIRCLE_JOB} | select(.job_name==\"$JOB_NAME\") | [.start_time, .build_time_millis] | @csv" 
