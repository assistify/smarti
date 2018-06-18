#!/usr/bin/env bash

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_KEY}"
export AWS_DEFAULT_REGION="${AWS_REGION}"
cd ${DEPLOY_PATH}
./aws.sh s3 cp ${BUILD_FILE} s3://${AWS_BUCKET}/redlink/ --region ${AWS_REGION} --acl bucket-owner-full-control
# For dedicated branches, we tag the artifacts - this should actually be based on git tags
TARGET_ENVIRONMENT=undefined
if [ ${BRANCH} = master ]
  then
      TARGET_ENVIRONMENT=production
      # publish a new "latest"-file in order to make new clients be created with it
      ./aws.sh s3 cp ${BUILD_FILE} s3://${AWS_BUCKET}/redlink/assistify-smarti-latest.rpm --region ${AWS_REGION} --acl bucket-owner-full-control
  else
    if [[ ${BRANCH} == develop ]] || [[ $BRANCH == "release/"* ]]
      then
        TARGET_ENVIRONMENT=test
    fi
  fi
./aws.sh s3api put-object-tagging --region ${AWS_REGION} --bucket ${AWS_BUCKET} --key rocketchat/${BUILD_FILE} --tagging "{ \"TagSet\": [ { \"Key\": \"environment\", \"Value\": \"${TARGET_ENVIRONMENT}\" }, { \"Key\": \"nodejs_version\", \"Value\": \"${NODEJS_VERSION}\" }, { \"Key\": \"nodejs_checksum\", \"Value\": \"${NODEJS_CHECKSUM}\" }, { \"Key\": \"assets\", \"Value\": \"${ASSETS_URL}\" } ] }"