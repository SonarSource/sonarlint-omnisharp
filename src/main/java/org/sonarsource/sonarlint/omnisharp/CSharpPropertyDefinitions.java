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

import java.util.ArrayList;
import java.util.List;
import org.sonar.api.SonarRuntime;
import org.sonar.api.config.PropertyDefinition;
import org.sonarsource.dotnet.shared.plugins.AbstractPropertyDefinitions;

public class CSharpPropertyDefinitions extends AbstractPropertyDefinitions {

  public CSharpPropertyDefinitions(SonarRuntime runtime) {
    super(CSharpPlugin.LANGUAGE_KEY, CSharpPlugin.LANGUAGE_NAME, CSharpPlugin.FILE_SUFFIXES_DEFVALUE, runtime);
  }

  @Override
  public List<PropertyDefinition> create() {
    List<PropertyDefinition> result = new ArrayList<>(super.create());
    result.add(
      PropertyDefinition.builder(getOmnisharpLocation())
        .multiValues(true)
        .hidden()
        .build());
    return result;
  }

  public static String getOmnisharpLocation() {
    return "sonar." + CSharpPlugin.LANGUAGE_KEY + ".omnisharpPath";
  }
}
