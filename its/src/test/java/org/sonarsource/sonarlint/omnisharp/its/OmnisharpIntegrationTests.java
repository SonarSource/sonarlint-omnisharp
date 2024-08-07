/*
 * SonarOmnisharp ITs
 * Copyright (C) 2021-2024 SonarSource SA
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.StandaloneSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.api.ClientModuleFileEvent;
import org.sonarsource.sonarlint.core.analysis.api.ClientModuleInfo;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneGlobalConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneSonarLintEngine;
import org.sonarsource.sonarlint.core.commons.Language;
import org.sonarsource.sonarlint.core.commons.RuleKey;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonarsource.sonarlint.core.commons.IssueSeverity.BLOCKER;
import static org.sonarsource.sonarlint.core.commons.IssueSeverity.CRITICAL;
import static org.sonarsource.sonarlint.core.commons.IssueSeverity.INFO;
import static org.sonarsource.sonarlint.core.commons.IssueSeverity.MAJOR;
import static org.sonarsource.sonarlint.core.commons.IssueSeverity.MINOR;

class OmnisharpIntegrationTests {

  private static final String SOLUTION1_MODULE_KEY = "solution1";
  private static final ClientModuleInfo MODULE_INFO_1 = new ClientModuleInfo(SOLUTION1_MODULE_KEY, null);
  private static final String SOLUTION2_MODULE_KEY = "solution2";
  private static final ClientModuleInfo MODULE_INFO_2 = new ClientModuleInfo(SOLUTION2_MODULE_KEY, null);
  private static StandaloneSonarLintEngine sonarlintEngine;

  @BeforeAll
  public static void prepare(@TempDir Path tmpDir) throws Exception {
    Path slHome = tmpDir.resolve("sonarlintHome");
    Files.createDirectories(slHome);
    File pluginJar = FileUtils
      .listFiles(Paths.get("../omnisharp-plugin/target/").toAbsolutePath().normalize().toFile(), new RegexFileFilter("^sonarlint-omnisharp-plugin-([0-9.]+)(-SNAPSHOT)*.jar$"),
        FalseFileFilter.FALSE)
      .iterator().next();

    String omnisharpMonoPath = new File("target/omnisharp-mono").getAbsolutePath();
    String omnisharpWinPath = new File("target/omnisharp-win").getAbsolutePath();
    String omnisharpNet6Path = new File("target/omnisharp-net6").getAbsolutePath();
    StandaloneGlobalConfiguration config = StandaloneGlobalConfiguration.builder()
      .addPlugin(pluginJar.toPath())
      .addEnabledLanguage(Language.CS)
      .setSonarLintUserHome(slHome)
      .setLogOutput((msg, level) -> System.out.println(msg))
      .setExtraProperties(
        Map.of(
          "sonar.cs.internal.omnisharpMonoLocation", omnisharpMonoPath,
          "sonar.cs.internal.omnisharpWinLocation", omnisharpWinPath,
          "sonar.cs.internal.omnisharpNet6Location", omnisharpNet6Path))
      .setClientPid(ProcessHandle.current().pid())
      .build();
    sonarlintEngine = new StandaloneSonarLintEngineImpl(config);
  }

  @AfterAll
  public static void stop() throws InterruptedException {
    // SLCORE-504 Give enough time to release dll, else we got deletion failure on Windows
    Thread.sleep(5000);
    sonarlintEngine.stop();
  }

  @BeforeEach
  public void startModule() {
    sonarlintEngine.declareModule(MODULE_INFO_1);
  }

  @AfterEach
  public void stopModules() {
    sonarlintEngine.stopModule(SOLUTION1_MODULE_KEY);
    sonarlintEngine.stopModule(SOLUTION2_MODULE_KEY);
  }

  @Test
  void analyzeNet5Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
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
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));
  }

  @Test
  void analyzeNet6Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet6Project");
    ClientInputFile inputFile = prepareInputFile(baseDir, "DotNet6Project/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("DotNet6Project.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 1, 3, 1, 7, inputFile.getPath(), INFO));
  }

  @Test
  void analyzeNet7Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet7Project");
    ClientInputFile inputFile = prepareInputFile(baseDir, "DotNet7Project/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");\n"
        + "public sealed record Foo\n"
        + "{\n"
        + "    public required Bar Baz { get; init; }  // \"Bar\" is flagged with S1104: Fields should not have public accessibility\n"
        + "}\n"
        + "\n"
        + "public sealed record Bar\n"
        + "{\n"
        + "}",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("DotNet7Project.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 1, 3, 1, 7, inputFile.getPath(), INFO),
        tuple("csharpsquid:S3903", "Move 'Foo' into a named namespace.", 3, 21, 3, 24, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S3903", "Move 'Bar' into a named namespace.", 8, 21, 8, 24, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S2094", "Remove this empty record, write its code or make it an \"interface\".", 8, 21, 8, 24, inputFile.getPath(), MINOR)
      );
  }

  @Test
  void analyzeNet8Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet8Project");
    ClientInputFile inputFile = prepareInputFile(baseDir, "DotNet8Project/Program.cs",
      "namespace DotNet8Project;\n" +
        "\n" +
        "public static class Class1\n" +
        "{\n" +
        "\n" +
        "    public static void Method2()\n" +
        "    {\n" +
        "        Method([\"\", \"\"]);\n" +
        "    }\n" +
        "    static void Method(string[] list)\n" +
        "    {\n" +
        "        ;\n" +
        "    }\n" +
        "}\n",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("DotNet8Project.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1116", "Remove this empty statement.", 12, 8, 12, 9, inputFile.getPath(), MINOR),
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'list'.", 10, 23, 10, 36, inputFile.getPath(), MAJOR)
      );
  }

  @Test
  void provideQuickFixes(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet6Project");
    ClientInputFile inputFile = prepareInputFile(baseDir, "DotNet6Project/Program.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program\n"
        + "    {\n"
        + "        private void Foo(string a)\n"
        + "        {\n"
        + "            Console.WriteLine(\"Hello World!\");\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("DotNet6Project.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .contains(
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'a'.", 7, 25, 7, 33, inputFile.getPath(), MAJOR));

    var issue = issues.stream().filter(i -> i.getRuleKey().equals("csharpsquid:S1172")).findFirst().get();
    assertThat(issue.quickFixes()).hasSize(1);
    assertThat(issue.quickFixes().get(0).message()).isEqualTo("Remove unused parameter");
    assertThat(issue.quickFixes().get(0).inputFileEdits()).hasSize(1);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).target().uri()).isEqualTo(inputFile.uri());
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits()).hasSize(1);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits().get(0).range().getStartLine()).isEqualTo(7);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits().get(0).range().getStartLineOffset()).isEqualTo(25);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits().get(0).range().getEndLine()).isEqualTo(7);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits().get(0).range().getEndLineOffset()).isEqualTo(33);
    assertThat(issue.quickFixes().get(0).inputFileEdits().get(0).textEdits().get(0).newText()).isEmpty();
  }

  @Test
  // FIXME
  @DisabledOnOs(OS.WINDOWS)
  void analyzeMixedSolutionWithOldOmnisharp(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "SolutionMixingCoreAndFramework");
    ClientInputFile inputFileFramework = prepareInputFile(baseDir, "DotNetFramework4_8/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);
    ClientInputFile inputFileCore = prepareInputFile(baseDir, "DotNet6Project/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFiles(inputFileFramework, inputFileCore)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "false")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 1, 3, 1, 7, inputFileFramework.getPath(), INFO));
  }

  // @Test
  // FIXME - was failing on Windows, now failing on Linux and MacOS too.
  // Tracked as https://sonarsource.atlassian.net/browse/SLI-1058
  void analyzeMixedSolutionWithNet6Omnisharp(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "SolutionMixingCoreAndFramework");

    ClientInputFile inputFileFramework = prepareInputFile(baseDir, "DotNetFramework4_8/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);
    ClientInputFile inputFileCore = prepareInputFile(baseDir, "DotNet6Project/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFiles(inputFileFramework, inputFileCore)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 1, 3, 1, 7, inputFileCore.getPath(), INFO));
  }

  @Test
  void analyzeFramework4_8Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNetFramework4_8");
    ClientInputFile inputFile = prepareInputFile(baseDir, "DotNetFramework4_8/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "false")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("DotNetFramework4_8.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 1, 3, 1, 7, inputFile.getPath(), INFO));
  }

  @Test
  void testAnalyzeNewFileAddedAfterOmnisharpStartup(@TempDir Path tmpDir) throws Exception {
    testAnalyzeNewFileAddedAfterOmnisharpStartup(tmpDir, false);
  }

  @Test
  void testAnalyzeNewFileAddedAfterOmnisharpStartupWithLoadOnDemand(@TempDir Path tmpDir) throws Exception {
    testAnalyzeNewFileAddedAfterOmnisharpStartup(tmpDir, true);
  }

  private void testAnalyzeNewFileAddedAfterOmnisharpStartup(Path tmpDir, boolean loadOnDemand) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
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
    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.loadProjectsOnDemand", String.valueOf(loadOnDemand))
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    List<String> logs = new ArrayList<>();
    sonarlintEngine.analyze(analysisConfiguration1, issues::add, (m, l) -> logs.add(m), null);

    String logLoadOnDemand = "Omnisharp: [INFORMATION] Skip loading projects listed in solution file or under target directory because MsBuild:LoadProjectsOnDemand is true.";
    if (loadOnDemand) {
      assertThat(logs).contains(logLoadOnDemand);
    } else {
      assertThat(logs).doesNotContain(logLoadOnDemand);
    }

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));

    ClientInputFile newInputFile = prepareInputFile(baseDir, "ConsoleApp1/Program2.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program2\n"
        + "    {\n"
        + "        static void Main(string[] args)\n"
        + "        {\n"
        + "            Console.WriteLine(\"Hello World!\");\n"
        + "            // TODO foo\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    sonarlintEngine.fireModuleFileEvent(SOLUTION1_MODULE_KEY, ClientModuleFileEvent.of(newInputFile, ModuleFileEvent.Type.CREATED));

    // Give time for Omnisharp to process the file event
    Thread.sleep(1000);

    issues.clear();
    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(newInputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.loadProjectsOnDemand", String.valueOf(loadOnDemand))
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 18, newInputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 10, 15, 10, 19, newInputFile.getPath(), INFO));
  }

  @Test
  void testRuleActivation(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
    ClientInputFile inputFile = prepareInputFile(baseDir, "ConsoleApp1/Program.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program\n"
        + "    {\n"
        + "        public void test(int x)\n"
        + "        {\n"
        + "          if (x == 0)\n"
        + "          {\n"
        + "            DoSomething();\n"
        + "          }\n"
        + "          else if (x == 1)\n"
        + "          {\n"
        + "            DoSomething();\n"
        + "          } \n"
        + "        }\n"
        + "        public void DoSomething(){\n"
        + "          // TODO foo\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration1, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1871", "Either merge this branch with the identical one on line 10 or change one of the implementations.", 14, 10, 16, 11, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 19, 13, 19, 17, inputFile.getPath(), INFO));

    issues.clear();

    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .addExcludedRules(RuleKey.parse("csharpsquid:S1135"))
      .addIncludedRules(RuleKey.parse("csharpsquid:S126"))
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1871", "Either merge this branch with the identical one on line 10 or change one of the implementations.", 14, 10, 16, 11,
          inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S126", "Add the missing 'else' clause with either the appropriate action or a suitable comment as to why no action is taken.", 13, 10, 13, 17,
          inputFile.getPath(), CRITICAL));
  }

  @Test
  void testRuleParameter(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
    ClientInputFile inputFile = prepareInputFile(baseDir, "ConsoleApp1/Program.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program\n"
        + "    {\n"
        + "        static void Main(string[] args)\n"
        + "        {\n"
        + "            while (true)\n"
        + "            {\n"
        + "                while (true)\n"
        + "                            {\n"
        + "                            while (true)\n"
        + "                            {\n"
        + "                                while (true)\n"
        + "                                {\n"
        + "                                    while (true)\n"
        + "                                    {\n"
        + "                                        while (true)\n"
        + "                                        {\n"
        + "                                           // TODO do something\n"
        + "                                        }\n"
        + "                                    }\n"
        + "                    }\n"
        + "                }\n"
        + "                }\n"
        + "            }"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    final List<Issue> issues = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration1, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .contains(
        tuple("csharpsquid:S3776", "Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed.", 7, 20, 7, 24, inputFile.getPath(), CRITICAL));

    issues.clear();

    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .addRuleParameter(RuleKey.parse("csharpsquid:S3776"), "threshold", "20")
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .contains(
        tuple("csharpsquid:S3776", "Refactor this method to reduce its Cognitive Complexity from 21 to the 20 allowed.", 7, 20, 7, 24, inputFile.getPath(), CRITICAL));
  }

  @Test
  void testChangingSolutions(@TempDir Path tmpDir) throws Exception {
    sonarlintEngine.declareModule(MODULE_INFO_2);

    Path solution1BaseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
    Path solution2BaseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet6Project");

    ClientInputFile inputFile1 = prepareInputFile(solution1BaseDir, "ConsoleApp1/Program.cs",
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

    ClientInputFile inputFile2 = prepareInputFile(solution2BaseDir, "DotNet6Project/Program.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp2\n"
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

    final List<Issue> issuesConsole1 = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", solution1BaseDir.resolve("ConsoleApp1.sln").toString())
      .setBaseDir(solution1BaseDir)
      .addInputFile(inputFile1)
      .build();
    sonarlintEngine.analyze(analysisConfiguration1, issuesConsole1::add, null, null);

    assertThat(issuesConsole1)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile1.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile1.getPath(), INFO));

    final List<Issue> issuesConsole2 = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setModuleKey(SOLUTION2_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", solution2BaseDir.resolve("DotNet6Project.sln").toString())
      .setBaseDir(solution2BaseDir)
      .addInputFile(inputFile2)
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issuesConsole2::add, null, null);

    assertThat(issuesConsole2)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile2.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile2.getPath(), INFO));
  }

  @Test
  void testMultipleSolutionsInSameFolder(@TempDir Path tmpDir) throws Exception {
    sonarlintEngine.declareModule(MODULE_INFO_2);

    // emulate a repo that contains multiple solutions
    Path repoBaseDir = prepareTestSolution(tmpDir, "ConsoleAppWithTwoSlnNotInRoot");

    ClientInputFile inputFile1 = prepareInputFile(repoBaseDir, "ConsoleApp1/Program1.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp1\n"
        + "{\n"
        + "    class Program1\n"
        + "    {\n"
        + "        static void Main(string[] args)\n"
        + "        {\n"
        + "            // TODO foo\n"
        + "            Console.WriteLine(\"Hello World!\");\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    ClientInputFile inputFile2 = prepareInputFile(repoBaseDir, "ConsoleApp2/Program2.cs",
      "using System;\n"
        + "\n"
        + "namespace ConsoleApp2\n"
        + "{\n"
        + "    class Program2\n"
        + "    {\n"
        + "        static void Main(string[] args)\n"
        + "        {\n"
        + "            // TODO foo\n"
        + "            Console.WriteLine(\"Hello World!\");\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    final List<Issue> issuesConsole1 = new ArrayList<>();
    Path solution1Sln = repoBaseDir.resolve("Solution/ConsoleApp1.sln");
    restore(solution1Sln);
    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .setBaseDir(repoBaseDir)
      .addInputFiles(inputFile1, inputFile2)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", solution1Sln.toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration1, issuesConsole1::add, null, null);

    assertThat(issuesConsole1)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 18, inputFile1.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile1.getPath(), INFO));
    // No issues in ConsoleApp2/Program2.cs because it is not part of Solution

    final List<Issue> issuesConsole2 = new ArrayList<>();
    Path solution2Sln = repoBaseDir.resolve("Solution/ConsoleApp2.sln");
    restore(solution2Sln);
    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setModuleKey(SOLUTION2_MODULE_KEY)
      .setBaseDir(repoBaseDir)
      .addInputFiles(inputFile1, inputFile2)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", solution2Sln.toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issuesConsole2::add, null, null);

    assertThat(issuesConsole2)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 18, inputFile2.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile2.getPath(), INFO));
    // No issues in ConsoleApp1/Program1.cs because it is not part of Solution
  }

  @Test
  void testAnalyzeFileinANewProject(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
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

    StandaloneAnalysisConfiguration analysisConfiguration1 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration1, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine,
        Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17,
          inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));

    // Emulate project creation
    FileUtils.copyDirectory(new File("src/test/projects/ConsoleApp1WithTestProject"), baseDir.toFile());
    restore(baseDir);

    sonarlintEngine.declareModule(MODULE_INFO_2);

    ClientInputFile slnInputFile = prepareInputFile(baseDir, "ConsoleApp1.sln",
      null,
      false);
    ClientInputFile csprojInputFile = prepareInputFile(baseDir, "TestProject1/TestProject1.csproj",
      null,
      false);

    sonarlintEngine.fireModuleFileEvent(SOLUTION1_MODULE_KEY, ClientModuleFileEvent.of(csprojInputFile, ModuleFileEvent.Type.CREATED));
    sonarlintEngine.fireModuleFileEvent(SOLUTION1_MODULE_KEY, ClientModuleFileEvent.of(slnInputFile, ModuleFileEvent.Type.MODIFIED));

    ClientInputFile testInputFile = prepareInputFile(baseDir, "TestProject1/UnitTest1.cs",
      "using NUnit.Framework;\n"
        + "\n"
        + "namespace TestProject1\n"
        + "{\n"
        + "    public class Tests\n"
        + "    {\n"
        + "        [SetUp]\n"
        + "        public void Setup()\n"
        + "        {\n"
        + "        }\n"
        + "\n"
        + "        [Test]\n"
        + "        void Test1()\n"
        + "        {\n"
        + "            // TODO foo\n"
        + "            Assert.Pass();\n"
        + "        }\n"
        + "    }\n"
        + "}",
      false);

    sonarlintEngine.fireModuleFileEvent(SOLUTION1_MODULE_KEY, ClientModuleFileEvent.of(testInputFile, ModuleFileEvent.Type.CREATED));

    issues.clear();
    StandaloneAnalysisConfiguration analysisConfiguration2 = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(testInputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();
    sonarlintEngine.analyze(analysisConfiguration2, issues::add, null, null);

    assertThat(issues)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset, i -> i.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1186", "Add a nested comment explaining why this method is empty, throw a 'NotSupportedException' or complete the implementation.", 8, 20, 8, 25,
          testInputFile.getPath(), CRITICAL),
        tuple("csharpsquid:S3433", "Make this test method 'public'.", 13, 13, 13, 18, testInputFile.getPath(), BLOCKER),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 15, 15, 15, 19, testInputFile.getPath(), INFO));
  }

  @Test
  void testConcurrentAnalysis(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
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

    final List<List<Issue>> issuesPerThread = new ArrayList<>();
    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();

    ExecutorService threadPool = Executors.newFixedThreadPool(5);
    for (int i = 0; i < 5; i++) {
      ArrayList<Issue> issues = new ArrayList<>();
      issuesPerThread.add(issues);
      threadPool.execute(() -> sonarlintEngine.analyze(analysisConfiguration, issues::add, null, null));
    }
    threadPool.shutdown();
    assertThat(threadPool.awaitTermination(1, TimeUnit.MINUTES)).isTrue();

    for (int i = 0; i < 5; i++) {
      assertThat(issuesPerThread.get(i))
        .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset,
          issue -> issue.getInputFile().getPath(),
          Issue::getSeverity)
        .containsOnly(
          tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), MAJOR),
          tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));
    }
  }

  @Test
  void shouldNotFailAfterFirstThreadDied(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
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

    StandaloneAnalysisConfiguration analysisConfiguration = StandaloneAnalysisConfiguration.builder()
      .setBaseDir(baseDir)
      .addInputFile(inputFile)
      .setModuleKey(SOLUTION1_MODULE_KEY)
      .putExtraProperty("sonar.cs.internal.useNet6", "true")
      .putExtraProperty("sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString())
      .build();

    final List<Issue> issuesThread1 = new ArrayList<>();
    Thread thread1 = new Thread("Analysis 1") {
      @Override
      public void run() {
        sonarlintEngine.analyze(analysisConfiguration, issuesThread1::add, null, null);
      }
    };
    thread1.start();
    thread1.join(60_000);

    assertThat(thread1.isAlive()).isFalse();

    assertThat(issuesThread1)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset,
        issue -> issue.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));

    // At some point we had a bug that only appeared when waiting enough after the first thread died (unable to write to stdin, "read end
    // dead"
    Thread.sleep(10000);

    final List<Issue> issuesThread2 = new ArrayList<>();
    Thread thread2 = new Thread("Analysis 2") {
      @Override
      public void run() {
        sonarlintEngine.analyze(analysisConfiguration, issuesThread2::add, null, null);
      }
    };
    thread2.start();
    thread2.join(60_000);

    assertThat(issuesThread2)
      .extracting(Issue::getRuleKey, Issue::getMessage, Issue::getStartLine, Issue::getStartLineOffset, Issue::getEndLine, Issue::getEndLineOffset,
        issue -> issue.getInputFile().getPath(),
        Issue::getSeverity)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration.", 5, 10, 5, 17, inputFile.getPath(), MAJOR),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment.", 9, 15, 9, 19, inputFile.getPath(), INFO));

  }

  private Path prepareTestSolutionAndRestore(Path tmpDir, String name) throws IOException, InterruptedException {
    Path baseDir = prepareTestSolution(tmpDir, name);
    restore(baseDir);
    return baseDir;
  }

  private Path prepareTestSolution(Path tmpDir, String name) throws IOException, InterruptedException {
    Path baseDir = tmpDir.toRealPath().resolve(name);
    Files.createDirectories(baseDir);
    FileUtils.copyDirectory(new File("src/test/projects/" + name), baseDir.toFile());
    return baseDir;
  }

  private void restore(Path solutionDirOrFile) throws IOException, InterruptedException {
    ProcessBuilder pb;
    if (Files.isRegularFile(solutionDirOrFile)) {
      pb = new ProcessBuilder("dotnet", "restore", solutionDirOrFile.getFileName().toString())
        .directory(solutionDirOrFile.getParent().toFile())
        .inheritIO();
    } else {
      pb = new ProcessBuilder("dotnet", "restore")
        .directory(solutionDirOrFile.toFile())
        .inheritIO();
    }
    Process process = pb.start();
    if (process.waitFor() != 0) {
      fail("Unable to run dotnet restore");
    }
  }

  private ClientInputFile prepareInputFile(Path baseDir, String relativePath, @Nullable String content, final boolean isTest) throws IOException {
    final File file = new File(baseDir.toFile(), relativePath);
    if (content != null) {
      FileUtils.write(file, content, StandardCharsets.UTF_8);
    }
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
