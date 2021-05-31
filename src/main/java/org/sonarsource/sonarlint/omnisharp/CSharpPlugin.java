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

import org.sonar.api.Plugin;
import org.sonar.api.SonarProduct;
import org.sonarsource.dotnet.shared.plugins.AbstractPropertyDefinitions;
import org.sonarsource.dotnet.shared.plugins.DotNetPluginMetadata;
import org.sonarsource.dotnet.shared.plugins.RoslynProfileExporter;

public class CSharpPlugin implements Plugin {

  static final String LANGUAGE_KEY = "cs";
  static final String LANGUAGE_NAME = "C#";

  static final String REPOSITORY_KEY = "csharpsquid";
  static final String REPOSITORY_NAME = "SonarAnalyzer";
  static final String PLUGIN_KEY = "csharp";
  static final String SONARANALYZER_NAME = "SonarAnalyzer.CSharp";

  static final String FILE_SUFFIXES_KEY = AbstractPropertyDefinitions.getFileSuffixProperty(LANGUAGE_KEY);
  static final String FILE_SUFFIXES_DEFVALUE = ".cs";

  static final DotNetPluginMetadata METADATA = new CSharpPluginMetadata();

  @Override
  public void define(Context context) {
    if (context.getRuntime().getProduct() == SonarProduct.SONARLINT) {
      context.addExtension(OmnisharpServer.class);
      context.addExtension(OmnisharpSensor.class);
    }

    context.addExtensions(
      // language-specific
      METADATA,
      CSharp.class,
      CSharpSonarRulesDefinition.class);

    context.addExtensions(new CSharpPropertyDefinitions(context.getRuntime()).create());
    context.addExtensions(RoslynProfileExporter.sonarLintRepositoryProperties(METADATA));
  }

  private static class CSharpPluginMetadata implements DotNetPluginMetadata {

    @Override
    public String languageKey() {
      return LANGUAGE_KEY;
    }

    @Override
    public String pluginKey() {
      return PLUGIN_KEY;
    }

    @Override
    public String languageName() {
      return LANGUAGE_NAME;
    }

    @Override
    public String shortLanguageName() {
      return LANGUAGE_NAME;
    }

    @Override
    public String sonarAnalyzerName() {
      return SONARANALYZER_NAME;
    }

    @Override
    public String repositoryKey() {
      return REPOSITORY_KEY;
    }
  }
}
