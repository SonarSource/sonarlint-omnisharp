/*
 * SonarOmnisharp ITs
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
package org.sonarsource.sonarlint.omnisharp.its;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.StandaloneSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.client.api.common.Language;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneGlobalConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneSonarLintEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.fail;

class OmnisharpIntegrationTests {

  private static StandaloneSonarLintEngine sonarlintEngine;

  @BeforeAll
  public static void prepare(@TempDir Path tmpDir) throws Exception {
    Path slHome = tmpDir.resolve("sonarlintHome");
    Files.createDirectories(slHome);
    File pluginJar = FileUtils
      .listFiles(Paths.get("../omnisharp-plugin/target/").toAbsolutePath().normalize().toFile(), new RegexFileFilter("^sonarlint-omnisharp-plugin-([0-9.]+)(-SNAPSHOT)*.jar$"),
        FalseFileFilter.FALSE)
      .iterator().next();
    StandaloneGlobalConfiguration config = StandaloneGlobalConfiguration.builder()
      .addPlugin(pluginJar.toURI().toURL())
      .addEnabledLanguage(Language.CS)
      .setSonarLintUserHome(slHome)
      .setLogOutput((msg, level) -> System.out.println(msg))
      .setExtraProperties(Collections.singletonMap("sonar.cs.internal.omnisharpLocation", new File("target/omnisharp").getAbsolutePath()))
      .setClientPid(ProcessHandle.current().pid())
      .build();
    sonarlintEngine = new StandaloneSonarLintEngineImpl(config);
  }

  @AfterAll
  public static void stop() {
    sonarlintEngine.stop();
  }

  @Test
  void testSampleProject(@TempDir Path tmpDir) throws Exception {
    Path baseDir = tmpDir.toRealPath().resolve("ConsoleApp1");
    Files.createDirectories(baseDir);
    FileUtils.copyDirectory(new File("src/test/projects/ConsoleApp1"), baseDir.toFile());
    ProcessBuilder pb = new ProcessBuilder("dotnet", "restore")
      .directory(baseDir.toFile())
      .inheritIO();
    Process process = pb.start();
    if (process.waitFor() != 0) {
      fail("Unable to run dotnet restore");
    }
    ClientInputFile inputFile = prepareInputFile(baseDir, "ConsoleApp1/Program.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program\n"
        + "    {\n"
        + "        static void Main(string[] args)\n"
        + "        {\n"
        + "            // TODO foo\n"
        + "            Console.WriteLine(\"Hello World!\");\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .build();
    sonarlintEngine.analyze(analysisConfiguration, i -> issues.add(i), null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), "MAJOR"),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), "INFO"));
  }

  private ClientInputFile prepareInputFile(Path baseDir, String relativePath, String content, final boolean isTest) throws IOException {
    final File file = new File(baseDir.toFile(), relativePath);
    FileUtils.write(file, content, StandardCharsets.UTF_8);
    return createInputFile(baseDir, file.toPath(), isTest);
  }

  private ClientInputFile createInputFile(Path baseDir, final Path path, final boolean isTest) {
    return new ClientInputFile() {

      @Override
      public String getPath() {
        return path.toString();
      }

      @Override
      public String relativePath() {
        return baseDir.relativize(path).toString();
      }

      @Override
      public URI uri() {
        return path.toUri();
      }

      @Override
      public boolean isTest() {
        return isTest;
      }

      @Override
      public Charset getCharset() {
        return StandardCharsets.UTF_8;
      }

      @Override
      public <G> G getClientObject() {
        return null;
      }

      @Override
      public InputStream inputStream() throws IOException {
        return new FileInputStream(path.toFile());
      }

      @Override
      public String contents() throws IOException {
        return FileUtils.readFileToString(path.toFile(), getCharset());
      }
    };
  }

}
