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
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.analysis.api.ClientModuleInfo;
import org.sonarsource.sonarlint.core.rpc.client.ClientJsonRpcLauncher;
import org.sonarsource.sonarlint.core.rpc.client.ConfigScopeNotFoundException;
import org.sonarsource.sonarlint.core.rpc.client.ConnectionNotFoundException;
import org.sonarsource.sonarlint.core.rpc.client.SonarLintCancelChecker;
import org.sonarsource.sonarlint.core.rpc.client.SonarLintRpcClientDelegate;
import org.sonarsource.sonarlint.core.rpc.impl.BackendJsonRpcLauncher;
import org.sonarsource.sonarlint.core.rpc.protocol.SonarLintRpcServer;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.AnalyzeFilesAndTrackParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.DidChangeAnalysisPropertiesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.config.binding.BindingSuggestionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.file.DidUpdateFileSystemParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.ClientConstantInfoDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.FeatureFlagsDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.HttpConfigurationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.InitializeParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.LanguageSpecificRequirements;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.OmnisharpRequirementsDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.TelemetryClientConstantAttributesDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.StandaloneRuleConfigDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TaintVulnerabilityDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.analysis.RawIssueDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.AssistBindingParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.AssistBindingResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.binding.NoBindingSuggestionFoundParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.AssistCreatingConnectionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.AssistCreatingConnectionResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.connection.ConnectionSuggestionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.event.DidReceiveServerHotspotEvent;
import org.sonarsource.sonarlint.core.rpc.protocol.client.fix.FixSuggestionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.hotspot.HotspotDetailsDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.GetProxyPasswordAuthenticationResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.ProxyDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.http.X509CertificateDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.issue.IssueDetailsDto;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.message.MessageType;
import org.sonarsource.sonarlint.core.rpc.protocol.client.message.ShowSoonUnsupportedMessageParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.progress.ReportProgressParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.progress.StartProgressParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.smartnotification.ShowSmartNotificationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.telemetry.TelemetryClientLiveAttributesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Either;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;
import org.sonarsource.sonarlint.core.rpc.protocol.common.TokenDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.UsernamePasswordDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.fail;

class OmnisharpIntegrationTests {

  private static final String SOLUTION1_MODULE_KEY = "solution1";
  private static final ClientModuleInfo MODULE_INFO_1 = new ClientModuleInfo(SOLUTION1_MODULE_KEY, null);
  private static final String SOLUTION2_MODULE_KEY = "solution2";
  private static final ClientModuleInfo MODULE_INFO_2 = new ClientModuleInfo(SOLUTION2_MODULE_KEY, null);

  private static final ClientConstantInfoDto IT_CLIENT_INFO = new ClientConstantInfoDto("clientName", "integrationTests");
  private static final TelemetryClientConstantAttributesDto IT_TELEMETRY_ATTRIBUTES = new TelemetryClientConstantAttributesDto("SLO# ITs", "SonarLint OmniSharp ITs",
    "1.2.3", "4.5.6", Collections.emptyMap());

  private static SonarLintRpcServer backend;
  private static SonarLintRpcClientDelegate client;

