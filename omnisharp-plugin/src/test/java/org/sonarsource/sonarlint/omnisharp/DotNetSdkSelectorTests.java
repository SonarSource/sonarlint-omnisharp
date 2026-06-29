/*
 * SonarOmnisharp
 * Copyright (C) SonarSource Sàrl
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.utils.System2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class DotNetSdkSelectorTests {

  private final System2 system2 = mock(System2.class);

  @Test
  void selectCompatibleSdkPath_returns_compatible_hint_without_listing_sdks() {
    var underTest = new DotNetSdkSelector(system2);
    var sdkPath = Path.of("/usr/share/dotnet/sdk/8.0.422");

    var selected = underTest.selectCompatibleSdkPath(Path.of("/tmp/project"), sdkPath, null);

    assertThat(selected).isEqualTo(sdkPath);
  }

  @Test
  void selectCompatibleSdkPath_picks_highest_compatible_sdk_when_hint_is_incompatible() {
    var underTest = spy(new DotNetSdkSelector(system2));
    doReturn(List.of(
      new DotNetSdkSelector.SdkInfo("8.0.100", Path.of("/usr/share/dotnet/sdk/8.0.100")),
      new DotNetSdkSelector.SdkInfo("8.0.422", Path.of("/usr/share/dotnet/sdk/8.0.422")),
      new DotNetSdkSelector.SdkInfo("10.0.301", Path.of("/usr/share/dotnet/sdk/10.0.301"))))
      .when(underTest).listInstalledSdks(null);

    var selected = underTest.selectCompatibleSdkPath(Path.of("/tmp/project"), Path.of("/usr/share/dotnet/sdk/10.0.301"), null);

    assertThat(selected).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.422"));
  }

  @Test
  void selectCompatibleSdkPath_returns_null_when_only_incompatible_sdks_are_installed() {
    var underTest = spy(new DotNetSdkSelector(system2));
    doReturn(List.of(new DotNetSdkSelector.SdkInfo("10.0.301", Path.of("/usr/share/dotnet/sdk/10.0.301"))))
      .when(underTest).listInstalledSdks(null);

    var selected = underTest.selectCompatibleSdkPath(Path.of("/tmp/project"), null, null);

    assertThat(selected).isNull();
  }

  @Test
  void selectCompatibleSdkPath_respects_global_json_version(@TempDir Path projectBaseDir) throws Exception {
    Files.writeString(projectBaseDir.resolve("global.json"),
      "{\n  \"sdk\": {\n    \"version\": \"8.0.100\"\n  }\n}\n");
    var underTest = spy(new DotNetSdkSelector(system2));
    doReturn(List.of(
      new DotNetSdkSelector.SdkInfo("8.0.100", Path.of("/usr/share/dotnet/sdk/8.0.100")),
      new DotNetSdkSelector.SdkInfo("8.0.422", Path.of("/usr/share/dotnet/sdk/8.0.422"))))
      .when(underTest).listInstalledSdks(null);

    var selected = underTest.selectCompatibleSdkPath(projectBaseDir, null, null);

    assertThat(selected).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.100"));
  }

  @Test
  void selectCompatibleSdkPath_returns_null_when_listing_sdks_fails() {
    var underTest = spy(new DotNetSdkSelector(system2));
    doReturn(List.of()).when(underTest).listInstalledSdks(Path.of("/nonexistent/dotnet"));

    var selected = underTest.selectCompatibleSdkPath(Path.of("/tmp/project"), null, Path.of("/nonexistent/dotnet"));

    assertThat(selected).isNull();
  }

  @Test
  void selectBestSdk_picks_highest_when_global_json_does_not_match() {
    var sdks = List.of(
      new DotNetSdkSelector.SdkInfo("8.0.100", Path.of("/sdk/8.0.100")),
      new DotNetSdkSelector.SdkInfo("8.0.422", Path.of("/sdk/8.0.422")));

    var selected = DotNetSdkSelector.selectBestSdk(sdks, Optional.of("7.0.0"));

    assertThat(selected.version()).isEqualTo("8.0.422");
  }

  @Test
  void filterCompatibleSdks_excludes_sdk_10() {
    var sdks = List.of(
      new DotNetSdkSelector.SdkInfo("8.0.422", Path.of("/sdk/8.0.422")),
      new DotNetSdkSelector.SdkInfo("10.0.301", Path.of("/sdk/10.0.301")));

    assertThat(DotNetSdkSelector.filterCompatibleSdks(sdks))
      .extracting(DotNetSdkSelector.SdkInfo::version)
      .containsExactly("8.0.422");
  }

  @Test
  void parseListSdksLine_parses_dotnet_list_output() {
    var sdk = DotNetSdkSelector.parseListSdksLine("8.0.422 [/usr/share/dotnet/sdk]");

    assertThat(sdk).isPresent();
    assertThat(sdk.get().version()).isEqualTo("8.0.422");
    assertThat(sdk.get().path()).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.422"));
  }

  @Test
  void parseListSdksLine_normalizes_unversioned_linux_package_path() {
    var sdk = DotNetSdkSelector.parseListSdksLine("8.0.128 [/usr/share/dotnet/sdk]");

    assertThat(sdk).isPresent();
    assertThat(sdk.get().version()).isEqualTo("8.0.128");
    assertThat(sdk.get().path()).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.128"));
  }

  @Test
  void readGlobalJsonSdkVersion_reads_version_from_project_tree(@TempDir Path projectBaseDir) throws Exception {
    Files.writeString(projectBaseDir.resolve("global.json"),
      "{\n  \"sdk\": {\n    \"version\": \"8.0.128\"\n  }\n}\n");

    assertThat(DotNetSdkSelector.readGlobalJsonSdkVersion(projectBaseDir)).contains("8.0.128");
  }

  @Test
  void readGlobalJsonSdkVersion_reads_version_from_parent_directory(@TempDir Path projectBaseDir) throws Exception {
    Files.writeString(projectBaseDir.resolve("global.json"),
      "{\n  \"sdk\": {\n    \"version\": \"8.0.128\"\n  }\n}\n");
    var nestedProject = Files.createDirectory(projectBaseDir.resolve("src"));

    assertThat(DotNetSdkSelector.readGlobalJsonSdkVersion(nestedProject)).contains("8.0.128");
  }

  @Test
  void readGlobalJsonSdkVersion_returns_empty_when_file_is_missing(@TempDir Path projectBaseDir) {
    assertThat(DotNetSdkSelector.readGlobalJsonSdkVersion(projectBaseDir)).isEmpty();
  }

  @Test
  void readGlobalJsonSdkVersion_returns_empty_when_json_is_invalid(@TempDir Path projectBaseDir) throws Exception {
    Files.writeString(projectBaseDir.resolve("global.json"), "not-json");

    assertThat(DotNetSdkSelector.readGlobalJsonSdkVersion(projectBaseDir)).isEmpty();
  }

  @Test
  void readGlobalJsonSdkVersion_returns_empty_when_sdk_section_is_missing(@TempDir Path projectBaseDir) throws Exception {
    Files.writeString(projectBaseDir.resolve("global.json"), "{\n  \"msbuild-sdks\": {}\n}\n");

    assertThat(DotNetSdkSelector.readGlobalJsonSdkVersion(projectBaseDir)).isEmpty();
  }

  @Test
  void listInstalledSdks_returns_empty_when_dotnet_cli_is_invalid() {
    var underTest = new DotNetSdkSelector(system2);

    assertThat(underTest.listInstalledSdks(Path.of("/nonexistent/dotnet"))).isEmpty();
  }

  @Test
  void resolveDotnetExecutable_uses_windows_executable_name_on_windows() {
    when(system2.isOsWindows()).thenReturn(true);
    var underTest = new DotNetSdkSelector(system2);

    assertThat(underTest.resolveDotnetExecutable(null)).isEqualTo("dotnet.exe");
  }

  @Test
  void resolveDotnetExecutable_uses_unix_executable_name_on_non_windows() {
    when(system2.isOsWindows()).thenReturn(false);
    var underTest = new DotNetSdkSelector(system2);

    assertThat(underTest.resolveDotnetExecutable(null)).isEqualTo("dotnet");
  }

  @Test
  void resolveDotnetExecutable_uses_provided_path() {
    var underTest = new DotNetSdkSelector(system2);

    assertThat(underTest.resolveDotnetExecutable(Path.of("/custom/dotnet"))).isEqualTo("/custom/dotnet");
  }

  @Test
  void compareSdkVersions_orders_patch_versions_numerically() {
    assertThat(DotNetSdkSelector.compareSdkVersions("8.0.422", "8.0.100")).isPositive();
    assertThat(DotNetSdkSelector.compareSdkVersions("9.0.10", "9.0.9")).isPositive();
  }

  @Test
  void compareSdkVersions_returns_zero_for_equal_versions() {
    assertThat(DotNetSdkSelector.compareSdkVersions("8.0.422", "8.0.422")).isZero();
  }

  @Test
  void parseListSdksLine_returns_empty_for_invalid_line() {
    assertThat(DotNetSdkSelector.parseListSdksLine("invalid")).isEmpty();
  }

}
