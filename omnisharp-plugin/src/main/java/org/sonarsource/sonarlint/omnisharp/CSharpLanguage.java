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

import java.util.Objects;
import org.sonar.api.config.Configuration;
import org.sonar.api.resources.AbstractLanguage;

public class CSharpLanguage extends AbstractLanguage {

  private final Configuration configuration;

  public CSharpLanguage(Configuration configuration) {
    super(OmnisharpPluginConstants.LANGUAGE_KEY, OmnisharpPluginConstants.LANGUAGE_NAME);
    this.configuration = configuration;
  }

  @Override
  public String[] getFileSuffixes() {
    return configuration.getStringArray(CSharpPropertyDefinitions.FILE_SUFFIXES_KEY);
  }

  @Override
  public boolean equals(Object o) {
    return super.equals(o) && o instanceof CSharpLanguage && configuration == ((CSharpLanguage) o).configuration;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), configuration.hashCode());
  }
}
