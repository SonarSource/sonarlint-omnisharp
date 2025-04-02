/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource SA
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.utils.System2;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OmnisharpCommandBuilderTests {

  OmnisharpCommandBuilder underTest;

  @TempDir
  public Path omnisharpNet6Location;
  @TempDir
  public Path omnisharpMonoLocation;
  @TempDir
  public Path omnisharpWinLocation;
  @TempDir
  public Path omnisharpDllServicesPath;

  private SonarLintRuntime sonarLintRuntime;

  private MapSettings mapSettings;

  private System2 system2;

  @BeforeEach
  void prepare() {
    system2 = mock(System2.class);
    OmnisharpServicesExtractor extractor = mock(OmnisharpServicesExtractor.class);
    when(extractor.getOmnisharpServicesDllPath()).thenReturn(omnisharpDllServicesPath);
    sonarLintRuntime = mock(SonarLintRuntime.class);
    mapSettings = new MapSettings();
    mapSettings.appendProperty("sonar.cs.internal.omnisharpNet6Location", omnisharpNet6Location.toString());
    mapSettings.appendProperty("sonar.cs.internal.omnisharpMonoLocation", omnisharpMonoLocation.toString());
    mapSettings.appendProperty("sonar.cs.internal.omnisharpWinLocation", omnisharpWinLocation.toString());
    Configuration config = mapSettings.asConfig();
    underTest = new OmnisharpCommandBuilder(system2, extractor, sonarLintRuntime, config);
  }

  @Test
  void buildCommandNet6_no_solution_provided_use_basedir(@TempDir Path projectBaseDir) {
    var pb = underTest.buildNet6(projectBaseDir, null, null, null, false);
    assertThat(pb.command()).containsExactly("dotnet",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommandNet6_use_provide_dotnet_cli(@TempDir Path projectBaseDir, @TempDir Path dotnetCliPath) {
    var pb = underTest.buildNet6(projectBaseDir, dotnetCliPath, null, null, false);
    assertThat(pb.command()).containsExactly(dotnetCliPath.toString(),
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommandNet6_use_dotnet_exe_on_windows(@TempDir Path projectBaseDir) {
    when(system2.isOsWindows()).thenReturn(true);

    var pb = underTest.buildNet6(projectBaseDir, null, null, null, false);
    assertThat(pb.command()).containsExactly("dotnet.exe",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommandNet6_solution_provided(@TempDir Path projectBaseDir, @TempDir Path solutionFile) {
    var pb = underTest.buildNet6(projectBaseDir, null, null, solutionFile, false);
    assertThat(pb.command()).containsExactly("dotnet",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      solutionFile.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_pass_client_pid(@TempDir Path projectBaseDir, @TempDir Path solutionFile) {
    when(sonarLintRuntime.getClientPid()).thenReturn(12345L);

    var pb = underTest.buildNet6(projectBaseDir, null, null, solutionFile, false);
    assertThat(pb.command()).containsExactly("dotnet",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "--hostPID",
      "12345",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      solutionFile.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_pass_msbuild_location(@TempDir Path projectBaseDir, @TempDir Path solutionFile, @TempDir Path msbuildPath) {
    var pb = underTest.buildNet6(projectBaseDir, null, msbuildPath, solutionFile, false);
    assertThat(pb.command()).containsExactly("dotnet",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:MSBuildOverride:MSBuildPath=" + msbuildPath.toString(),
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      solutionFile.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_fail_if_missing_omnisharp_dir(@TempDir Path projectBaseDir) {
    mapSettings.removeProperty("sonar.cs.internal.omnisharpNet6Location");

    assertThrows(IllegalStateException.class, () -> underTest.buildNet6(projectBaseDir, null, null, null, false));
  }

  @Test
  void buildCommand_use_mono_on_unix(@TempDir Path projectBaseDir) {
    when(system2.isOsWindows()).thenReturn(false);

    var pb = underTest.build(projectBaseDir, null, null, null, false);
    assertThat(pb.command()).containsExactly("mono",
      omnisharpMonoLocation.resolve("OmniSharp.exe").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_use_provide_mono(@TempDir Path projectBaseDir, @TempDir Path monoPath) {
    when(system2.isOsWindows()).thenReturn(false);

    var pb = underTest.build(projectBaseDir, monoPath, null, null, false);
    assertThat(pb.command()).containsExactly(monoPath.toString(),
      omnisharpMonoLocation.resolve("OmniSharp.exe").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_use_win_exe_on_windows(@TempDir Path projectBaseDir) {
    when(system2.isOsWindows()).thenReturn(true);

    var pb = underTest.build(projectBaseDir, null, null, null, false);
    assertThat(pb.command()).containsExactly(omnisharpWinLocation.resolve("OmniSharp.exe").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=false",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

  @Test
  void buildCommand_load_project_on_demand(@TempDir Path projectBaseDir) {
    var pb = underTest.buildNet6(projectBaseDir, null, null, null, true);
    assertThat(pb.command()).containsExactly("dotnet",
      omnisharpNet6Location.resolve("OmniSharp.dll").toString(),
      "-l",
      "Trace",
      "MsBuild:loadProjectsOnDemand=true",
      "DotNet:enablePackageRestore=false",
      "--encoding",
      "utf-8",
      "-s",
      projectBaseDir.toString(),
      "--plugin",
      omnisharpDllServicesPath.toString());
  }

}
