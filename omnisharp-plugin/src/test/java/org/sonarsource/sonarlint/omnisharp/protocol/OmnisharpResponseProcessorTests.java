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
package org.sonarsource.sonarlint.omnisharp.protocol;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;

import static org.assertj.core.api.Assertions.assertThat;

class OmnisharpResponseProcessorTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpResponseProcessor underTest;
  private CompletableFuture<Void> startFuture;
  private CompletableFuture<Void> loadProjectsFuture;

  @BeforeEach
  void prepare() {
    logTester.setLevel(LoggerLevel.DEBUG);
    underTest = new OmnisharpResponseProcessor();
    startFuture = new CompletableFuture<>();
    loadProjectsFuture = new CompletableFuture<>();
  }

  @ParameterizedTest
  @MethodSource("eventsThatDoNotCompleteProjectLoading")
  void events_do_not_complete_project_loading(String message) {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture, message);

    assertThat(loadProjectsFuture.isDone()).isFalse();
  }

  private static Stream<String> eventsThatDoNotCompleteProjectLoading() {
    return Stream.of(
      "{\"Type\":\"event\",\"Event\":\"ProjectAdded\",\"Body\":{}}",
      "{\"Type\":\"event\",\"Event\":\"log\",\"Body\":{\"LogLevel\":\"Error\",\"Message\":\"Some error\"}}",
      "{\"Type\":\"event\",\"Event\":\"MsBuildProjectDiagnostics\",\"Body\":{\"Errors\":[\"Some MSBuild error\"]}}");
  }

  @Test
  void msbuild_project_diagnostics_with_errors_are_logged() {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture,
      "{\"Type\":\"event\",\"Event\":\"MsBuildProjectDiagnostics\",\"Body\":{\"Errors\":[\"Some MSBuild error\"]}}");

    assertThat(logTester.logs(LoggerLevel.ERROR)).contains("MSBuild failed to load the project");
  }

  @Test
  void log_event_with_missing_fields_is_ignored() {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture,
      "{\"Type\":\"event\",\"Event\":\"log\",\"Body\":{}}");

    assertThat(logTester.logs()).isEmpty();
    assertThat(loadProjectsFuture.isDone()).isFalse();
  }

}
