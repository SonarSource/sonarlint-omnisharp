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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.batch.sensor.issue.NewMessageFormatting;
import org.sonar.api.batch.sensor.issue.fix.NewInputFileEdit;
import org.sonar.api.batch.sensor.issue.fix.NewQuickFix;
import org.sonar.api.batch.sensor.issue.fix.NewTextEdit;
import org.sonar.api.config.Configuration;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;
import org.sonarsource.sonarlint.omnisharp.protocol.Diagnostic;
import org.sonarsource.sonarlint.omnisharp.protocol.DiagnosticLocation;
import org.sonarsource.sonarlint.omnisharp.protocol.Fix;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.QuickFix;
import org.sonarsource.sonarlint.omnisharp.protocol.QuickFixEdit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OmnisharpSensorTests {

  private final OmnisharpServerController mockServer = mock(OmnisharpServerController.class);
  private final OmnisharpEndpoints mockProtocol = mock(OmnisharpEndpoints.class);
  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();
  private OmnisharpSensor underTest;
  private Path baseDir;

  private static QuickFix mockQuickFix(String message, Fix... fixes) {
    QuickFix qf = mock(QuickFix.class);
    when(qf.getMessage()).thenReturn(message);
    when(qf.getFixes()).thenReturn(fixes);
    return qf;
  }

  private static Fix mockFix(Path filePath, QuickFixEdit... edits) {
    Fix fix = mock(Fix.class);
    when(fix.getFilename()).thenReturn(filePath.toString());
    when(fix.getEdits()).thenReturn(edits);
    return fix;
  }

  private static QuickFixEdit mockEdit(int startLine, int startColumn, int endLine, int endColumn, String newText) {
    QuickFixEdit edit = mock(QuickFixEdit.class);
    when(edit.getStartLine()).thenReturn(startLine);
    when(edit.getStartColumn()).thenReturn(startColumn);
    when(edit.getEndLine()).thenReturn(endLine);
    when(edit.getEndColumn()).thenReturn(endColumn);
    when(edit.getNewText()).thenReturn(newText);
    return edit;
  }

  @BeforeEach
  void prepare(@TempDir Path tmp) throws Exception {
    baseDir = tmp.toRealPath();
    underTest = new OmnisharpSensor(mockServer, mockProtocol);
    when(mockServer.whenReady()).thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  void describe() {
    DefaultSensorDescriptor descriptor = new DefaultSensorDescriptor();

    underTest.describe(descriptor);

    assertThat(descriptor.name()).isEqualTo("OmniSharp");
    assertThat(descriptor.languages()).containsOnly(OmnisharpPluginConstants.LANGUAGE_KEY);
    assertThat(descriptor.ruleRepositories()).containsOnly(OmnisharpPluginConstants.REPOSITORY_KEY);

    var configWithProp = mock(Configuration.class);
    when(configWithProp.hasKey(CSharpPropertyDefinitions.getAnalyzerPath())).thenReturn(true);
    when(configWithProp.hasKey(CSharpPropertyDefinitions.getOmnisharpMonoLocation())).thenReturn(true);
    when(configWithProp.hasKey(CSharpPropertyDefinitions.getOmnisharpWinLocation())).thenReturn(true);
    when(configWithProp.hasKey(CSharpPropertyDefinitions.getOmnisharpNet6Location())).thenReturn(true);

    var configWithoutProp = mock(Configuration.class);

    assertThat(descriptor.configurationPredicate()).accepts(configWithProp).rejects(configWithoutProp);

  }

  @Test
  void noopIfNoFiles() {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);

    underTest.execute(sensorContext);

    verifyNoInteractions(mockProtocol, mockServer);
  }

  @Test
  void scanCsFile() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    underTest.execute(sensorContext);

    verify(mockServer).lazyStart(baseDir, OmnisharpTestUtils.ANALYZER_JAR, false, false, null, null, null, null, 60, 60);

    verify(mockProtocol).updateBuffer(filePath.toFile(), content);
    verify(mockProtocol).config(argThat(json -> json.toString().equals("{\"activeRules\":[]}")));
    verify(mockProtocol).codeCheck(eq(filePath.toFile()), any());
    verifyNoMoreInteractions(mockProtocol);
  }

  @Test
  void logIfProjectLoadTimeout() throws Exception {
    when(mockServer.whenReady()).thenReturn(CompletableFuture.failedFuture(new TimeoutException()));

    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    underTest.execute(sensorContext);

    verify(mockServer).lazyStart(baseDir, OmnisharpTestUtils.ANALYZER_JAR, false, false, null, null, null, null, 60, 60);

    verifyNoInteractions(mockProtocol);

    assertThat(logTester.logs(LoggerLevel.ERROR))
      .contains("Timeout waiting for the solution to be loaded." +
        " You can find help on https://docs.sonarsource.com/sonarlint/intellij/using-sonarlint/scan-my-project/#supported-features-in-rider" +
        " or https://docs.sonarsource.com/sonarlint/vs-code/getting-started/requirements/#c-analysis");
  }

  @Test
  void passConfig() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);

    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getUseNet6(), "true");
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getLoadProjectsOnDemand(), "true");
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getStartupTimeout(), "999");
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getLoadProjectsTimeout(), "123");

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    underTest.execute(sensorContext);

    verify(mockServer).lazyStart(baseDir, OmnisharpTestUtils.ANALYZER_JAR, true, true, null, null, null, null, 999, 123);
  }

  @Test
  void passActiveRulesAndParams() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());
    sensorContext.setActiveRules(new ActiveRulesBuilder()
      // Rule from another repo, should be ignored
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of("foo", "bar")).build())
      // Rule without params
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S123")).build())
      // Rule with params
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S456")).setParam("param1", "val1").setParam("param2", "val2").build())
      .build());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    underTest.execute(sensorContext);

    // The order of parameters is not guaranteed
    verify(mockProtocol)
      .config(argThat(json -> json.toString().equals("{\"activeRules\":[{\"ruleId\":\"S123\"},{\"ruleId\":\"S456\",\"params\":{\"param1\":\"val1\",\"param2\":\"val2\"}}]}")
      || json.toString().equals("{\"activeRules\":[{\"ruleId\":\"S123\"},{\"ruleId\":\"S456\",\"params\":{\"param2\":\"val2\",\"param1\":\"val1\"}}]}")));
  }

  @Test
  void testCancellation() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());
    sensorContext.setCancelled(true);

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    underTest.execute(sensorContext);

    verify(mockServer).lazyStart(baseDir, OmnisharpTestUtils.ANALYZER_JAR, false, false, null, null, null, null, 60, 60);
    verify(mockProtocol).config(any());
    verifyNoMoreInteractions(mockProtocol);
  }

  @Test
  void ignoreInactiveRules() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();
    sensorContext.fileSystem().add(file);

    ArgumentCaptor<Consumer<Diagnostic>> captor = ArgumentCaptor.forClass(Consumer.class);

    underTest.execute(sensorContext);

    verify(mockProtocol).codeCheck(eq(filePath.toFile()), captor.capture());

    Consumer<Diagnostic> issueConsumer = captor.getValue();

    Diagnostic diag = mock(Diagnostic.class);
    when(diag.getId()).thenReturn("SA12345");

    issueConsumer.accept(diag);

    assertThat(sensorContext.allIssues()).isEmpty();
  }

  @Test
  void reportIssueForActiveRules() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    RuleKey ruleKey = RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S12345");
    sensorContext.setActiveRules(new ActiveRulesBuilder().addRule(new NewActiveRule.Builder().setRuleKey(ruleKey).build()).build());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
    sensorContext.fileSystem().add(file);

    ArgumentCaptor<Consumer<Diagnostic>> captor = ArgumentCaptor.forClass(Consumer.class);

    underTest.execute(sensorContext);

    verify(mockProtocol).codeCheck(eq(filePath.toFile()), captor.capture());

    Consumer<Diagnostic> issueConsumer = captor.getValue();

    Diagnostic diag = mock(Diagnostic.class);
    when(diag.getFilename()).thenReturn(filePath.toString());
    when(diag.getId()).thenReturn("S12345");
    when(diag.getLine()).thenReturn(1);
    when(diag.getColumn()).thenReturn(1);
    when(diag.getEndLine()).thenReturn(1);
    when(diag.getEndColumn()).thenReturn(5);
    when(diag.getText()).thenReturn("Don't do this");

    issueConsumer.accept(diag);

    assertThat(sensorContext.allIssues()).extracting(Issue::ruleKey, i -> i.primaryLocation().inputComponent(), i -> i.primaryLocation().message(),
        i -> i.primaryLocation().textRange().start().line(),
        i -> i.primaryLocation().textRange().start().lineOffset(),
        i -> i.primaryLocation().textRange().end().line(),
        i -> i.primaryLocation().textRange().end().lineOffset())
      .containsOnly(tuple(ruleKey, file, "Don't do this", 1, 0, 1, 4));
  }

  @Test
  void ignoreIssuesOnOtherFiles() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    RuleKey ruleKey = RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S12345");
    sensorContext.setActiveRules(new ActiveRulesBuilder().addRule(new NewActiveRule.Builder().setRuleKey(ruleKey).build()).build());

    Path filePath = baseDir.resolve("Foo.cs");
    Path anotherFilePath = baseDir.resolve("Bar.cs");
    String content = "Console.WriteLine(\"Hello World!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
    sensorContext.fileSystem().add(file);

    ArgumentCaptor<Consumer<Diagnostic>> captor = ArgumentCaptor.forClass(Consumer.class);

    underTest.execute(sensorContext);

    verify(mockProtocol).codeCheck(eq(filePath.toFile()), captor.capture());

    Consumer<Diagnostic> issueConsumer = captor.getValue();

    Diagnostic diag = mock(Diagnostic.class);
    when(diag.getFilename()).thenReturn(anotherFilePath.toString());
    when(diag.getId()).thenReturn("S12345");
    when(diag.getLine()).thenReturn(1);
    when(diag.getColumn()).thenReturn(1);
    when(diag.getEndLine()).thenReturn(1);
    when(diag.getEndColumn()).thenReturn(5);
    when(diag.getText()).thenReturn("Don't do this");

    issueConsumer.accept(diag);

    assertThat(sensorContext.allIssues()).isEmpty();
  }

  @Test
  void processSecondaryLocations() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    RuleKey ruleKey = RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S12345");
    sensorContext.setActiveRules(new ActiveRulesBuilder().addRule(new NewActiveRule.Builder().setRuleKey(ruleKey).build()).build());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello \n Woooooooooooooooooooooooooooorld!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
    Path anotherFilePath = baseDir.resolve("Bar.cs");

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
    sensorContext.fileSystem().add(file);

    ArgumentCaptor<Consumer<Diagnostic>> captor = ArgumentCaptor.forClass(Consumer.class);

    underTest.execute(sensorContext);

    verify(mockProtocol).codeCheck(eq(filePath.toFile()), captor.capture());

    Consumer<Diagnostic> issueConsumer = captor.getValue();

    Diagnostic diag = mock(Diagnostic.class);
    when(diag.getFilename()).thenReturn(filePath.toString());
    when(diag.getId()).thenReturn("S12345");
    when(diag.getLine()).thenReturn(1);
    when(diag.getColumn()).thenReturn(1);
    when(diag.getEndLine()).thenReturn(1);
    when(diag.getEndColumn()).thenReturn(5);
    when(diag.getText()).thenReturn("Don't do this");

    DiagnosticLocation secondary1 = mock(DiagnosticLocation.class);
    when(secondary1.getFilename()).thenReturn(filePath.toString());
    when(secondary1.getLine()).thenReturn(2);
    when(secondary1.getColumn()).thenReturn(3);
    when(secondary1.getEndLine()).thenReturn(2);
    when(secondary1.getEndColumn()).thenReturn(5);
    when(secondary1.getText()).thenReturn("Secondary 1");

    DiagnosticLocation secondary2 = mock(DiagnosticLocation.class);
    when(secondary2.getFilename()).thenReturn(filePath.toString());
    when(secondary2.getLine()).thenReturn(2);
    when(secondary2.getColumn()).thenReturn(8);
    when(secondary2.getEndLine()).thenReturn(2);
    when(secondary2.getEndColumn()).thenReturn(10);
    when(secondary2.getText()).thenReturn("Secondary 2");

    DiagnosticLocation secondaryOnAnotherFile = mock(DiagnosticLocation.class);
    when(secondaryOnAnotherFile.getFilename()).thenReturn(anotherFilePath.toString());
    when(secondaryOnAnotherFile.getLine()).thenReturn(2);
    when(secondaryOnAnotherFile.getColumn()).thenReturn(8);
    when(secondaryOnAnotherFile.getEndLine()).thenReturn(2);
    when(secondaryOnAnotherFile.getEndColumn()).thenReturn(10);
    when(secondaryOnAnotherFile.getText()).thenReturn("Another file");

    when(diag.getAdditionalLocations()).thenReturn(new DiagnosticLocation[]{secondary1, secondary2, secondaryOnAnotherFile});

    issueConsumer.accept(diag);

    Issue issue = sensorContext.allIssues().iterator().next();
    assertThat(issue.flows()).hasSize(2);
    assertThat(issue.flows()).allMatch(f -> f.locations().size() == 1);
    assertThat(issue.flows().get(0).locations()).extracting(l -> l.inputComponent(), l -> l.message(),
        l -> l.textRange().start().line(),
        l -> l.textRange().start().lineOffset(),
        l -> l.textRange().end().line(),
        l -> l.textRange().end().lineOffset())
      .containsOnly(tuple(file, "Secondary 1", 2, 2, 2, 4));
  }

  @Test
  void processQuickFixes() throws Exception {
    SensorContextTester sensorContext = SensorContextTester.create(baseDir);
    sensorContext.settings().appendProperty(CSharpPropertyDefinitions.getAnalyzerPath(), OmnisharpTestUtils.ANALYZER_JAR.toString());

    RuleKey ruleKey = RuleKey.of(OmnisharpPluginConstants.REPOSITORY_KEY, "S12345");
    sensorContext.setActiveRules(new ActiveRulesBuilder().addRule(new NewActiveRule.Builder().setRuleKey(ruleKey).build()).build());

    Path filePath = baseDir.resolve("Foo.cs");
    String content = "Console.WriteLine(\"Hello \n Woooooooooooooooooooooooooooorld!\");";
    Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
    Path anotherFilePath = baseDir.resolve("Bar.cs");

    InputFile file = TestInputFileBuilder.create("", "Foo.cs")
      .setModuleBaseDir(baseDir)
      .setLanguage(OmnisharpPluginConstants.LANGUAGE_KEY)
      .setCharset(StandardCharsets.UTF_8)
      .initMetadata(content)
      .build();
    sensorContext.fileSystem().add(file);

    // SensorContextTester is not quick fix aware yet
    sensorContext = spy(sensorContext);
    var issue = new MockSonarLintIssue();
    when(sensorContext.newIssue()).thenReturn(issue);

    ArgumentCaptor<Consumer<Diagnostic>> captor = ArgumentCaptor.forClass(Consumer.class);

    underTest.execute(sensorContext);

    verify(mockProtocol).codeCheck(eq(filePath.toFile()), captor.capture());

    Consumer<Diagnostic> issueConsumer = captor.getValue();

    Diagnostic diag = mock(Diagnostic.class);
    when(diag.getFilename()).thenReturn(filePath.toString());
    when(diag.getId()).thenReturn("S12345");
    when(diag.getLine()).thenReturn(1);
    when(diag.getColumn()).thenReturn(1);
    when(diag.getEndLine()).thenReturn(1);
    when(diag.getEndColumn()).thenReturn(5);
    when(diag.getText()).thenReturn("Don't do this");

    QuickFix qfWithOneFixAndOneEditSameFile = mockQuickFix("Quick Fix With One Edit Same File",
      mockFix(filePath,
        mockEdit(1, 2, 1, 4, "")));

    QuickFix qfWithTwoFixesAndTwoEditsSameFile = mockQuickFix("Quick Fix With Multiple Edits",
      mockFix(filePath,
        mockEdit(1, 2, 1, 4, ""),
        mockEdit(1, 6, 1, 8, "another")),
      mockFix(filePath,
        mockEdit(2, 12, 2, 14, ""),
        mockEdit(2, 16, 2, 18, "another")));

    QuickFix qfDifferentFile = mockQuickFix("Quick Fix Different File",
      mockFix(anotherFilePath,
        mockEdit(1, 2, 1, 4, "")));

    when(diag.getQuickFixes()).thenReturn(new QuickFix[]{qfWithOneFixAndOneEditSameFile, qfWithTwoFixesAndTwoEditsSameFile, qfDifferentFile});

    issueConsumer.accept(diag);

    var quickFixes = issue.getQuickFixes();
    assertThat(quickFixes)
      .extracting("message")
      .containsExactly("Quick Fix With One Edit Same File", "Quick Fix With Multiple Edits", "Quick Fix Different File");
    assertThat(quickFixes)
      .flatExtracting("inputFileEdits")
      .extracting("inputFile")
      .containsExactly(file, file, file/* why ? */);
    assertThat(quickFixes)
      .flatExtracting("inputFileEdits")
      .flatExtracting("textEdits")
      .extracting("textRange.start.line", "textRange.start.lineOffset", "textRange.end.line", "textRange.end.lineOffset", "newText")
      .containsExactly(tuple(1, 1, 1, 3, ""), tuple(1, 1, 1, 3, ""), tuple(1, 5, 1, 7, "another"), tuple(2, 11, 2, 13, ""), tuple(2, 15, 2, 17, "another"));
  }

  private static class MockSonarLintIssue implements NewIssue {
    private final List<MockSonarLintQuickFix> quickFixes = new ArrayList<>();

    @Override
    public NewIssue forRule(RuleKey ruleKey) {
      return this;
    }

    @Override
    public NewIssue gap(@Nullable Double aDouble) {
      return this;
    }

    @Override
    public NewIssue overrideSeverity(@Nullable Severity severity) {
      return this;
    }

    @Override
    public NewIssue overrideImpact(SoftwareQuality softwareQuality, org.sonar.api.issue.impact.Severity severity) {
      return null;
    }

    @Override
    public NewIssue at(NewIssueLocation newIssueLocation) {
      return this;
    }

    @Override
    public NewIssue addLocation(NewIssueLocation newIssueLocation) {
      return this;
    }

    @Override
    public NewIssue setQuickFixAvailable(boolean b) {
      return this;
    }

    @Override
    public NewIssue addFlow(Iterable<NewIssueLocation> iterable) {
      return this;
    }

    @Override
    public NewIssue addFlow(Iterable<NewIssueLocation> iterable, NewIssue.FlowType flowType, @Nullable String s) {
      return this;
    }

    @Override
    public NewIssueLocation newLocation() {
      return new MockIssueLocation();
    }

    @Override
    public void save() {
      // no op
    }

    @Override
    public NewIssue setRuleDescriptionContextKey(@Nullable String s) {
      return this;
    }

    @Override
    public NewIssue setCodeVariants(@Nullable Iterable<String> iterable) {
      return null;
    }

    @Override
    public MockSonarLintQuickFix newQuickFix() {
      return new MockSonarLintQuickFix();
    }

    @Override
    public NewIssue addQuickFix(org.sonar.api.batch.sensor.issue.fix.NewQuickFix newQuickFix) {
      quickFixes.add((MockSonarLintQuickFix) newQuickFix);
      return this;
    }

    public List<MockSonarLintQuickFix> getQuickFixes() {
      return quickFixes;
    }

    private static class MockSonarLintQuickFix implements NewQuickFix {
      private String message;
      private final List<MockSonarLintInputFileEdit> inputFileEdits = new ArrayList<>();

      @Override
      public NewQuickFix message(String message) {
        this.message = message;
        return this;
      }

      @Override
      public NewInputFileEdit newInputFileEdit() {
        return new MockSonarLintInputFileEdit();
      }

      @Override
      public NewQuickFix addInputFileEdit(NewInputFileEdit newInputFileEdit) {
        inputFileEdits.add((MockSonarLintInputFileEdit) newInputFileEdit);
        return this;
      }

      public String getMessage() {
        return message;
      }

      public List<MockSonarLintInputFileEdit> getInputFileEdits() {
        return inputFileEdits;
      }

      private static class MockSonarLintInputFileEdit implements NewInputFileEdit {
        private InputFile inputFile;
        private final List<MockSonarLintTextEdit> textEdits = new ArrayList<>();

        @Override
        public NewInputFileEdit on(InputFile inputFile) {
          this.inputFile = inputFile;
          return this;
        }

        @Override
        public NewTextEdit newTextEdit() {
          return new MockSonarLintTextEdit();
        }

        @Override
        public NewInputFileEdit addTextEdit(NewTextEdit newTextEdit) {
          textEdits.add((MockSonarLintTextEdit) newTextEdit);
          return this;
        }

        public InputFile getInputFile() {
          return inputFile;
        }

        public List<MockSonarLintTextEdit> getTextEdits() {
          return textEdits;
        }

        private static class MockSonarLintTextEdit implements NewTextEdit {
          private TextRange textRange;
          private String newText;

          @Override
          public NewTextEdit at(TextRange textRange) {
            this.textRange = textRange;
            return this;
          }

          @Override
          public NewTextEdit withNewText(String newText) {
            this.newText = newText;
            return this;
          }

          public TextRange getTextRange() {
            return textRange;
          }

          public String getNewText() {
            return newText;
          }
        }
      }
    }
  }

  private static class MockIssueLocation implements NewIssueLocation {

    @Override
    public NewIssueLocation on(InputComponent inputComponent) {
      return this;
    }

    @Override
    public NewIssueLocation at(TextRange textRange) {
      return this;
    }

    @Override
    public NewIssueLocation message(String s) {
      return this;
    }

    @Override
    public NewIssueLocation message(String message, List<NewMessageFormatting> newMessageFormatting) {
      return this;
    }

    @Override
    public NewMessageFormatting newMessageFormatting() {
      throw new UnsupportedOperationException();
    }
  }

}
