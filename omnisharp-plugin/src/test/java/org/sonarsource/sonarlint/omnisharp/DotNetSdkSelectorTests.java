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
import org.sonar.api.utils.System2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DotNetSdkSelectorTests {

  private final DotNetSdkSelector underTest = new DotNetSdkSelector(mock(System2.class));

  @Test
  void selectCompatibleSdkPath_returns_compatible_hint_without_listing_sdks() {
    var sdkPath = Path.of("/usr/share/dotnet/sdk/8.0.422");

    var selected = underTest.selectCompatibleSdkPath(Path.of("/tmp/project"), sdkPath, null);

    assertThat(selected).isEqualTo(sdkPath);
  }

  @Test
  void parseListSdksLine_parses_dotnet_list_output() {
    var sdk = DotNetSdkSelector.parseListSdksLine("8.0.422 [/usr/share/dotnet/sdk]");

    assertThat(sdk).isPresent();
    assertThat(sdk.get().version()).isEqualTo("8.0.422");
    assertThat(sdk.get().path()).isEqualTo(Path.of("/usr/share/dotnet/sdk/8.0.422"));
  }

  @Test
  void compareSdkVersions_orders_patch_versions_numerically() {
    assertThat(DotNetSdkSelector.compareSdkVersions("8.0.422", "8.0.100")).isPositive();
    assertThat(DotNetSdkSelector.compareSdkVersions("9.0.10", "9.0.9")).isPositive();
  }

  @Test
  void parseListSdksLine_returns_empty_for_invalid_line() {
    assertThat(DotNetSdkSelector.parseListSdksLine("invalid")).isEmpty();
  }

}
