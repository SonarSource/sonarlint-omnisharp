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

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public final class DotNetSdkPathResolver {

  private static final Pattern SDK_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?(?:[-\\w.]+)?");
  // OmniSharp 1.39.x supports .NET SDK major versions up to 9; SDK 10+ causes MSBuild project system initialization failures
  private static final int MAX_COMPATIBLE_SDK_MAJOR_VERSION = 9;

  private DotNetSdkPathResolver() {
    // utility class
  }

  public static final class SdkConfiguration {
    private final Path path;
    private final String version;

    public SdkConfiguration(Path path, String version) {
      this.path = path;
      this.version = version;
    }

    public Path path() {
      return path;
    }

    public String version() {
      return version;
    }
  }

  public static Optional<SdkConfiguration> fromPathHint(@Nullable Path pathHint) {
    if (pathHint == null) {
      return Optional.empty();
    }
    Path normalized = pathHint.normalize();
    var fromSdkDirectory = extractFromSdkDirectoryStructure(normalized);
    if (fromSdkDirectory.isPresent()) {
      return fromSdkDirectory;
    }
    return extractFromRootRelativePath(normalized);
  }

  private static Optional<SdkConfiguration> extractFromSdkDirectoryStructure(Path normalized) {
    Path current = normalized;
    while (current != null) {
      Path parent = current.getParent();
      if (parent != null && isSdkDirectory(parent)) {
        String version = current.getFileName().toString();
        if (isSdkVersion(version)) {
          return Optional.of(new SdkConfiguration(current, version));
        }
      }
      current = parent;
    }
    return Optional.empty();
  }

  private static Optional<SdkConfiguration> extractFromRootRelativePath(Path normalized) {
    if (normalized.getParent() != null) {
      return Optional.empty();
    }
    Path lastSegmentPath = normalized.getFileName();
    if (lastSegmentPath == null) {
      return Optional.empty();
    }
    String lastSegment = lastSegmentPath.toString();
    if (isSdkVersion(lastSegment)) {
      return Optional.of(new SdkConfiguration(normalized, lastSegment));
    }
    return Optional.empty();
  }

  private static boolean isSdkDirectory(Path parent) {
    var parentName = parent.getFileName();
    return parentName != null && "sdk".equalsIgnoreCase(parentName.toString());
  }

  public static Path toVersionedSdkPath(Path path, String version) {
    Path normalized = path.normalize();
    var fileName = normalized.getFileName();
    if (fileName != null && fileName.toString().equals(version)) {
      return normalized;
    }
    return normalized.resolve(version);
  }

  public static boolean isCompatibleSdkVersion(String version) {
    int majorVersion = parseMajorVersion(version);
    return majorVersion >= 1 && majorVersion <= MAX_COMPATIBLE_SDK_MAJOR_VERSION;
  }

  static int parseMajorVersion(String version) {
    int dotIndex = version.indexOf('.');
    if (dotIndex <= 0) {
      return -1;
    }
    try {
      return Integer.parseInt(version.substring(0, dotIndex));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static boolean isSdkVersion(String value) {
    return SDK_VERSION_PATTERN.matcher(value).matches();
  }

}
