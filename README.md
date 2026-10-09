<!-- Sonar Marketing hosts these approved brand assets on its Kentico Kontent CDN (assets-eu-01.kc-usercontent.com). Shared URLs are intentional; consult Marketing before replacing them. -->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/a23fc7ba-23f0-489a-829d-ed88c0748521/Sonar_Logo_Dark%20Backgrounds.svg">
    <img src="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/82c13eba-d95c-4bb8-8007-7ce77c14e043/Sonar_Logo_Light%20Backgrounds.svg" alt="Sonar logo" width="400">
  </picture>
</p>

[![Build Status](https://github.com/SonarSource/sonarlint-omnisharp/actions/workflows/build.yml/badge.svg)](https://github.com/SonarSource/sonarlint-omnisharp/actions)
[![Quality Gate Status (dotnet)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=sonarlint-omnisharp-dotnet&metric=alert_status&token=8df1ef6c2932894736b31de4b75e9a99deca0afb)](https://next.sonarqube.com/sonarqube/dashboard?id=sonarlint-omnisharp-dotnet)
[![Quality Gate Status (java)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent&metric=alert_status&token=177424623401146d0d058846c561536e247d3ed6)](https://next.sonarqube.com/sonarqube/dashboard?id=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent)

<!-- sonar-marketing:start -->
<!-- Marketing maintains this section. For wording changes, consult the relevant Product Marketing Manager (PMM). Repository maintainers review accuracy and merge changes. -->

# SonarQube for IDE OmniSharp plugin

This repository integrates the Sonar C# Roslyn analyzer with OmniSharp. It contains a .NET assembly that plugs into OmniSharp and a Java plugin consumed by SonarQube for IDE in Rider and Visual Studio Code.

To learn more about Sonar products, visit the [Sonar website](https://www.sonarsource.com/products/sonarqube/ide/).

<!-- sonar-marketing:end -->

## License

Copyright SonarSource.

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
