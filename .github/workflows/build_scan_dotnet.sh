#!/bin/bash

# This script expects 2 environment variables to be set:
# * SONAR_TOKEN
# * SONAR_HOST_URL
# Optional:
# * SONAR_REGION (for SonarCloud US, set to "us")

set -euo pipefail

maven_expression() {
  if ! mvn -q -Dexec.executable="echo" -Dexec.args="\${$1}" --non-recursive org.codehaus.mojo:exec-maven-plugin:1.3.1:exec; then
    echo "Failed to evaluate Maven expression '$1'" >&2
    mvn -X -Dexec.executable="echo" -Dexec.args="\${$1}" --non-recursive org.codehaus.mojo:exec-maven-plugin:1.3.1:exec
    return 1
  fi
}

# Read the version from the root pom
sonarProjectVersion=$(maven_expression "project.version" | sed 's/-SNAPSHOT//')
echo "Sonar project version is '${sonarProjectVersion}'"

# Set the variable so it can be used by other tasks
SONAR_PROJECT_VERSION=${sonarProjectVersion}

# Restore the .NET project using Repox as source feed
dotnet restore omnisharp-dotnet/SonarLint.OmniSharp.DotNet.Services.sln \
  --locked-mode \
  --configfile omnisharp-dotnet/nuget.config

REGION_FLAG=$([ "${SONAR_REGION:-}" = "us" ] && echo "-d:sonar.region=$SONAR_REGION" || echo "")

# Setup SonarQube scan
dotnet sonarscanner begin \
  -d:sonar.token=${SONAR_TOKEN} \
  $REGION_FLAG \
  -o:sonarsource \
  -d:sonar.projectBaseDir="$GITHUB_WORKSPACE/omnisharp-dotnet/src" \
  -k:"sonarlint-omnisharp-dotnet" \
  -v:${SONAR_PROJECT_VERSION} \
  -d:sonar.cs.opencover.reportsPaths="./**/TestResults/coverage.**.xml"

# Get OmniSharp fork from Repox
mvn generate-resources -B -Denable-repo=qa -DskipIts -Pdownload-omnisharp-for-building

# Build and run .NET unit tests
dotnet test omnisharp-dotnet/SonarLint.OmniSharp.DotNet.Services.sln \
  -c Release \
  -p:CollectCoverage=true -p:CoverletOutput=TestResults/ -p:CoverletOutputFormat=opencover \
  --no-restore

# Finish SonarQube scan
dotnet sonarscanner end \
  -d:sonar.token=${SONAR_TOKEN}
