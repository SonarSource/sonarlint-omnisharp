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

import java.util.ArrayList;
import java.util.List;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;

import static org.sonarsource.sonarlint.omnisharp.CSharpPlugin.LANGUAGE_KEY;

public class CSharpPropertyDefinitions {

  private static final String PROP_PREFIX = "sonar.";

  public List<PropertyDefinition> create() {
    List<PropertyDefinition> result = new ArrayList<>();
    result.add(
      PropertyDefinition.builder(getOmnisharpLocation())
        .multiValues(true)
        .hidden()
        .build());
    result.add(
      PropertyDefinition.builder(getFileSuffixProperty())
        .category(CSharpPlugin.LANGUAGE_NAME)
        .defaultValue(CSharpPlugin.FILE_SUFFIXES_DEFVALUE)
        .name("File suffixes")
        .description("Comma-separated list of suffixes of files to analyze.")
        .multiValues(true)
        .onQualifiers(Qualifiers.PROJECT)
        .build());
    return result;
  }

  public static String getFileSuffixProperty() {
    return PROP_PREFIX + LANGUAGE_KEY + ".file.suffixes";
  }

  public static String getOmnisharpLocation() {
    return PROP_PREFIX + LANGUAGE_KEY + ".omnisharpPath";
  }
}