  @BeforeAll
  public static void prepare(@TempDir Path tmpDir) throws Exception {
    var clientToServerOutputStream = new PipedOutputStream();
    var clientToServerInputStream = new PipedInputStream(clientToServerOutputStream);

    var serverToClientOutputStream = new PipedOutputStream();
    var serverToClientInputStream = new PipedInputStream(serverToClientOutputStream);

    client = new MockSonarLintRpcClientDelegate() {
      @Override
      public void log(LogParams params) {
        System.out.println(params);
      }
    };
    new BackendJsonRpcLauncher(clientToServerInputStream, serverToClientOutputStream);
    var clientLauncher = new ClientJsonRpcLauncher(serverToClientInputStream, clientToServerOutputStream, client);
    backend = clientLauncher.getServerProxy();

    var slHome = tmpDir.resolve("sonarlintHome");
    Files.createDirectories(slHome);
    var pluginJar = FileUtils
      .listFiles(Paths.get("../omnisharp-plugin/target/").toAbsolutePath().normalize().toFile(), new RegexFileFilter("^sonarlint-omnisharp-plugin-([0-9.]+)(-SNAPSHOT)*.jar$"),
        FalseFileFilter.FALSE)
      .iterator().next().toPath();

    var ossAnalyserPath = new File("target/analyzer/sonarcsharp.jar").toPath();
    // TODO Use different paths, for now both are the same
    var enterpriseAnalyserPath = new File("target/analyzer/sonarcsharp.jar").toPath();
    var omnisharpMonoPath = new File("target/omnisharp-mono").toPath();
    var omnisharpWinPath = new File("target/omnisharp-win").toPath();
    var omnisharpNet6Path = new File("target/omnisharp-net6").toPath();

    var featureFlags = new FeatureFlagsDto(false, false, false, false, true, false, false, false, false, false);

    // TODO Remove these activations once the analyzer declares activeByDefault for Sonar way rules
    var ruleConfigByKey = Map.of(
      "csharpsquid:S1116", new StandaloneRuleConfigDto(true, Map.of()),
      "csharpsquid:S1118", new StandaloneRuleConfigDto(true, Map.of()),
      "csharpsquid:S1135", new StandaloneRuleConfigDto(true, Map.of()),
      "csharpsquid:S1172", new StandaloneRuleConfigDto(true, Map.of()),
      "csharpsquid:S2094", new StandaloneRuleConfigDto(true, Map.of()),
      "csharpsquid:S3903", new StandaloneRuleConfigDto(true, Map.of())
    );
    backend.initialize(
        new InitializeParams(IT_CLIENT_INFO, IT_TELEMETRY_ATTRIBUTES, HttpConfigurationDto.defaultConfig(), null, featureFlags,
          slHome.resolve("storage"),
          slHome.resolve("work"),
          Set.of(pluginJar, enterpriseAnalyserPath), Collections.emptyMap(),
          Set.of(org.sonarsource.sonarlint.core.rpc.protocol.common.Language.CS), Collections.emptySet(), Collections.emptySet(), Collections.emptyList(), Collections.emptyList(), slHome.toString(), ruleConfigByKey,
          false, new LanguageSpecificRequirements(null,
            new OmnisharpRequirementsDto(omnisharpMonoPath, omnisharpNet6Path, omnisharpWinPath, ossAnalyserPath, enterpriseAnalyserPath)),
          false, null))
      .get();
  }

  @BeforeEach
  public void cleanupClient() {
    ((MockSonarLintRpcClientDelegate) client).clear();
  }

  @AfterAll
  public static void stop() throws InterruptedException {
    Thread.sleep(5000);
    backend.shutdown().join();
  }

