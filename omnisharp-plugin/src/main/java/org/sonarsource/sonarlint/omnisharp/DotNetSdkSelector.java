/*
 * SonarOmnisharp
 * Copyright (C) SonarSource Sàrl
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;

@SonarLintSide(lifespan = SonarLintSide.MODULE)
public class DotNetSdkSelector {

  private static final Logger LOG = Loggers.get(DotNetSdkSelector.class);
  private static final Pattern LIST_SDKS_PATTERN = Pattern.compile("^(\\S+)\\s+\\[(.+)]$");
  private static final int PROCESS_TIMEOUT_SEC = 30;

  private final System2 system2;

  public DotNetSdkSelector(System2 system2) {
    this.system2 = system2;
  }

  @Nullable
  public Path selectCompatibleSdkPath(Path projectBaseDir, @Nullable Path sdkPathHint, @Nullable Path dotnetCliPath) {
    var compatibleHint = DotNetSdkPathResolver.fromPathHint(sdkPathHint)
      .filter(sdk -> DotNetSdkPathResolver.isCompatibleSdkVersion(sdk.version()))
      .map(DotNetSdkPathResolver.SdkConfiguration::path)
      .orElse(null);
    if (compatibleHint != null) {
      return compatibleHint;
    }

    if (sdkPathHint != null) {
      DotNetSdkPathResolver.fromPathHint(sdkPathHint).ifPresent(sdk ->
        LOG.info("Ignoring incompatible .NET SDK hint {} (OmniSharp 1.39.x does not support SDK {}+)",
          sdk.path(), DotNetSdkPathResolver.parseMajorVersion(sdk.version()) + 1));
    }

    var globalJsonVersion = readGlobalJsonSdkVersion(projectBaseDir);
    var installedSdks = listInstalledSdks(dotnetCliPath).stream()
      .filter(sdk -> DotNetSdkPathResolver.isCompatibleSdkVersion(sdk.version()))
      .collect(Collectors.toList());

    if (installedSdks.isEmpty()) {
      LOG.warn("No .NET SDK compatible with OmniSharp found on the machine");
      return null;
    }

    var selected = globalJsonVersion
      .flatMap(requested -> installedSdks.stream()
        .filter(sdk -> sdk.version().startsWith(requested) || requested.startsWith(sdk.version()))
        .max(Comparator.comparing(SdkInfo::version, DotNetSdkSelector::compareSdkVersions)))
      .orElseGet(() -> installedSdks.stream().max(Comparator.comparing(SdkInfo::version, DotNetSdkSelector::compareSdkVersions)).orElseThrow());

    LOG.info("Selected .NET SDK {} for OmniSharp analysis", selected.path());
    return selected.path();
  }

  private static Optional<String> readGlobalJsonSdkVersion(Path projectBaseDir) {
    var current = projectBaseDir.toAbsolutePath().normalize();
    while (current != null) {
      var globalJson = current.resolve("global.json");
      if (Files.isRegularFile(globalJson)) {
        try {
          var json = JsonParser.parseString(Files.readString(globalJson, StandardCharsets.UTF_8)).getAsJsonObject();
          if (json.has("sdk") && json.get("sdk").isJsonObject()) {
            JsonObject sdk = json.getAsJsonObject("sdk");
            if (sdk.has("version")) {
              return Optional.of(sdk.get("version").getAsString());
            }
          }
        } catch (Exception e) {
          LOG.debug("Unable to read global.json at {}", globalJson, e);
        }
        return Optional.empty();
      }
      current = current.getParent();
    }
    return Optional.empty();
  }

  private List<SdkInfo> listInstalledSdks(@Nullable Path dotnetCliPath) {
    var dotnetExecutable = resolveDotnetExecutable(dotnetCliPath);
    var command = new ArrayList<String>();
    command.add(dotnetExecutable);
    command.add("--list-sdks");

    try {
      var process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        LOG.warn("Timed out while listing installed .NET SDKs");
        return List.of();
      }
      if (process.exitValue() != 0) {
        LOG.warn("Failed to list installed .NET SDKs (exit code {})", process.exitValue());
        return List.of();
      }
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        return reader.lines()
          .map(String::trim)
          .filter(line -> !line.isEmpty())
          .map(DotNetSdkSelector::parseListSdksLine)
          .flatMap(Optional::stream)
          .collect(Collectors.toList());
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("Unable to list installed .NET SDKs", e);
      return List.of();
    }
  }

  private String resolveDotnetExecutable(@Nullable Path dotnetCliPath) {
    if (dotnetCliPath != null) {
      return dotnetCliPath.toString();
    }
    return system2.isOsWindows() ? "dotnet.exe" : "dotnet";
  }

  static Optional<SdkInfo> parseListSdksLine(String line) {
    Matcher matcher = LIST_SDKS_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new SdkInfo(matcher.group(1), Path.of(matcher.group(2))));
  }

  static int compareSdkVersions(String left, String right) {
    var leftParts = left.split("[.-]");
    var rightParts = right.split("[.-]");
    int length = Math.max(leftParts.length, rightParts.length);
    for (int i = 0; i < length; i++) {
      int leftPart = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
      int rightPart = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
      if (leftPart != rightPart) {
        return Integer.compare(leftPart, rightPart);
      }
    }
    return 0;
  }

  private static int parseVersionPart(String part) {
    try {
      return Integer.parseInt(part.replaceAll("\\D.*", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  static final class SdkInfo {
    private final String version;
    private final Path path;

    private SdkInfo(String version, Path path) {
      this.version = version;
      this.path = path;
    }

    String version() {
      return version;
    }

    Path path() {
      return path;
    }
  }

}
