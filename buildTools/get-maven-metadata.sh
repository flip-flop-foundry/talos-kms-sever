#!/bin/bash

# Intended to be run inside a Maven project directory
# Outputs Maven project metadata to GitHub Actions output and env files

PARAMS=(
"project.version:MAVEN_VERSION"
"project.artifactId:APP_NAME"
"project.description:DESCRIPTION"
"project.organization.name:VENDOR"
"project.developers[0].email:MAINTAINER_EMAIL"

)
cd ../

for entry in "${PARAMS[@]}"; do
  IFS=':' read -r EXPR OUT <<< "$entry"
  VALUE=$(mvn -q -DforceStdout help:evaluate -Dexpression="$EXPR" 2>/dev/null || true)
  VALUE=$(echo -n "$VALUE" | tr -d '\r\n')
  echo "Determined $EXPR: $VALUE"
  if [ -z "$VALUE" ]; then
    echo "Could not determine $EXPR via Maven (docker)" >&2
    exit 1
  fi
  echo "${OUT}=${VALUE}" >> $GITHUB_OUTPUT
  echo "${OUT}=${VALUE}" >> $GITHUB_ENV
done