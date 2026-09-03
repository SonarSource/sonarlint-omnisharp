/*
 * SonarOmnisharp ITs
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
package org.sonarsource.sonarlint.omnisharp.its;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.sonarsource.sonarlint.omnisharp.its.OmnisharpAnalysisHarness.activeRule;
import static org.sonarsource.sonarlint.omnisharp.its.OmnisharpAnalysisHarness.activeRules;
import static org.sonarsource.sonarlint.omnisharp.its.OmnisharpAnalysisHarness.activeRulesBuilder;

/**
 * Drives the packaged plugin against a real OmniSharp process and the real C# analyzer, on the test
 * solutions under {@code src/test/projects}: starting the right OmniSharp flavor, feeding it the
 * active rules and their parameters, and mapping the diagnostics it returns back to issues and
 * quick fixes.
 */
class OmnisharpIntegrationTests {

  private static final Pattern LIST_SDKS_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)\\s+\\[(.+)]\\s*$", Pattern.MULTILINE);
  private static final String SDK_CONFIGURATION_FROM_IDE_HINT_LOG_PREFIX = "Using OmniSharp SDK configuration from IDE hint:";

  /**
   * The rules the assertions below rely on. All of them belong to the C# analyzer default profile,
   * which is the profile SonarLint activates; keeping the set explicit keeps the expected issues
   * stable as that profile grows.
   */
  private static final String[] DEFAULT_ACTIVE_RULE_KEYS = {
    "csharpsquid:S1116",
    "csharpsquid:S1118",
    "csharpsquid:S1135",
    "csharpsquid:S1144",
    "csharpsquid:S1172",
    "csharpsquid:S1871",
    "csharpsquid:S2094",
    "csharpsquid:S2190",
    "csharpsquid:S2325",
    "csharpsquid:S3903"
  };

  private static final Function<Issue, Object> RULE_KEY = issue -> issue.ruleKey().toString();
  private static final Function<Issue, Object> MESSAGE = issue -> issue.primaryLocation().message();

  private static final String TODO_AND_HELLO_WORLD = "// TODO foo\n"
    + "Console.WriteLine(\"Hello, World!\");";

  private static final String DOTNET8_PROGRAM_WITH_COLLECTION_EXPRESSION = "namespace DotNet8Project;\n"
    + "\n"
    + "public static class Class1\n"
    + "{\n"
    + "\n"
    + "    public static void Method2()\n"
    + "    {\n"
    + "        Method([\"\", \"\"]);\n"
    + "    }\n"
    + "    static void Method(string[] list)\n"
    + "    {\n"
    + "        ;\n"
    + "    }\n"
    + "}\n";

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  @TempDir
  Path tmpDir;

  private final List<OmnisharpAnalysisHarness> harnesses = new ArrayList<>();

  @AfterEach
  void stopOmnisharp() {
    harnesses.forEach(OmnisharpAnalysisHarness::close);
    harnesses.clear();
  }

  @Test
  void analyzeNet5Solution() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("ConsoleAppNet5");

    var result = newHarness().analyze(baseDir, "ConsoleApp1/Program.cs",
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
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("ConsoleApp1.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeNet6Solution() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("DotNet6Project");

    var result = newHarness().analyze(baseDir, "DotNet6Project/Program.cs",
      "// TODO foo\n"
        + "Console.WriteLine(\"Hello, World!\");",
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("DotNet6Project.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeNet7Solution() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("DotNet7Project");

    var result = newHarness().analyze(baseDir, "DotNet7Project/Program.cs",
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
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("DotNet7Project.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."),
        tuple("csharpsquid:S3903", "Move 'Foo' into a named namespace."),
        tuple("csharpsquid:S3903", "Move 'Bar' into a named namespace."),
        tuple("csharpsquid:S2094", "Remove this empty record, write its code or make it an \"interface\"."));
  }

  @Test
  void analyzeNet8Solution() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("DotNet8Project");

    var result = newHarness().analyze(baseDir, "DotNet8Project/Program.cs", DOTNET8_PROGRAM_WITH_COLLECTION_EXPRESSION,
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("DotNet8Project.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1116", "Remove this empty statement."),
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'list'."));

    assertThat(logTester.logs()).noneMatch(log -> log.contains(SDK_CONFIGURATION_FROM_IDE_HINT_LOG_PREFIX));
  }

  @Test
  void analyzeNet8SolutionWithSdkPathHint() throws Exception {
    var sdkHint = findInstalledDotNetSdk(8).orElse(null);
    assumeTrue(sdkHint != null && Files.isDirectory(sdkHint.path()), "No .NET 8 SDK found — required for this test");
    // The command line OmniSharp is started with is logged at debug level
    logTester.setLevel(Level.DEBUG);

    var baseDir = prepareTestSolutionAndRestore("DotNet8Project");

    var properties = new HashMap<>(net6OmnisharpOn(baseDir.resolve("DotNet8Project.sln")));
    properties.put("sonar.cs.internal.msBuildPath", sdkHint.path().toString());

    var result = newHarness().analyze(baseDir, "DotNet8Project/Program.cs", DOTNET8_PROGRAM_WITH_COLLECTION_EXPRESSION,
      defaultActiveRules(), properties);

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1116", "Remove this empty statement."),
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'list'."));

    assertThat(logTester.logs()).anyMatch(log -> log.contains(
      SDK_CONFIGURATION_FROM_IDE_HINT_LOG_PREFIX + " Sdk:Path=" + sdkHint.path() + ", Sdk:Version=" + sdkHint.version()));
    assertThat(logTester.logs()).noneMatch(log -> log.contains("MsBuild:MSBuildOverride:MSBuildPath=" + sdkHint.path()));
  }

  @Test
  void provideQuickFixes() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("DotNet6Project");

    var result = newHarness().analyze(baseDir, "DotNet6Project/Program.cs",
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
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("DotNet6Project.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S2325", "Make 'Foo' a static method."),
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'a'."),
        tuple("csharpsquid:S1144", "Remove the unused private method 'Foo'."));
    assertThat(result.issues())
      .filteredOn(issue -> "csharpsquid:S1172".equals(issue.ruleKey().toString()))
      .allMatch(Issue::isQuickFixAvailable);

    var quickFixes = result.quickFixesOf("csharpsquid:S1172");
    assertThat(quickFixes).hasSize(1);
    var quickFix = quickFixes.get(0);
    assertThat(quickFix.message()).isEqualTo("Remove unused parameter");
    assertThat(quickFix.fileEdits()).hasSize(1);
    assertThat(quickFix.fileEdits().get(0).target().uri().toString()).endsWith("DotNet6Project/Program.cs");
    assertThat(quickFix.fileEdits().get(0).textEdits())
      .extracting(edit -> edit.range().start().line(), edit -> edit.range().start().lineOffset(),
        edit -> edit.range().end().line(), edit -> edit.range().end().lineOffset(), edit -> edit.newText())
      .containsExactly(tuple(7, 25, 7, 33, ""));
  }

  @Test
  // FIXME - still failing on Windows
  @DisabledOnOs(OS.WINDOWS)
  void analyzeMixedSolutionWithOldOmnisharp() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("SolutionMixingCoreAndFramework");
    var harness = newHarness();
    var properties = legacyOmnisharpOn(baseDir.resolve("MixSolution.sln"));

    var frameworkResult = harness.analyze(baseDir, "DotNetFramework4_8/Program.cs", TODO_AND_HELLO_WORLD, defaultActiveRules(), properties);
    var net6Result = harness.analyze(baseDir, "DotNet6Project/Program.cs", TODO_AND_HELLO_WORLD, defaultActiveRules(), properties);

    assertThat(frameworkResult.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
    // The .NET 6 project is not loaded by the legacy (Mono/net472) OmniSharp flavor
    assertThat(net6Result.issues()).isEmpty();
  }

  @Test
  // FIXME - was failing on Windows, now failing on Linux and MacOS too?
  // Tracked as https://sonarsource.atlassian.net/browse/SLOMNI-5
  void analyzeMixedSolutionWithNet6Omnisharp() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("SolutionMixingCoreAndFramework");
    var harness = newHarness();
    var properties = net6OmnisharpOn(baseDir.resolve("MixSolution.sln"));

    var frameworkResult = harness.analyze(baseDir, "DotNetFramework4_8/Program.cs", TODO_AND_HELLO_WORLD, defaultActiveRules(), properties);
    var net6Result = harness.analyze(baseDir, "DotNet6Project/Program.cs", TODO_AND_HELLO_WORLD, defaultActiveRules(), properties);

    // XXX Not sure if this is actually expected
    assertThat(frameworkResult.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
    assertThat(net6Result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeFramework4_8Solution() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("DotNetFramework4_8");

    var result = newHarness().analyze(baseDir, "DotNetFramework4_8/Program.cs", TODO_AND_HELLO_WORLD,
      defaultActiveRules(),
      legacyOmnisharpOn(baseDir.resolve("DotNetFramework4_8.sln")));

    assertThat(result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeBlazorApp_IgnoresRazorFiles() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("BlazorApp");

    var result = newHarness().analyze(baseDir, "BlazorApp/Components/App.razor",
      "@code {"
        + "        // TODO"
        + "    }\n",
      defaultActiveRules(),
      net6OmnisharpOn(baseDir.resolve("BlazorApp.sln")));

    assertThat(result.issues()).isEmpty();
  }

  @Test
  void activatingAndDeactivatingRules() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("ConsoleAppNet5");
    var harness = newHarness();
    var properties = net6OmnisharpOn(baseDir.resolve("ConsoleApp1.sln"));
    var content = "using System;\n"
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
      + "}";

    var withTodoRule = harness.analyze(baseDir, "ConsoleApp1/Program.cs", content,
      activeRules("csharpsquid:S1871", "csharpsquid:S1135"), properties);

    assertThat(withTodoRule.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1871", "Either merge this branch with the identical one on line 10 or change one of the implementations."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));

    // S1135 off, S126 (not in the default profile) on
    var withElseRule = harness.analyze(baseDir, "ConsoleApp1/Program.cs", content,
      activeRules("csharpsquid:S1871", "csharpsquid:S126"), properties);

    assertThat(withElseRule.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1871", "Either merge this branch with the identical one on line 10 or change one of the implementations."),
        tuple("csharpsquid:S126", "Add the missing 'else' clause with either the appropriate action or a suitable comment as to why no action is taken."));
  }

  @Test
  void passingRuleParametersToTheAnalyzer() throws Exception {
    var baseDir = prepareTestSolutionAndRestore("ConsoleAppNet5");
    var harness = newHarness();
    var properties = net6OmnisharpOn(baseDir.resolve("ConsoleApp1.sln"));
    var content = "using System;\n"
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
      + "}";

    var withDefaultThreshold = harness.analyze(baseDir, "ConsoleApp1/Program.cs", content, defaultActiveRules(), properties);

    assertThat(withDefaultThreshold.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S3776", "Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed."),
        tuple("csharpsquid:S2190", "Add a way to break out of this method's recursion."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));

    var withRaisedThreshold = harness.analyze(baseDir, "ConsoleApp1/Program.cs", content,
      activeRulesBuilder(DEFAULT_ACTIVE_RULE_KEYS)
        .addRule(activeRule("csharpsquid:S3776").setParam("threshold", "20").build())
        .build(),
      properties);

    assertThat(withRaisedThreshold.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S3776", "Refactor this method to reduce its Cognitive Complexity from 21 to the 20 allowed."),
        tuple("csharpsquid:S2190", "Add a way to break out of this method's recursion."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeTwoSolutionsConcurrently() throws Exception {
    var solution1BaseDir = prepareTestSolutionAndRestore("ConsoleAppNet5");
    var solution2BaseDir = prepareTestSolutionAndRestore("DotNet6Project");

    var solution1Result = newHarness().analyze(solution1BaseDir, "ConsoleApp1/Program.cs",
      todoProgram("ConsoleApp1"),
      defaultActiveRules(),
      net6OmnisharpOn(solution1BaseDir.resolve("ConsoleApp1.sln")));
    var solution2Result = newHarness().analyze(solution2BaseDir, "DotNet6Project/Program.cs",
      todoProgram("ConsoleApp2"),
      defaultActiveRules(),
      net6OmnisharpOn(solution2BaseDir.resolve("DotNet6Project.sln")));

    assertThat(solution1Result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
    assertThat(solution2Result.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  @Disabled("FIXME Not sure what is broken")
  void analyzeNewFileAddedAfterOmnisharpStartup() throws Exception {
    analyzeNewFileAddedAfterOmnisharpStartup(false);
  }

  @Test
  @Disabled("FIXME Not sure what is broken")
  void analyzeNewFileAddedAfterOmnisharpStartupWithLoadOnDemand() throws Exception {
    analyzeNewFileAddedAfterOmnisharpStartup(true);
  }

  private void analyzeNewFileAddedAfterOmnisharpStartup(boolean loadOnDemand) throws Exception {
    // The OmniSharp output telling us whether projects were loaded eagerly is logged at debug level
    logTester.setLevel(Level.DEBUG);
    var baseDir = prepareTestSolutionAndRestore("ConsoleAppNet5");
    var harness = newHarness();
    var properties = Map.of(
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.loadProjectsOnDemand", String.valueOf(loadOnDemand),
      "sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString());

    var firstResult = harness.analyze(baseDir, "ConsoleApp1/Program.cs", todoProgram("ConsoleApp1"), defaultActiveRules(), properties);

    var logLoadOnDemand = "Omnisharp: [INFORMATION] Skip loading projects listed in solution file or under target directory"
      + " because MsBuild:LoadProjectsOnDemand is true.";
    if (loadOnDemand) {
      assertThat(logTester.logs()).contains(logLoadOnDemand);
    } else {
      assertThat(logTester.logs()).doesNotContain(logLoadOnDemand);
    }
    assertThat(firstResult.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));

    harness.fireFileEvent(baseDir, "ConsoleApp1/Program2.cs", ModuleFileEvent.Type.CREATED);

    var newFileResult = harness.analyze(baseDir, "ConsoleApp1/Program2.cs",
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
      defaultActiveRules(), properties);

    assertThat(newFileResult.issues())
      .extracting(RULE_KEY, MESSAGE)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  private static String todoProgram(String namespaceName) {
    return "using System;\n"
      + "\n"
      + "namespace " + namespaceName + "\n"
      + "{\n"
      + "    class Program\n"
      + "    {\n"
      + "        static void Main(string[] args)\n"
      + "        {\n"
      + "            // TODO foo\n"
      + "            Console.WriteLine(\"Hello World!\");\n"
      + "        }\n"
      + "    }\n"
      + "}";
  }

  private static ActiveRules defaultActiveRules() {
    return activeRulesBuilder(DEFAULT_ACTIVE_RULE_KEYS)
      // The parameter default that SonarLint takes from the rule definition
      .addRule(activeRule("csharpsquid:S3776").setParam("threshold", "15").build())
      .build();
  }

  private static Map<String, String> net6OmnisharpOn(Path solutionPath) {
    return Map.of(
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", solutionPath.toString());
  }

  private static Map<String, String> legacyOmnisharpOn(Path solutionPath) {
    return Map.of(
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.solutionPath", solutionPath.toString());
  }

  private OmnisharpAnalysisHarness newHarness() {
    var harness = new OmnisharpAnalysisHarness(tmpDir.resolve("work" + harnesses.size()));
    harnesses.add(harness);
    return harness;
  }

  private Path prepareTestSolutionAndRestore(String name) throws IOException, InterruptedException {
    var baseDir = prepareTestSolution(name);
    restore(baseDir);
    return baseDir;
  }

  private Path prepareTestSolution(String name) throws IOException {
    var baseDir = tmpDir.toRealPath().resolve(name);
    Files.createDirectories(baseDir);
    FileUtils.copyDirectory(new File("src/test/projects/" + name), baseDir.toFile());
    return baseDir;
  }

  private static void restore(Path solutionDirOrFile) throws IOException, InterruptedException {
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
    var process = pb.start();
    if (process.waitFor() != 0) {
      fail("Unable to run dotnet restore");
    }
  }

  private static Optional<InstalledSdk> findInstalledDotNetSdk(int majorVersion) throws IOException, InterruptedException {
    var process = new ProcessBuilder("dotnet", "--list-sdks").redirectErrorStream(true).start();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      return Optional.empty();
    }
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    Optional<InstalledSdk> latestMatchingSdk = Optional.empty();
    String latestVersion = null;
    Matcher matcher = LIST_SDKS_PATTERN.matcher(output);
    while (matcher.find()) {
      var version = matcher.group(1);
      if (!version.startsWith(majorVersion + ".")) {
        continue;
      }
      if (latestVersion == null || compareSdkVersions(version, latestVersion) > 0) {
        latestVersion = version;
        latestMatchingSdk = Optional.of(new InstalledSdk(Paths.get(matcher.group(2).trim(), version), version));
      }
    }
    return latestMatchingSdk;
  }

  private static int compareSdkVersions(String left, String right) {
    var leftParts = left.split("\\.");
    var rightParts = right.split("\\.");
    var length = Math.max(leftParts.length, rightParts.length);
    for (var i = 0; i < length; i++) {
      var leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
      var rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
      if (leftPart != rightPart) {
        return Integer.compare(leftPart, rightPart);
      }
    }
    return 0;
  }

  private static final class InstalledSdk {
    private final Path path;
    private final String version;

    private InstalledSdk(Path path, String version) {
      this.path = path;
      this.version = version;
    }

    private Path path() {
      return path;
    }

    private String version() {
      return version;
    }
  }
}
