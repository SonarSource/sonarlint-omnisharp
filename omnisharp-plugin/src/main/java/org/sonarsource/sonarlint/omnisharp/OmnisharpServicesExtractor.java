/*
 * SonarOmnisharp
 * Copyright (C) 2021-2024 SonarSource SA
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import org.sonar.api.Startable;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.ZipUtils;
import org.sonarsource.api.sonarlint.SonarLintSide;

import static java.util.Objects.requireNonNull;

@ScannerSide
@SonarLintSide(lifespan = SonarLintSide.MULTIPLE_ANALYSES)
public class OmnisharpServicesExtractor implements Startable {

  private static final String SERVICES_DLL_FILENAME = "SonarLint.OmniSharp.DotNet.Services.dll";

  private static final String OMNISHARP_SERVICES_LOCATION = "slServices";

  private Path omnisharpServicesDir;

  private final TempFolder tempFolder;

  public OmnisharpServicesExtractor(TempFolder tempFolder) {
    this.tempFolder = tempFolder;
  }

  @Override
  public void start() {
    String analyzerVersion = loadAnalyzerVersion();
    this.omnisharpServicesDir = tempFolder.newDir(OMNISHARP_SERVICES_LOCATION).toPath();
    unzipAnalyzer(analyzerVersion);
    extractOmnisharpServicesDll();
  }

  public Path getOmnisharpServicesDllPath() {
    return omnisharpServicesDir.resolve(SERVICES_DLL_FILENAME);
  }

  private void extractOmnisharpServicesDll() {
    try (InputStream bundle = getClass().getResourceAsStream("/" + SERVICES_DLL_FILENAME)) {
      requireNonNull(bundle, SERVICES_DLL_FILENAME + " not found in plugin jar");
      Files.copy(bundle, omnisharpServicesDir.resolve(SERVICES_DLL_FILENAME));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract services", e);
    }
  }

  private void unzipAnalyzer(String analyzerVersion) {
    try (InputStream bundle = getClass().getResourceAsStream("/static/SonarAnalyzer-" + analyzerVersion + ".zip")) {
      requireNonNull(bundle, "SonarAnalyzer not found in plugin jar");
      ZipUtils.unzip(bundle, omnisharpServicesDir.resolve("analyzers").toFile(), (Predicate<ZipEntry>) ze -> ze.getName().endsWith(".dll"));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract analyzers", e);
    }
  }

  private String loadAnalyzerVersion() {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/analyzer-version.txt"), StandardCharsets.UTF_8))) {
      return reader.lines().findFirst().orElseThrow(() -> new IllegalStateException("Unable to read analyzer version"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void stop() {
    // Nothing to do
  }

}
