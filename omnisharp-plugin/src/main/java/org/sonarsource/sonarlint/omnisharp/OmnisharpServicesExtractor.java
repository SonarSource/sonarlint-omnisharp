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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.sonar.api.Startable;
import org.sonar.api.config.Configuration;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.ZipUtils;
import org.sonarsource.api.sonarlint.SonarLintSide;

import static java.util.Objects.requireNonNull;

@ScannerSide
@SonarLintSide(lifespan = SonarLintSide.INSTANCE)
public class OmnisharpServicesExtractor {

  private static final String SERVICES_DLL_FILENAME = "SonarLint.OmniSharp.DotNet.Services.dll";

  private static final String OMNISHARP_SERVICES_LOCATION = "slServices";

  private Path analyzerZipPath;
  private Path omnisharpServicesDir;

  private final TempFolder tempFolder;
  private final Configuration configuration;

  public OmnisharpServicesExtractor(TempFolder tempFolder, Configuration configuration) {
    this.tempFolder = tempFolder;
    this.configuration = configuration;
  }

  public Path getOmnisharpServicesDllPath() {
    if (omnisharpServicesDir == null) {
      unzipAnalyzerPlugin();
      this.omnisharpServicesDir = tempFolder.newDir(OMNISHARP_SERVICES_LOCATION).toPath();
      unzipAnalyzer();
      extractOmnisharpServicesDll();
    }
    return omnisharpServicesDir.resolve(SERVICES_DLL_FILENAME);
  }

  private void unzipAnalyzerPlugin() {
    var pluginUnzipDir = tempFolder.newDir("pluginZip");
    var analyzerPluginPath = configuration.get(CSharpPropertyDefinitions.getAnalyzerPath()).orElse(null);
    try (InputStream analyzerPlugin = new FileInputStream(analyzerPluginPath)) {
      requireNonNull(analyzerPlugin, "Plugin jar not found");
      ZipUtils.unzip(analyzerPlugin, pluginUnzipDir, ze -> ze.getName().endsWith(".zip"));
      analyzerZipPath = Stream.of(new File(pluginUnzipDir, "static").listFiles((dir, name) -> name.endsWith(".zip")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Unable to find analyzer ZIP"))
        .toPath();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract analyzers", e);
    }
  }

  private void extractOmnisharpServicesDll() {
    try (InputStream bundle = getClass().getResourceAsStream("/" + SERVICES_DLL_FILENAME)) {
      requireNonNull(bundle, SERVICES_DLL_FILENAME + " not found in plugin jar");
      Files.copy(bundle, omnisharpServicesDir.resolve(SERVICES_DLL_FILENAME));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract services", e);
    }
  }

  private void unzipAnalyzer() {
    try (InputStream bundle = new FileInputStream(analyzerZipPath.toFile())) {
      requireNonNull(bundle, "SonarAnalyzer not found in extracted plugin jar");
      ZipUtils.unzip(bundle, omnisharpServicesDir.resolve("analyzers").toFile(), ze -> ze.getName().endsWith(".dll"));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract analyzers", e);
    }
  }
}
