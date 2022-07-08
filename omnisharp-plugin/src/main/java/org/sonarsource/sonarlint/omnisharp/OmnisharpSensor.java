/*
 * SonarOmnisharp
 * Copyright (C) 2021-2022 SonarSource SA
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.analyzer.commons.ProgressReport;
import org.sonarsource.sonarlint.omnisharp.protocol.Diagnostic;
import org.sonarsource.sonarlint.omnisharp.protocol.DiagnosticLocation;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;

public class OmnisharpSensor implements Sensor {

  private static final Logger LOG = Loggers.get(OmnisharpSensor.class);

  private final OmnisharpServerController server;
  private final OmnisharpEndpoints omnisharpEndpoints;

  public OmnisharpSensor(OmnisharpServerController server, OmnisharpEndpoints omnisharpEndpoints) {
    this.server = server;
    this.omnisharpEndpoints = omnisharpEndpoints;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("OmniSharp")
      .onlyOnLanguage(OmnisharpPlugin.LANGUAGE_KEY)
      .createIssuesForRuleRepositories(OmnisharpPlugin.REPOSITORY_KEY)
      .onlyWhenConfiguration(c -> c.hasKey(CSharpPropertyDefinitions.getOmnisharpMonoLocation())
        || c.hasKey(CSharpPropertyDefinitions.getOmnisharpWinLocation())
        || c.hasKey(CSharpPropertyDefinitions.getOmnisharpNet6Location()));
  }

  @Override
  public void execute(SensorContext context) {
    FilePredicate predicate = context.fileSystem().predicates().hasLanguage(OmnisharpPlugin.LANGUAGE_KEY);
    if (!context.fileSystem().hasFiles(predicate)) {
      return;
    }
    try {
      Path dotnetCliExePath = context.config().get(CSharpPropertyDefinitions.getDotnetCliExeLocation()).map(Paths::get).orElse(null);
      Path monoExePath = context.config().get(CSharpPropertyDefinitions.getMonoExeLocation()).map(Paths::get).orElse(null);
      Path msBuildPath = context.config().get(CSharpPropertyDefinitions.getMSBuildPath()).map(Paths::get).orElse(null);
      Path solutionPath = context.config().get(CSharpPropertyDefinitions.getSolutionPath()).map(Paths::get).orElse(null);
      boolean useFramework = context.config().getBoolean(CSharpPropertyDefinitions.getUseNet6()).orElse(false);
      boolean loadProjectsOnDemand = context.config().getBoolean(CSharpPropertyDefinitions.getLoadProjectsOnDemand()).orElse(false);
      int startupTimeOutSec = context.config().getInt(CSharpPropertyDefinitions.getStartupTimeout()).orElse(60);
      int loadProjectsTimeOutSec = context.config().getInt(CSharpPropertyDefinitions.getLoadProjectsTimeout()).orElse(60);
      server.lazyStart(context.fileSystem().baseDir().toPath(), useFramework, loadProjectsOnDemand, dotnetCliExePath, monoExePath, msBuildPath, solutionPath, startupTimeOutSec,
        loadProjectsTimeOutSec);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted", e);
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to start OmniSharp", e);
    }

    try {
      server.whenReady().get();
      analyze(context, predicate);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof TimeoutException) {
        LOG.error("Timeout waiting for the solution to be loaded. You can find help on https://github.com/SonarSource/sonarlint-intellij/wiki/Rider");
        return;
      }
      throw new IllegalStateException("Analysis failed: " + e.getMessage(), e.getCause());
    }
  }

  private void analyze(SensorContext context, FilePredicate predicate) {
    JsonObject config = buildRulesConfig(context);
    omnisharpEndpoints.config(config);

    ProgressReport progressReport = new ProgressReport("Report about progress of OmniSharp analyzer", TimeUnit.SECONDS.toMillis(10));
    progressReport.start(StreamSupport.stream(context.fileSystem().inputFiles(predicate).spliterator(), false).map(InputFile::toString).collect(Collectors.toList()));
    boolean successfullyCompleted = false;
    boolean cancelled = false;
    try {
      for (InputFile inputFile : context.fileSystem().inputFiles(predicate)) {
        if (context.isCancelled()) {
          cancelled = true;
          break;
        }
        scanFile(context, inputFile);
        progressReport.nextFile();
      }
      successfullyCompleted = !cancelled;
    } finally {
      if (successfullyCompleted) {
        progressReport.stop();
      } else {
        progressReport.cancel();
      }
    }
  }

  private static JsonObject buildRulesConfig(SensorContext context) {
    JsonObject config = new JsonObject();
    JsonArray rulesJson = new JsonArray();
    for (ActiveRule activeRule : context.activeRules().findByRepository(OmnisharpPlugin.REPOSITORY_KEY)) {
      JsonObject ruleJson = new JsonObject();
      ruleJson.addProperty("ruleId", activeRule.ruleKey().rule());
      if (!activeRule.params().isEmpty()) {
        JsonObject paramsJson = new JsonObject();
        for (Map.Entry<String, String> param : activeRule.params().entrySet()) {
          paramsJson.addProperty(param.getKey(), param.getValue());
        }
        ruleJson.add("params", paramsJson);
      }
      rulesJson.add(ruleJson);
    }
    config.add("activeRules", rulesJson);
    return config;
  }

  private void scanFile(SensorContext context, InputFile f) {
    String buffer;
    try {
      buffer = f.contents();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read file buffer", e);
    }
    omnisharpEndpoints.updateBuffer(f.file(), buffer);
    omnisharpEndpoints.codeCheck(f.file(), diag -> handle(context, diag));
  }

  private static void handle(SensorContext context, Diagnostic diag) {
    RuleKey ruleKey = RuleKey.of(OmnisharpPlugin.REPOSITORY_KEY, diag.getId());
    if (context.activeRules().find(ruleKey) != null) {
      Path diagFilePath = Paths.get(diag.getFilename());
      InputFile diagInputFile = context.fileSystem().inputFile(context.fileSystem().predicates().is(diagFilePath.toFile()));
      if (diagInputFile != null) {
        NewIssue newIssue = context.newIssue();
        newIssue
          .forRule(ruleKey)
          .at(createLocation(newIssue, diag, diagInputFile));
        DiagnosticLocation[] additionalLocations = diag.getAdditionalLocations();
        if (additionalLocations != null) {
          for (DiagnosticLocation additionalLocation : additionalLocations) {
            Path additionalFilePath = Paths.get(additionalLocation.getFilename());
            InputFile additionalFilePathInputFile = context.fileSystem().inputFile(context.fileSystem().predicates().is(additionalFilePath.toFile()));
            if (additionalFilePathInputFile != null) {
              newIssue.addLocation(createLocation(newIssue, additionalLocation, additionalFilePathInputFile));
            }
          }
        }
        newIssue.save();
      }
    }
  }

  private static NewIssueLocation createLocation(NewIssue newIssue, DiagnosticLocation location, InputFile inputFile) {
    return newIssue.newLocation()
      .on(inputFile)
      .at(inputFile.newRange(location.getLine(), location.getColumn() - 1, location.getEndLine(), location.getEndColumn() - 1))
      .message(location.getText());
  }

}
