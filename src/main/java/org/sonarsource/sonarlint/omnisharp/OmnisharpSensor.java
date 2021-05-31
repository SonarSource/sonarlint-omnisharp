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

import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;

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
    server.analyze(context);
  }

}
