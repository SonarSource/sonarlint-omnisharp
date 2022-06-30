/*
 * SonarOmnisharp
 * Copyright (C) 2021-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.omnisharp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.sonar.api.config.Configuration;
import org.sonar.api.utils.System2;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;

@SonarLintSide(lifespan = "MODULE")
public class OmnisharpCommandBuilder {

  private final System2 system2;
  private final SonarLintRuntime sonarLintRuntime;
  private final OmnisharpServicesExtractor servicesExtractor;
  private final Configuration config;

  public OmnisharpCommandBuilder(System2 system2, OmnisharpServicesExtractor servicesExtractor, SonarLintRuntime sonarLintRuntime, Configuration config) {
    this.system2 = system2;
    this.servicesExtractor = servicesExtractor;
    this.sonarLintRuntime = sonarLintRuntime;
    this.config = config;
  }

  public ProcessBuilder buildNet6(Path projectBaseDir, @Nullable Path dotnetCliPath, @Nullable Path msBuildPath, @Nullable Path solutionPath) {

    List<String> args = new ArrayList<>();
    if (dotnetCliPath != null) {
      args.add(dotnetCliPath.toString());
    } else {
      if (system2.isOsWindows()) {
        args.add("dotnet.exe");
      } else {
        args.add("dotnet");
      }
    }
    String omnisharpNet6Loc = getMandatoryConfig(CSharpPropertyDefinitions.getOmnisharpNet6Location());
    args.add(Paths.get(omnisharpNet6Loc).resolve("OmniSharp.dll").toString());
    return addArguments(projectBaseDir, msBuildPath, solutionPath, args);
  }

  public ProcessBuilder build(Path projectBaseDir, @Nullable Path monoPath, @Nullable Path msBuildPath, @Nullable Path solutionPath) {
    List<String> args = new ArrayList<>();
    if (system2.isOsWindows()) {
      String omnisharpWinLoc = getMandatoryConfig(CSharpPropertyDefinitions.getOmnisharpWinLocation());
      args.add(Paths.get(omnisharpWinLoc).resolve("OmniSharp.exe").toString());
    } else {
      if (monoPath != null) {
        args.add(monoPath.toString());
      } else {
        args.add("mono");
      }
      String omnisharpMonoLoc = getMandatoryConfig(CSharpPropertyDefinitions.getOmnisharpMonoLocation());
      args.add(Paths.get(omnisharpMonoLoc).resolve("OmniSharp.exe").toString());
    }
    return addArguments(projectBaseDir, msBuildPath, solutionPath, args);
  }

  private ProcessBuilder addArguments(Path projectBaseDir, @Nullable Path msBuildPath, @Nullable Path solutionPath, List<String> args) {
    args.add("-v");
    if (sonarLintRuntime.getClientPid() != 0) {
      args.add("--hostPID");
      args.add(Long.toString(sonarLintRuntime.getClientPid()));
    }
    if (msBuildPath != null) {
      args.add("MsBuild:MSBuildOverride:MSBuildPath=" + msBuildPath.toString());
    }
    args.add("DotNet:enablePackageRestore=false");
    args.add("--encoding");
    args.add("utf-8");
    args.add("-s");
    args.add(solutionPath != null ? solutionPath.toString() : projectBaseDir.toString());
    args.add("--plugin");
    args.add(servicesExtractor.getOmnisharpServicesDllPath().toString());
    return new ProcessBuilder(args);
  }

  private String getMandatoryConfig(String propKey) {
    return config.get(propKey).orElseThrow(() -> new IllegalStateException("Property '" + propKey + "' is required"));
  }

}
