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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OmnisharpResponseProcessorTests {

  private OmnisharpResponseProcessor underTest;
  private CompletableFuture<Void> startFuture;
  private CompletableFuture<Void> loadProjectsFuture;

  @BeforeEach
  void prepare() {
    underTest = new OmnisharpResponseProcessor();
    startFuture = new CompletableFuture<>();
    loadProjectsFuture = new CompletableFuture<>();
  }

  @Test
  void project_events_do_not_complete_project_loading() {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture,
      "{\"Type\":\"event\",\"Event\":\"ProjectAdded\",\"Body\":{}}");

    assertThat(loadProjectsFuture.isDone()).isFalse();
  }

  @Test
  void log_events_do_not_complete_project_loading() {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture,
      "{\"Type\":\"event\",\"Event\":\"log\",\"Body\":{\"LogLevel\":\"Error\",\"Message\":\"Some error\"}}");

    assertThat(loadProjectsFuture.isDone()).isFalse();
  }

  @Test
  void msbuild_project_diagnostics_do_not_complete_project_loading() {
    underTest.handleOmnisharpOutput(startFuture, loadProjectsFuture,
      "{\"Type\":\"event\",\"Event\":\"MsBuildProjectDiagnostics\",\"Body\":{\"Errors\":[\"Some MSBuild error\"]}}");

    assertThat(loadProjectsFuture.isDone()).isFalse();
  }

}
