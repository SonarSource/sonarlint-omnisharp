SonarLint Omnisharp Plugin
==============
Replacement for SonarC# that leverage OmniSharp to run our Roslyn analyzer

[![Build Status](https://dev.azure.com/sonarsource/DotNetTeam%20Project/_apis/build/status/sonarlint/SonarLint%20OmniSharp?repoName=SonarSource%2Fsonarlint-omnisharp&branchName=master)](https://dev.azure.com/sonarsource/DotNetTeam%20Project/_build/latest?definitionId=118&repoName=SonarSource%2Fsonarlint-omnisharp&branchName=master)
[![Quality Gate Status (dotnet)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=sonarlint-omnisharp-dotnet&metric=alert_status&token=8df1ef6c2932894736b31de4b75e9a99deca0afb)](https://next.sonarqube.com/sonarqube/dashboard?id=sonarlint-omnisharp-dotnet)
[![Quality Gate Status (java)](https://next.sonarqube.com/sonarqube/api/project_badges/measure?project=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent&metric=alert_status&token=177424623401146d0d058846c561536e247d3ed6)](https://next.sonarqube.com/sonarqube/dashboard?id=org.sonarsource.sonarlint.omnisharp%3Asonarlint-omnisharp-parent)


License
-------

Copyright 2024 SonarSource.

Licensed under the [GNU Lesser General Public License, Version 3.0](http://www.gnu.org/licenses/lgpl.txt)

Overview
--------
The project consists of two components:
* a .NET project that produces an assembly that plugs in to OmniSharp, and 
* a Java project that produces a Sonar plugin jar that will be consumed in Rider/IntelliJ by SonarLint.


Building locally
----------------
The Azure pipeline builds, tests and packages both components.

Use the following commands to build locally:

Download OmniSharp fork
`mvn generate-resources -Pdownload-omnisharp-for-building`

.NET solution:
Set ARTIFACTORY_USER(your Sonar email) and ARTIFACTORY_PASSWORD(Your repox credentials) as environment variable
in your computer which will be used in nuget.config packageSourceCredentials (lines 19,20)
You may need to restart your computer after these variables are set
`dotnet build omnisharp-dotnet\SonarLint.OmniSharp.DotNet.Services.sln`

Java plugin:
`mvn clean verify`

The Java component depends on the .NET component, so the .NET component must be built first.
