# SonarLint Omnisharp Plugin

Replacement for SonarC# that leverages OmniSharp to run our Roslyn analyzer

[![Build Status](https://api.cirrus-ci.com/github/SonarSource/sonarlint-omnisharp.svg?branch=master)](https://cirrus-ci.com/github/SonarSource/sonarlint-omnisharp)
[![Quality Gate Status (dotnet)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=sonarlint-omnisharp-dotnet&metric=alert_status&token=8df1ef6c2932894736b31de4b75e9a99deca0afb)](https://next.sonarqube.com/sonarqube/dashboard?id=sonarlint-omnisharp-dotnet)
[![Quality Gate Status (java)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent&metric=alert_status&token=177424623401146d0d058846c561536e247d3ed6)](https://next.sonarqube.com/sonarqube/dashboard?id=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent)


## License

Copyright 2025 SonarSource.

Licensed under the [GNU Lesser General Public License, Version 3.0](http://www.gnu.org/licenses/lgpl.txt)

## Overview

The project consists of two components:
* a .NET project that produces an assembly that plugs in to OmniSharp
* a Java project that produces a Sonar plugin jar that will be consumed by SonarLint in Rider and VSCode

## Building locally

The Azure pipeline builds, tests and packages both components.

Use the following commands to build locally:

### Download OmniSharp fork

`mvn generate-resources -Pdownload-omnisharp-for-building`

### Build .NET solution

Set the `ARTIFACTORY_USER` (your Sonar email) and `ARTIFACTORY_PASSWORD` (your repox credentials) environment variables.
You may need to restart your computer after these variables are set.

`dotnet build omnisharp-dotnet/SonarLint.OmniSharp.DotNet.Services.sln`

### Java plugin

The Java component depends on the .NET component, so the .NET component must be built first.

`mvn clean verify`
