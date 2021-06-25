/*
 * SonarOmnisharp
 * Copyright (C) 2021-2021 SonarSource SA
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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.impl.utils.DefaultTempFolder;
import org.sonar.api.utils.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class OmnisharpServicesExtractorTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpServicesExtractor underTest;
  private Path slTmpDir;

  @BeforeEach
  void prepare(@TempDir Path tmpDir) throws IOException {
    slTmpDir = tmpDir.resolve("tmp");
    underTest = new OmnisharpServicesExtractor(new DefaultTempFolder(slTmpDir.toFile()));
  }

  @Test
  void extractAnalyzersAndServicesOnStartup() {
    underTest.start();
    assertThat(slTmpDir.resolve("slServices"))
      .isDirectoryContaining("glob:**/SonarLint.OmniSharp.DotNet.Services.dll");
    assertThat(slTmpDir.resolve("slServices/analyzers"))
      .isDirectoryContaining("glob:**/SonarAnalyzer.dll")
      .isDirectoryContaining("glob:**/SonarAnalyzer.CSharp.dll")
      .isDirectoryContaining("glob:**/SonarAnalyzer.CFG.dll")
      .isDirectoryContaining("glob:**/Google.Protobuf.dll");
    assertThat(underTest.getOmnisharpServicesDllPath()).endsWith(Paths.get("SonarLint.OmniSharp.DotNet.Services.dll"));
  }

}
