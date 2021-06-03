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

import java.io.IOException;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.rule.RuleKey;

public class OmnisharpSensor implements Sensor {

  private final OmnisharpServer server;

  public OmnisharpSensor(OmnisharpServer server) {
    this.server = server;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("Onmisharp")
      .onlyOnLanguage(CSharpPlugin.LANGUAGE_KEY)
      .createIssuesForRuleRepositories(CSharpPlugin.REPOSITORY_KEY)
      .onlyWhenConfiguration(c -> c.hasKey(CSharpPropertyDefinitions.getOmnisharpLocation()));
  }

  @Override
  public void execute(SensorContext context) {
    FilePredicate predicate = context.fileSystem().predicates().hasLanguage(CSharpPlugin.LANGUAGE_KEY);
    if (context.fileSystem().hasFiles(predicate)) {
      try {
        server.lazyStart(context.fileSystem().baseDir().toPath());
      } catch (Exception e) {
        throw new IllegalStateException("Unable to start OmniSharp", e);
      }
    }
    for (InputFile f : context.fileSystem().inputFiles(predicate)) {
      String buffer;
      try {
        buffer = f.contents();
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read file buffer", e);
      }
      server.updateBuffer(f.absolutePath(), buffer);
      server.codeCheck(f.absolutePath(), diag -> handle(context, f, diag));
    }
  }

  private void handle(SensorContext context, InputFile f, OmnisharpDiagnostic diag) {
    NewIssue newIssue = context.newIssue();
    newIssue
      .forRule(RuleKey.of(CSharpPlugin.REPOSITORY_KEY, diag.id))
      .at(
        newIssue.newLocation()
          .on(f)
          .at(f.newRange(diag.line, diag.column - 1, diag.endLine, diag.endColumn - 1))
          .message(diag.text))
      .save();
  }

}