  @Test
  void analyzeNet5Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "ConsoleApp1/Program.cs", "using System;\n" +
      "\n" +
      "namespace ConsoleApp1\n" +
      "{\n" +
      "    class Program\n" +
      "    {\n" +
      "        static void Main(string[] args)\n" +
      "        {\n" +
      "            // TODO foo\n" +
      "            Console.WriteLine(\"Hello World!\");\n" +
      "        }\n" +
      "    }\n" +
      "}",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeNet6Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet6Project");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet6Project/Program.cs", "// TODO foo\\n\"\n" +
        "        + \"Console.WriteLine(\\\"Hello, World!\\\");",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("DotNet6Project.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeNet7Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet7Project");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet7Project/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");\n" +
        "public sealed record Foo\n" +
        "{\n" +
        "    public required Bar Baz { get; init; }  // \"Bar\" is flagged with S1104: Fields should not have public accessibility\n" +
        "}\n" +
        "\n" +
        "public sealed record Bar\n" +
        "{\n" +
        "}",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("DotNet7Project.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."),
        tuple("csharpsquid:S3903", "Move 'Foo' into a named namespace."),
        tuple("csharpsquid:S3903", "Move 'Bar' into a named namespace."),
        tuple("csharpsquid:S2094", "Remove this empty record, write its code or make it an \"interface\"."));
  }

  @Test
  void analyzeNet8Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet8Project");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet8Project/Program.cs", "namespace DotNet8Project;\n" +
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
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("DotNet8Project.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1116", "Remove this empty statement."),
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'list'."));
  }

  @Test
  void provideQuickFixes(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNet6Project");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet6Project/Program.cs", "using System;\n" +
        "\n" +
        "namespace ConsoleApp1\n" +
        "{\n" +
        "    class Program\n" +
        "    {\n" +
        "        private void Foo(string a)\n" +
        "        {\n" +
        "            Console.WriteLine(\"Hello World!\");\n" +
        "        }\n" +
        "    }\n" +
        "}",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("DotNet6Project.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1172", "Remove this unused method parameter 'a'."));

    var issue = issues.get(0);
    var quickFixes = issue.getQuickFixes();
    assertThat(quickFixes).hasSize(1);
    var quickFix = quickFixes.get(0);
    assertThat(quickFix.message()).isEqualTo("Remove unused parameter");
    assertThat(quickFix.fileEdits()).hasSize(1);
    assertThat(quickFix.fileEdits().get(0).target().toString()).endsWith("DotNet6Project/Program.cs");
    assertThat(quickFix.fileEdits().get(0).textEdits()).hasSize(1);
    assertThat(quickFix.fileEdits().get(0).textEdits().get(0).range().getStartLine()).isEqualTo(7);
    assertThat(quickFix.fileEdits().get(0).textEdits().get(0).range().getStartLineOffset()).isEqualTo(25);
    assertThat(quickFix.fileEdits().get(0).textEdits().get(0).range().getEndLine()).isEqualTo(7);
    assertThat(quickFix.fileEdits().get(0).textEdits().get(0).range().getEndLineOffset()).isEqualTo(33);
    assertThat(quickFix.fileEdits().get(0).textEdits().get(0).newText()).isEmpty();
  }

  @Test
  // FIXME - still failing on Windows
  @DisabledOnOs(OS.WINDOWS)
  void analyzeMixedSolutionWithOldOmnisharp(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "SolutionMixingCoreAndFramework");
    var issues1 = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNetFramework4_8/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");",
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString());
    var issues2 = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet6Project/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");",
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString());

    assertThat(issues1)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
    assertThat(issues2).isEmpty();
  }

  @Test
  // FIXME - was failing on Windows, now failing on Linux and MacOS too?
  // Tracked as https://sonarsource.atlassian.net/browse/SLOMNI-5
  void analyzeMixedSolutionWithNet6Omnisharp(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "SolutionMixingCoreAndFramework");
    var issues1 = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNetFramework4_8/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString());
    var issues2 = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNet6Project/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");",
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.solutionPath", baseDir.resolve("MixSolution.sln").toString());

    // XXX Not sure if this is actually expected
    assertThat(issues1)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
    assertThat(issues2)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  @Test
  void analyzeFramework4_8Solution(@TempDir Path tmpDir) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "DotNetFramework4_8");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "DotNetFramework4_8/Program.cs", "// TODO foo\n" +
        "Console.WriteLine(\"Hello, World!\");",
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.solutionPath", baseDir.resolve("DotNetFramework4_8.sln").toString());

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  // @Test
  // FIXME Not sure what is broken
  void testAnalyzeNewFileAddedAfterOmnisharpStartup(@TempDir Path tmpDir) throws Exception {
    testAnalyzeNewFileAddedAfterOmnisharpStartup(tmpDir, false);
  }

  //@Test
  // FIXME Not sure what is broken
  void testAnalyzeNewFileAddedAfterOmnisharpStartupWithLoadOnDemand(@TempDir Path tmpDir) throws Exception {
    testAnalyzeNewFileAddedAfterOmnisharpStartup(tmpDir, true);
  }

  private void testAnalyzeNewFileAddedAfterOmnisharpStartup(Path tmpDir, boolean loadOnDemand) throws Exception {
    Path baseDir = prepareTestSolutionAndRestore(tmpDir, "ConsoleAppNet5");
    var issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "ConsoleApp1/Program.cs", "using System;\n" +
        "\n" +
        "namespace ConsoleApp1\n" +
        "{\n" +
        "    class Program\n" +
        "    {\n" +
        "        static void Main(string[] args)\n" +
        "        {\n" +
        "            // TODO foo\n" +
        "            Console.WriteLine(\"Hello World!\");\n" +
        "        }\n" +
        "    }\n" +
        "}",
      "sonar.cs.internal.useNet6", "false",
      "sonar.cs.internal.loadProjectsOnDemand", String.valueOf(loadOnDemand),
      "sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString());

    String logLoadOnDemand = "Omnisharp: [INFORMATION] Skip loading projects listed in solution file or under target directory because MsBuild:LoadProjectsOnDemand is true.";
    if (loadOnDemand) {
      assertThat(((MockSonarLintRpcClientDelegate) client).getLogs()).contains(logLoadOnDemand);
    } else {
      assertThat(((MockSonarLintRpcClientDelegate) client).getLogs()).doesNotContain(logLoadOnDemand);
    }

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));

    var newFilePath = baseDir.resolve("ConsoleApp1/Program2.cs");
    backend.getFileService().didUpdateFileSystem(new DidUpdateFileSystemParams(List.of(), List.of(new ClientFileDto(
      baseDir.resolve("ConsoleApp1/Program2.cs").toUri(), newFilePath, SOLUTION1_MODULE_KEY, false, "UTF-8", newFilePath, "", Language.CS, false))));

    // Give time for Omnisharp to process the file event
    Thread.sleep(1000);

    issues = analyzeCSharpFile(SOLUTION1_MODULE_KEY, baseDir.toString(), "ConsoleApp1/Program2.cs", "using System;\n"
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
      "sonar.cs.internal.useNet6", "true",
      "sonar.cs.internal.loadProjectsOnDemand", String.valueOf(loadOnDemand),
      "sonar.cs.internal.solutionPath", baseDir.resolve("ConsoleApp1.sln").toString()
    );

    assertThat(issues)
      .extracting(RawIssueDto::getRuleKey, RawIssueDto::getPrimaryMessage)
      .containsOnly(
        tuple("csharpsquid:S1118", "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("csharpsquid:S1135", "Complete the task associated to this 'TODO' comment."));
  }

  /*
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
   */

  private Path prepareTestSolutionAndRestore(Path tmpDir, String name) throws IOException, InterruptedException {
    Path baseDir = prepareTestSolution(tmpDir, name);
    restore(baseDir);
    return baseDir;
  }

  private Path prepareTestSolution(Path tmpDir, String name) throws IOException {
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

  private List<RawIssueDto> analyzeCSharpFile(String configScopeId, String baseDir, String filePathStr, String content, String... properties) throws Exception {
    var filePath = Path.of("projects").resolve(baseDir).resolve(filePathStr);
    var fileUri = filePath.toUri();
    backend.getFileService().didUpdateFileSystem(new DidUpdateFileSystemParams(List.of(),
      List.of(new ClientFileDto(fileUri, Path.of(filePathStr), configScopeId, false, "UTF-8", filePath.toAbsolutePath(), content, Language.CS, true))));

    var propertiesMap = new HashMap<String, String>();
    for (int i=0; i<properties.length; i+=2) {
      propertiesMap.put(properties[i], properties[i+1]);
    }
    backend.getAnalysisService().didSetUserAnalysisProperties(new DidChangeAnalysisPropertiesParams(configScopeId, propertiesMap));
    var analyzeResponse = backend.getAnalysisService().analyzeFilesAndTrack(
      new AnalyzeFilesAndTrackParams(configScopeId, UUID.randomUUID(), List.of(fileUri), propertiesMap, false, System.currentTimeMillis())
    ).join();

    assertThat(analyzeResponse.getFailedAnalysisFiles()).isEmpty();
    // it could happen that the notification is not yet received while the analysis request is finished.
    // await().atMost(Duration.ofMillis(200)).untilAsserted(() -> assertThat(((MockSonarLintRpcClientDelegate) client).getRaisedIssues(configScopeId)).isNotEmpty());
    Thread.sleep(200);
    var raisedIssues = ((MockSonarLintRpcClientDelegate) client).getRaisedIssues(configScopeId);
    ((MockSonarLintRpcClientDelegate) client).getRaisedIssues().clear();
    return raisedIssues != null ? raisedIssues : List.of();
  }

  static class MockSonarLintRpcClientDelegate implements SonarLintRpcClientDelegate {

    private final Map<String, List<RawIssueDto>> raisedIssues = new HashMap<>();
    private final List<String> logs = new ArrayList<>();

    public List<RawIssueDto> getRaisedIssues(String configurationScopeId) {
      var issues = raisedIssues.get(configurationScopeId);
      return issues != null ? issues : List.of();
    }

    public Map<String, List<RawIssueDto>> getRaisedIssues() {
      return raisedIssues;
    }

    public List<String> getLogs() {
      return logs;
    }

    @Override
    public void didRaiseIssue(String configurationScopeId, UUID analysisId, RawIssueDto rawIssue) {
      raisedIssues.computeIfAbsent(configurationScopeId, k -> new ArrayList<>()).add(rawIssue);
    }

    @Override
    public void suggestBinding(Map<String, List<BindingSuggestionDto>> suggestionsByConfigScope) {

    }

    @Override
    public void suggestConnection(Map<String, List<ConnectionSuggestionDto>> suggestionsByConfigScope) {

    }

    @Override
    public void openUrlInBrowser(URL url) {

    }

    @Override
    public void showMessage(MessageType type, String text) {

    }

    @Override
    public void log(LogParams params) {
      this.logs.add(params.getMessage());
    }

    @Override
    public void showSoonUnsupportedMessage(ShowSoonUnsupportedMessageParams params) {

    }

    @Override
    public void showSmartNotification(ShowSmartNotificationParams params) {

    }

    @Override
    public String getClientLiveDescription() {
      return "";
    }

    @Override
    public void showHotspot(String configurationScopeId, HotspotDetailsDto hotspotDetails) {

    }

    @Override
    public void showIssue(String configurationScopeId, IssueDetailsDto issueDetails) {

    }

    @Override
    public void showFixSuggestion(String configurationScopeId, String issueKey, FixSuggestionDto fixSuggestion) {

    }

    @Override
    public AssistCreatingConnectionResponse assistCreatingConnection(AssistCreatingConnectionParams params, SonarLintCancelChecker cancelChecker) throws CancellationException {
      throw new CancellationException("Unsupported in ITS");
    }

    @Override
    public AssistBindingResponse assistBinding(AssistBindingParams params, SonarLintCancelChecker cancelChecker) throws CancellationException {
      throw new CancellationException("Unsupported in ITS");
    }

    @Override
    public void startProgress(StartProgressParams params) throws UnsupportedOperationException {

    }

    @Override
    public void reportProgress(ReportProgressParams params) {

    }

    @Override
    public void didSynchronizeConfigurationScopes(Set<String> configurationScopeIds) {

    }

    @Override
    public Either<TokenDto, UsernamePasswordDto> getCredentials(String connectionId) throws ConnectionNotFoundException {
      throw new ConnectionNotFoundException();
    }

    @Override
    public List<ProxyDto> selectProxies(URI uri) {
      return List.of(ProxyDto.NO_PROXY);
    }

    @Override
    public GetProxyPasswordAuthenticationResponse getProxyPasswordAuthentication(String host, int port, String protocol, String prompt, String scheme, URL targetHost) {
      return new GetProxyPasswordAuthenticationResponse("", "");
    }

    @Override
    public boolean checkServerTrusted(List<X509CertificateDto> chain, String authType) {
      return false;
    }

    @Override
    public void didReceiveServerHotspotEvent(DidReceiveServerHotspotEvent params) {

    }

    @Override
    public String matchSonarProjectBranch(String configurationScopeId, String mainBranchName, Set<String> allBranchesNames, SonarLintCancelChecker cancelChecker)
      throws ConfigScopeNotFoundException {
      return mainBranchName;
    }

    public boolean matchProjectBranch(String configurationScopeId, String branchNameToMatch, SonarLintCancelChecker cancelChecker) throws ConfigScopeNotFoundException {
      return true;
    }

    @Override
    public void didChangeMatchedSonarProjectBranch(String configScopeId, String newMatchedBranchName) {

    }

    @Override
    public TelemetryClientLiveAttributesResponse getTelemetryLiveAttributes() {
      System.err.println("Telemetry should be disabled in ITs");
      throw new CancellationException("Telemetry should be disabled in ITs");
    }

    @Override
    public void didChangeTaintVulnerabilities(String configurationScopeId, Set<UUID> closedTaintVulnerabilityIds, List<TaintVulnerabilityDto> addedTaintVulnerabilities,
      List<TaintVulnerabilityDto> updatedTaintVulnerabilities) {

    }

    @Override
    public List<ClientFileDto> listFiles(String configScopeId) {
      return List.of();
    }

    @Override
    public void noBindingSuggestionFound(NoBindingSuggestionFoundParams params) {
    }

    @Override
    public void didChangeAnalysisReadiness(Set<String> configurationScopeIds, boolean areReadyForAnalysis) {

    }

    public void clear() {
      raisedIssues.clear();
      logs.clear();
    }

  }
}
