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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    mockOmnisharpRun("echo Foo");

    var thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(solutionDir, false, null, null, null, null));

    verify(endpoints).setServer(underTest);
    assertThat(processedOutput).containsExactly("Foo");
    verifyNoMoreInteractions(endpoints);

    assertThat(thrown).hasMessage("Unable to start the Omnisharp server: Process terminated unexpectedly");

    assertThat(underTest.isOmnisharpStarted()).isFalse();
    assertThat(underTest.whenReady()).isNotCompleted();
  }

  @Test
  void startStopManyTimes() throws Exception {
    mockOmnisharpRun(emulateStartEvent() + emulateProjectLoaded() + waitForKeyPress());
    pressKeyWhenEndpointCallStopServer();

    assertThat(underTest.isOmnisharpStarted()).isFalse();
    for (int i = 0; i < 10; i++) {
      underTest.lazyStart(solutionDir, false, null, null, null, null);
      assertThat(underTest.isOmnisharpStarted()).isTrue();
      underTest.whenReady().get();

      underTest.stop();
      assertThat(underTest.isOmnisharpStarted()).isFalse();
      assertThat(underTest.whenReady()).isCompletedExceptionally();
    }
  }

  @Test
  void multipleCallToStartOnlyStartsOnce() throws Exception {
    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());
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

  @Test
  void automaticallyRestartIfDifferentOmnisharpFlavorPath(@TempDir Path solutionPath) throws Exception {
    automaticallyRestartIfDifferentConfig(
      () -> underTest.lazyStart(solutionDir, false, null, null, null, null),
      () -> underTest.lazyStart(solutionDir, true, null, null, null, null),
      "Using a different flavor of OmniSharp, OmniSharp has to be restarted");
  }

  private void automaticallyRestartIfDifferentConfig(ThrowingRunnable first, ThrowingRunnable second, String expectedMsg) throws Exception {
    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());
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
    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());
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
    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());

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
    mockOmnisharpRun(waitForKeyPress());

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(solutionDir, false, null, null, null, null));
    assertThat(thrown).hasMessage("Timeout waiting for Omnisharp server to start");
    assertThat(underTest.isOmnisharpStarted()).isFalse();
    assertThat(underTest.whenReady()).isCompletedExceptionally();
  }

  @Test
  void waitForProjectLoaded() throws Exception {
    mockOmnisharpRun(emulateStartEvent() + emulateProjectLoaded() + waitForKeyPress());
    pressKeyWhenEndpointCallStopServer();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.whenReady().get();
    assertThat(underTest.whenReady()).isCompleted();
  }

  @Test
  void timeoutIfProjectsTakeTooLongToLoad() throws Exception {
    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());

    pressKeyWhenEndpointCallStopServer();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    var thrown = assertThrows(ExecutionException.class, () -> underTest.whenReady().get());
    assertThat(thrown).hasCauseExactlyInstanceOf(TimeoutException.class);

    assertThat(underTest.isOmnisharpStarted()).isTrue();
    assertThat(underTest.whenReady()).isCompletedExceptionally();
  }

  @Test
  void waitingForProjectToLoadDoesntPreventStopping() throws Exception {
    underTest = new OmnisharpServerController(endpoints, new FakeOmnisharpResponseProcessor(), commandBuilder, Duration.of(1, ChronoUnit.SECONDS),
      Duration.of(9999, ChronoUnit.SECONDS));

    mockOmnisharpRun(emulateStartEvent() + waitForKeyPress());
    pressKeyWhenEndpointCallStopServer();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    // This thread will block forever, waiting for solution to load
    WaitForReady t = new WaitForReady();
    t.start();

    // Give time for thread to be blocked on the future
    Thread.sleep(100);
    assertThat(t.isAlive()).isTrue();

    underTest.stop();

    assertThat(underTest.isOmnisharpStarted()).isFalse();
    assertThat(underTest.whenReady()).isCompletedExceptionally();

    t.join();
    assertThat(t.thrown).isInstanceOf(CancellationException.class);
  }

  private class WaitForReady extends Thread {
    private Exception thrown = null;

    @Override
    public void run() {
      try {
        underTest.whenReady().get();
      } catch (Exception e) {
        thrown = e;
      }
    }
  }

  @Test
  void startFailed() throws Exception {
    when(commandBuilder.build(any(), any(), any(), any())).thenReturn(new ProcessBuilder("not existing command"));

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(solutionDir, false, null, null, null, null));
    assertThat(thrown).hasMessageContainingAll("Unable to start the Omnisharp server", "not existing command");
    assertThat(underTest.isOmnisharpStarted()).isFalse();

    assertThat(underTest.whenReady()).isCompletedExceptionally();
  }

  private void mockOmnisharpRun(String script) throws IOException {
    if (System2.INSTANCE.isOsWindows()) {
      Path run = omnisharpDir.resolve("run.bat");
      writeBat("@echo off\n" + script, run);
      when(commandBuilder.buildNet6(any(), any(), any(), any())).thenReturn(new ProcessBuilder("cmd", "/c", run.toString()));
      when(commandBuilder.build(any(), any(), any(), any())).thenReturn(new ProcessBuilder("cmd", "/c", run.toString()));
    } else {
      Path run = omnisharpDir.resolve("run");
      writeBash(script, run);
      when(commandBuilder.buildNet6(any(), any(), any(), any())).thenReturn(new ProcessBuilder(run.toString()));
      when(commandBuilder.build(any(), any(), any(), any())).thenReturn(new ProcessBuilder(run.toString()));
    }
  }

  private String emulateStartEvent() {
    return "echo " + FakeOmnisharpResponseProcessor.STARTED_EVENT + "\n";
  }

  private String emulateProjectLoaded() {
    return "echo " + FakeOmnisharpResponseProcessor.LOADED_EVENT + "\n";
  }

  private String waitForKeyPress() {
    if (System2.INSTANCE.isOsWindows()) {
      // Prevent pause to write "Press any key to continue..." to stdout
      return "pause >nul";
    } else {
      return "read -p \"Press enter to continue\"\n";
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

    private static final String STARTED_EVENT = "STARTED";
    private static final String LOADED_EVENT = "LOADED";

    @Override
    public void handleOmnisharpOutput(CompletableFuture<Void> startFuture, CompletableFuture<Void> loadProjectsFuture, String line) {
      processedOutput.add(line);
      switch (line) {
        case STARTED_EVENT:
          startFuture.complete(null);
          break;
        case LOADED_EVENT:
          loadProjectsFuture.complete(null);
          break;
      }
    }
  }

}
