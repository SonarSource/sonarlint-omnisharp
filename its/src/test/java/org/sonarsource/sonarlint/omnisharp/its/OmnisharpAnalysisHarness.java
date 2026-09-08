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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarProduct;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.impl.utils.DefaultTempFolder;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.Version;
import org.sonarsource.sonarlint.omnisharp.CSharpPropertyDefinitions;
import org.sonarsource.sonarlint.omnisharp.OmnisharpCommandBuilder;
import org.sonarsource.sonarlint.omnisharp.OmnisharpFileListener;
import org.sonarsource.sonarlint.omnisharp.OmnisharpSensor;
import org.sonarsource.sonarlint.omnisharp.OmnisharpServerController;
import org.sonarsource.sonarlint.omnisharp.OmnisharpServicesExtractor;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;
import org.sonarsource.sonarlint.omnisharp.its.QuickFixRecordingIssue.RecordedQuickFix;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Wires the plugin extensions the way SonarLint would for a single analysis scope, and runs
 * analyses through the real {@link OmnisharpSensor}: a real OmniSharp process, the real C# analyzer
 * DLLs and the real test solutions on disk.
 * <p>
 * One harness stands for one SonarLint module: the OmniSharp server it starts is reused across
 * {@link #analyze} calls, and restarted by {@link OmnisharpServerController} when one of the
 * properties OmniSharp was started with changes.
 */
final class OmnisharpAnalysisHarness implements AutoCloseable {

  private static final String LANGUAGE_KEY = "cs";

  private static final Path ANALYZER_JAR = Paths.get("target/analyzer/sonarcsharp.jar").toAbsolutePath();
  private static final Path OMNISHARP_MONO_DIR = Paths.get("target/omnisharp-mono").toAbsolutePath();
  private static final Path OMNISHARP_WIN_DIR = Paths.get("target/omnisharp-win").toAbsolutePath();
  private static final Path OMNISHARP_NET6_DIR = Paths.get("target/omnisharp-net6").toAbsolutePath();

  private final MapSettings settings;
  private final DefaultTempFolder tempFolder;
  private final OmnisharpServerController serverController;
  private final OmnisharpSensor sensor;
  private final OmnisharpFileListener fileListener;

  OmnisharpAnalysisHarness(Path workDir) {
    settings = new MapSettings();
    // Locations that SonarLint resolves once and passes to the plugin for the whole session
    settings.setProperty(CSharpPropertyDefinitions.getAnalyzerPath(), ANALYZER_JAR.toString());
    settings.setProperty(CSharpPropertyDefinitions.getOmnisharpMonoLocation(), OMNISHARP_MONO_DIR.toString());
    settings.setProperty(CSharpPropertyDefinitions.getOmnisharpWinLocation(), OMNISHARP_WIN_DIR.toString());
    settings.setProperty(CSharpPropertyDefinitions.getOmnisharpNet6Location(), OMNISHARP_NET6_DIR.toString());

    tempFolder = new DefaultTempFolder(workDir.toFile(), true);
    var config = settings.asConfig();
    var servicesExtractor = new OmnisharpServicesExtractor(tempFolder, config);
    var responseProcessor = new OmnisharpResponseProcessor();
    var endpoints = new OmnisharpEndpoints(responseProcessor);
    var commandBuilder = new OmnisharpCommandBuilder(System2.INSTANCE, servicesExtractor, new ItSonarLintRuntime(), config);
    serverController = new OmnisharpServerController(endpoints, responseProcessor, commandBuilder);
    sensor = new OmnisharpSensor(serverController, endpoints);
    fileListener = new OmnisharpFileListener(serverController, endpoints);
  }

  /**
   * Writes {@code content} to {@code baseDir/relativeFilePath}, then analyzes that single file.
   */
  AnalysisResult analyze(Path baseDir, String relativeFilePath, String content, ActiveRules activeRules, Map<String, String> analysisProperties) {
    analysisProperties.forEach(settings::setProperty);

    var context = SensorContextTester.create(baseDir).setSettings(settings);
    context.setActiveRules(activeRules);
    context.fileSystem().add(writeInputFile(baseDir, relativeFilePath, content));

    var recordedIssues = new ArrayList<QuickFixRecordingIssue>();
    var spiedContext = spy(context);
    // SensorContextTester drops quick fixes (it hands out NoOpNewQuickFix), so record them on the side
    doAnswer(invocation -> {
      var recording = new QuickFixRecordingIssue(context.newIssue());
      recordedIssues.add(recording);
      return recording;
    }).when(spiedContext).newIssue();

    sensor.execute(spiedContext);

    return new AnalysisResult(new ArrayList<>(context.allIssues()), recordedIssues);
  }

  /**
   * Notifies the plugin of a file event, as SonarLint does when the IDE reports a file system change.
   */
  void fireFileEvent(Path baseDir, String relativeFilePath, ModuleFileEvent.Type type) {
    var target = TestInputFileBuilder.create("", relativeFilePath)
      .setModuleBaseDir(baseDir)
      .setLanguage(LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    fileListener.process(new ModuleFileEvent() {
      @Override
      public InputFile getTarget() {
        return target;
      }

      @Override
      public Type getType() {
        return type;
      }
    });
  }

  @Override
  public void close() {
    try {
      // What the module container does when SonarLint closes the analysis scope
      serverController.stop();
    } finally {
      tempFolder.stop();
    }
  }

  static ActiveRules activeRules(String... ruleKeys) {
    return activeRulesBuilder(ruleKeys).build();
  }

  static ActiveRulesBuilder activeRulesBuilder(String... ruleKeys) {
    var builder = new ActiveRulesBuilder();
    for (String ruleKey : ruleKeys) {
      builder.addRule(activeRule(ruleKey).build());
    }
    return builder;
  }

  static NewActiveRule.Builder activeRule(String ruleKey) {
    return new NewActiveRule.Builder()
      .setRuleKey(RuleKey.parse(ruleKey))
      .setLanguage(LANGUAGE_KEY);
  }

  private static InputFile writeInputFile(Path baseDir, String relativeFilePath, String content) {
    var filePath = baseDir.resolve(relativeFilePath);
    try {
      Files.createDirectories(filePath.getParent());
      Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to write " + filePath, e);
    }
    return TestInputFileBuilder.create("", relativeFilePath)
      .setModuleBaseDir(baseDir)
      .setLanguage(LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
  }

  static final class AnalysisResult {
    private final List<Issue> issues;
    private final List<QuickFixRecordingIssue> recordedIssues;

    private AnalysisResult(List<Issue> issues, List<QuickFixRecordingIssue> recordedIssues) {
      this.issues = issues;
      this.recordedIssues = recordedIssues;
    }

    List<Issue> issues() {
      return issues;
    }

    /**
     * The quick fixes reported on the single issue raised for the given rule.
     */
    List<RecordedQuickFix> quickFixesOf(String ruleKey) {
      var parsedRuleKey = RuleKey.parse(ruleKey);
      var matching = new ArrayList<QuickFixRecordingIssue>();
      for (QuickFixRecordingIssue recorded : recordedIssues) {
        if (parsedRuleKey.equals(recorded.ruleKey())) {
          matching.add(recorded);
        }
      }
      if (matching.size() != 1) {
        throw new IllegalStateException("Expected exactly one issue for rule " + ruleKey + " but got " + matching.size());
      }
      return matching.get(0).quickFixes();
    }
  }

  /**
   * The runtime information the plugin reads from its container. API versions are read from the
   * classpath the same way SonarLint reads them, so they always describe the APIs actually in use.
   * The client PID is this JVM, so that a leaked OmniSharp process does not outlive the test run.
   */
  private static final class ItSonarLintRuntime implements SonarLintRuntime {

    private static final String SONAR_PLUGIN_API_VERSION_FILE = "/sonar-api-version.txt";
    private static final String SONARLINT_PLUGIN_API_VERSION_FILE = "/sonarlint-api-version.txt";

    @Override
    public Version getSonarLintPluginApiVersion() {
      return loadVersion(SONARLINT_PLUGIN_API_VERSION_FILE);
    }

    @Override
    public long getClientPid() {
      return ProcessHandle.current().pid();
    }

    @Override
    public Version getApiVersion() {
      return loadVersion(SONAR_PLUGIN_API_VERSION_FILE);
    }

    private static Version loadVersion(String versionFile) {
      try (var scanner = new Scanner(ItSonarLintRuntime.class.getResourceAsStream(versionFile), StandardCharsets.UTF_8)) {
        return Version.parse(scanner.nextLine());
      } catch (Exception e) {
        throw new IllegalStateException("Unable to load " + versionFile + " from the classpath", e);
      }
    }

    @Override
    public SonarProduct getProduct() {
      return SonarProduct.SONARLINT;
    }

    @Override
    public SonarQubeSide getSonarQubeSide() {
      throw new UnsupportedOperationException("Can only be called in SonarQube");
    }

    @Override
    public SonarEdition getEdition() {
      throw new UnsupportedOperationException("Can only be called in SonarQube");
    }
  }
}
