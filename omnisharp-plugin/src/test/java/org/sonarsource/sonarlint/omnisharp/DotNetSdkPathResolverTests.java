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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DotNetSdkPathResolverTests {

  @Test
  void fromPathHint_extracts_sdk_from_standard_path() {
    var sdkPath = Path.of("/usr/share/dotnet/sdk/8.0.422");

    var sdk = DotNetSdkPathResolver.fromPathHint(sdkPath);

    assertThat(sdk).isPresent();
    assertThat(sdk.get().path()).isEqualTo(sdkPath);
    assertThat(sdk.get().version()).isEqualTo("8.0.422");
  }

  @Test
  void fromPathHint_extracts_sdk_from_msbuild_path_inside_sdk() {
    var msBuildPath = Path.of("/usr/share/dotnet/sdk/8.0.422/MSBuild.dll");

    var sdk = DotNetSdkPathResolver.fromPathHint(msBuildPath);

    assertThat(sdk).isPresent();
    assertThat(sdk.get().path()).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.422"));
    assertThat(sdk.get().version()).isEqualTo("8.0.422");
  }

  @Test
  void fromPathHint_returns_empty_when_null() {
    var sdk = DotNetSdkPathResolver.fromPathHint(null);

    assertThat(sdk).isEmpty();
  }

  @Test
  void fromPathHint_returns_empty_when_no_sdk_parent() {
    var path = Path.of("/usr/share/dotnet/tools/8.0.422");

    var sdk = DotNetSdkPathResolver.fromPathHint(path);

    assertThat(sdk).isEmpty();
  }

  @Test
  void fromPathHint_returns_empty_when_version_does_not_match() {
    var path = Path.of("/usr/share/dotnet/sdk/invalid-version");

    var sdk = DotNetSdkPathResolver.fromPathHint(path);

    assertThat(sdk).isEmpty();
  }

  @Test
  void fromPathHint_extracts_sdk_from_relative_version_path() {
    var path = Path.of("6.0.100");

    var sdk = DotNetSdkPathResolver.fromPathHint(path);

    assertThat(sdk).isPresent();
    assertThat(sdk.get().version()).isEqualTo("6.0.100");
  }

  @Test
  void toVersionedSdkPath_appends_version_when_path_is_sdk_root() {
    var path = Path.of("/usr/share/dotnet/sdk");

    assertThat(DotNetSdkPathResolver.toVersionedSdkPath(path, "8.0.128"))
      .isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.128"));
  }

  @Test
  void toVersionedSdkPath_returns_path_when_already_versioned() {
    var path = Path.of("/usr/share/dotnet/sdk/8.0.422");

    assertThat(DotNetSdkPathResolver.toVersionedSdkPath(path, "8.0.422")).isEqualTo(path);
  }

  @Test
  void isCompatibleSdkVersion_returns_true_for_sdk_8() {
    assertThat(DotNetSdkPathResolver.isCompatibleSdkVersion("8.0.422")).isTrue();
  }

  @Test
  void isCompatibleSdkVersion_returns_true_for_sdk_9() {
    assertThat(DotNetSdkPathResolver.isCompatibleSdkVersion("9.0.100")).isTrue();
  }

  @Test
  void isCompatibleSdkVersion_returns_false_for_sdk_10() {
    assertThat(DotNetSdkPathResolver.isCompatibleSdkVersion("10.0.301")).isFalse();
  }

  @Test
  void isCompatibleSdkVersion_returns_false_for_non_numeric_major_version() {
    assertThat(DotNetSdkPathResolver.isCompatibleSdkVersion("x.0.0")).isFalse();
  }

  @Test
  void isCompatibleSdkVersion_returns_false_for_major_version_zero() {
    assertThat(DotNetSdkPathResolver.isCompatibleSdkVersion("0.0.1")).isFalse();
  }

  @Test
  void parseMajorVersion_returns_negative_one_for_non_numeric_major_version() {
    assertThat(DotNetSdkPathResolver.parseMajorVersion("x.0.0")).isEqualTo(-1);
  }

  @Test
  void parseMajorVersion_returns_negative_one_when_no_dot() {
    assertThat(DotNetSdkPathResolver.parseMajorVersion("8")).isEqualTo(-1);
  }

  @Test
  void parseMajorVersion_parses_major_version() {
    assertThat(DotNetSdkPathResolver.parseMajorVersion("8.0.422")).isEqualTo(8);
  }

  @Test
  void fromPathHint_accepts_version_with_prerelease_suffix() {
    var sdk = DotNetSdkPathResolver.fromPathHint(Path.of("/usr/share/dotnet/sdk/8.0.422-preview.1"));

    assertThat(sdk).isPresent();
    assertThat(sdk.get().version()).isEqualTo("8.0.422-preview.1");
  }

}
