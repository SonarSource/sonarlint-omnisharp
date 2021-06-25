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
/*
 * SonarC#
 * Copyright (C) 2014-2021 SonarSource SA
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.analyzer.commons.ProgressReport;

public class OmnisharpSensor implements Sensor {

  private static final Logger LOG = Loggers.get(OmnisharpSensor.class);

  private final OmnisharpServer server;
  private final OmnisharpProtocol omnisharpProtocol;
  private List<String> allRulesKeys;

  public OmnisharpSensor(OmnisharpServer server, OmnisharpProtocol omnisharpProtocol) {
    this.server = server;
    this.omnisharpProtocol = omnisharpProtocol;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("OmniSharp")
      .onlyOnLanguage(CSharpPlugin.LANGUAGE_KEY)
      .createIssuesForRuleRepositories(CSharpPlugin.REPOSITORY_KEY)
      .onlyWhenConfiguration(c -> c.hasKey(CSharpPropertyDefinitions.getOmnisharpLocation()));
  }

  @Override
  public void execute(SensorContext context) {
    FilePredicate predicate = context.fileSystem().predicates().hasLanguage(CSharpPlugin.LANGUAGE_KEY);
    if (!context.fileSystem().hasFiles(predicate)) {
      return;
    }
    try {
      Path dotnetCliExePath = context.config().get(CSharpPropertyDefinitions.getDotnetCliExeLocation()).map(Paths::get).orElse(null);
      server.lazyStart(context.fileSystem().baseDir().toPath(), dotnetCliExePath);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted", e);
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      throw new IllegalStateException("Unable to start OmniSharp", e);
    }

    JsonObject config = new JsonObject();
    JsonArray rulesJson = new JsonArray();
    for (String rule : getAllRulesKeys()) {
      JsonObject ruleJson = new JsonObject();
      ruleJson.addProperty("ruleId", rule);
      ActiveRule activeRule = context.activeRules().find(RuleKey.of(CSharpPlugin.REPOSITORY_KEY, rule));
      ruleJson.addProperty("isEnabled", activeRule != null);
      if (activeRule != null && !activeRule.params().isEmpty()) {
        JsonObject paramsJson = new JsonObject();
        for (Map.Entry<String, String> param : activeRule.params().entrySet()) {
          paramsJson.addProperty(param.getKey(), param.getValue());
        }
        ruleJson.add("params", paramsJson);
      }
      rulesJson.add(ruleJson);
    }
    config.add("rules", rulesJson);
    omnisharpProtocol.config(config);

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

  /*
   * We need the complete list of rule keys, including hotspots, to disabled them and not
   * rely on default activation of the Roslyn rules.
   * Instead of parsing XML again, I'm just doing some basic line matching...
   */
  List<String> getAllRulesKeys() {
    if (allRulesKeys == null) {
      allRulesKeys = new ArrayList<>();
      CSharpSonarRulesDefinition.withRulesXml(reader -> {
        try (BufferedReader br = new BufferedReader(reader)) {
          for (String line; (line = br.readLine()) != null;) {
            line = line.trim();
            if (line.startsWith("<key>S") && line.endsWith("</key>")) {
              allRulesKeys.add(line.substring("<key>".length(), line.length() - "</key>".length()));
            }
          }
        } catch (IOException e) {
          throw new IllegalStateException("Unable to load rules", e);
        }
      });
    }
    return allRulesKeys;
  }

  private void scanFile(SensorContext context, InputFile f) {
    String buffer;
    try {
      buffer = f.contents();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read file buffer", e);
    }
    omnisharpProtocol.updateBuffer(f.file(), buffer);
    omnisharpProtocol.codeCheck(f.file(), diag -> handle(context, f, diag));
  }

  private static void handle(SensorContext context, InputFile f, OmnisharpDiagnostic diag) {
    RuleKey ruleKey = RuleKey.of(CSharpPlugin.REPOSITORY_KEY, diag.id);
    if (context.activeRules().find(ruleKey) != null) {
      NewIssue newIssue = context.newIssue();
      newIssue
        .forRule(ruleKey)
        .at(
          newIssue.newLocation()
            .on(f)
            .at(f.newRange(diag.line, diag.column - 1, diag.endLine, diag.endColumn - 1))
            .message(diag.text))
        .save();
    }
  }

}
