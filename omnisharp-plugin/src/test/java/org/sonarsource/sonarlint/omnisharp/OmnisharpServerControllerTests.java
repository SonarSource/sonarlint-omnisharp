/*
 * SonarOmnisharp
 * Copyright (C) 2021-2022 SonarSource SA
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertionsProvider.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class OmnisharpServerControllerTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  @TempDir
  public Path omnisharpDir;

  private OmnisharpServerController underTest;
  private Path solutionDir;
  private Path anotherSolutionDir;
  private OmnisharpEndpoints endpoints;
  private OmnisharpCommandBuilder commandBuilder;
  private final List<String> processedOutput = new CopyOnWriteArrayList<>();

  @BeforeEach
  void prepare(@TempDir Path tmpDir) throws IOException {
    logTester.setLevel(LoggerLevel.DEBUG);
    solutionDir = tmpDir.resolve("solution");
    anotherSolutionDir = tmpDir.resolve("anotherSolution");
    endpoints = mock(OmnisharpEndpoints.class);
    commandBuilder = mock(OmnisharpCommandBuilder.class);
    underTest = new OmnisharpServerController(endpoints, new FakeOmnisharpResponseProcessor(), commandBuilder, Duration.of(1, ChronoUnit.SECONDS),
      Duration.of(1, ChronoUnit.SECONDS));
    // Does nothing, for coverage
    underTest.start();
  }

  @AfterEach
  public void cleanup() {
    System.out.println("CLEANUP");
    underTest.stop();
    assertThat(underTest.isOmnisharpStarted()).isFalse();
  }

  @Test
  void dontWriteRequestIfServerStopped() {
    underTest.writeRequestOnStdIn("foo");
    assertThat(logTester.logs(LoggerLevel.DEBUG)).contains("Server stopped, ignoring request");
  }

  @Test
  void stopDoesNothingIfNotStarted() {
    underTest.stop();

    verify(endpoints).setServer(underTest);
    verifyNoMoreInteractions(endpoints);
  }

  @Test
  void processTerminatesBeforeReachingStartState() throws Exception {
    mockOmnisharpRun("echo Foo", "@echo off\necho Foo");

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    verify(endpoints).setServer(underTest);
    assertThat(processedOutput).containsExactly("Foo");
    verifyNoMoreInteractions(endpoints);

    assertThat(logTester.logs(LoggerLevel.ERROR)).contains("Unable to start the Omnisharp server: Process terminated unexpectedly");

    assertThat(underTest.isOmnisharpStarted()).isFalse();
    assertThat(underTest.whenReady()).isNotCompleted();
  }

  @Test
  void startStopManyTimes() throws Exception {
    mockOmnisharpRun("echo STARTED\nread -p \"Press enter to continue\"", "echo STARTED\npause");
    pressKeyWhenEndpointCallStopServer();

    assertThat(underTest.isOmnisharpStarted()).isFalse();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.stop();

    assertThat(underTest.isOmnisharpStarted()).isFalse();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.stop();

    assertThat(underTest.isOmnisharpStarted()).isFalse();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.stop();
  }

  @Test
  void multipleCallToStartOnlyStartsOnce() throws Exception {
    mockOmnisharpRun("echo STARTED\nread -p \"Press enter to continue\"", "echo STARTED\npause");
    pressKeyWhenEndpointCallStopServer();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    assertThat(underTest.whenReady()).isNotCompleted();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    underTest.lazyStart(solutionDir, false, null, null, null, null);
    underTest.lazyStart(solutionDir, false, null, null, null, null);
    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(processedOutput).containsExactly("STARTED");
  }

  @Test
  void automaticallyRestartIfDifferentSolutionDir() throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(anotherSolutionDir, false, null, null, null, null),
      "Using a different project basedir, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentDotnetCliPath(@TempDir Path dotnetCliPath) throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(solutionDir, false, dotnetCliPath, null, null, null),
      "Using a different dotnet CLI path, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentMonoPath(@TempDir Path monoPath) throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(solutionDir, false, null, monoPath, null, null),
      "Using a different Mono location, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentMSBuildPath(@TempDir Path msBuildPath) throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(solutionDir, false, null, null, msBuildPath, null),
      "Using a different MSBuild path, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentSolutionPath(@TempDir Path solutionPath) throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(solutionDir, false, null, null, null, solutionPath),
      "Using a different solution path, OmniSharp has to be restarted");
  }

  private void automaticallyRestartIfDifferentConfig(ThrowingRunnable first, ThrowingRunnable second, String expectedMsg) throws Exception {
    mockOmnisharpRun("echo STARTED\nread -p \"Press enter to continue\"", "@echo off\necho STARTED\npause");
    pressKeyWhenEndpointCallStopServer();

    System.out.println("First run");
    first.run();
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(endpoints, never()).stopServer();

    System.out.println("Second run");
    second.run();
    verify(endpoints).stopServer();
    assertThat(processedOutput).containsExactly("STARTED", "STARTED");
    assertThat(logTester.logs(LoggerLevel.INFO)).contains(expectedMsg);

    // Same parameters, should not restart
    clearInvocations(endpoints);
    System.out.println("Third run");
    second.run();
    verifyNoInteractions(endpoints);
    assertThat(processedOutput).containsExactly("STARTED", "STARTED");
  }

  @Test
  void stopCallStopServer() throws Exception {
    mockOmnisharpRun("echo STARTED\nread -p \"Press enter to continue\"", "echo \"STARTED\"\npause");
    pressKeyWhenEndpointCallStopServer();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.stop();

    verify(endpoints).stopServer();

    assertThat(underTest.isOmnisharpStarted()).isFalse();
  }

  private void pressKeyWhenEndpointCallStopServer() {
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();
  }

  @Test
  void stopDontCallStopServerIfProcessDeadAlready() throws Exception {
    mockOmnisharpRun("echo STARTED\nread -p \"Press enter to continue\"", "echo STARTED\npause");

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    // Write something on stdin to resume program
    underTest.writeRequestOnStdIn("");

    // give time for process to die
    Thread.sleep(1000);

    underTest.stop();

    verify(endpoints, never()).stopServer();
  }

  @Test
  void timeoutIfServerTakeTooLongToStart() throws Exception {
    mockOmnisharpRun("read -p \"Press enter to continue\"", "pause");

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(solutionDir, false, null, null, null, null));
    assertThat(thrown).hasMessage("Timeout waiting for Omnisharp server to start");
    assertThat(underTest.isOmnisharpStarted()).isFalse();
  }

  private void mockOmnisharpRun(String bashScript, String batScript) throws IOException {
    if (System2.INSTANCE.isOsWindows()) {
      Path run = omnisharpDir.resolve("run.bat");
      writeBat(batScript, run);
      when(commandBuilder.buildNet6(any(), any(), any(), any())).thenReturn(new ProcessBuilder("cmd", "/c", run.toString()));
      when(commandBuilder.build(any(), any(), any(), any())).thenReturn(new ProcessBuilder("cmd", "/c", run.toString()));
    } else {
      Path run = omnisharpDir.resolve("run");
      writeBash(bashScript, run);
      when(commandBuilder.buildNet6(any(), any(), any(), any())).thenReturn(new ProcessBuilder(run.toString()));
      when(commandBuilder.build(any(), any(), any(), any())).thenReturn(new ProcessBuilder(run.toString()));
    }
  }

  private void writeBash(String bashScript, Path scriptPath) throws IOException {
    Set<PosixFilePermission> ownerWritable = PosixFilePermissions.fromString("rwxr-xr-x");
    FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(ownerWritable);
    Files.createFile(scriptPath, permissions);
    Files.write(scriptPath, ("#!/bin/bash \n" + bashScript).getBytes(StandardCharsets.UTF_8));
  }

  private void writeBat(String batScript, Path scriptPath) throws IOException {
    Files.write(scriptPath, ("@echo off\n" + batScript).getBytes(StandardCharsets.UTF_8));
  }

  private class FakeOmnisharpResponseProcessor extends OmnisharpResponseProcessor {

    @Override
    public void handleOmnisharpOutput(CompletableFuture<Void> startFuture, CompletableFuture<Void> loadProjectsFuture, String line) {
      processedOutput.add(line);
      switch (line) {
        case "STARTED":
          startFuture.complete(null);
          break;
        case "LOADED":
          loadProjectsFuture.complete(null);
          break;
      }
    }
  }

}
