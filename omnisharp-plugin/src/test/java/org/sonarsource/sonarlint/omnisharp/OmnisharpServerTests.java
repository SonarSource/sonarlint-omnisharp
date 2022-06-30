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
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class OmnisharpServerTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  @TempDir
  public Path omnisharpDir;

  private OmnisharpServer underTest;
  private Path solutionDir;
  private Path anotherSolutionDir;
  private OmnisharpEndpoints endpoints;
  private OmnisharpResponseProcessor responseProcessor;
  private OmnisharpCommandBuilder commandBuilder;

  @BeforeEach
  void prepare(@TempDir Path tmpDir) throws IOException {
    solutionDir = tmpDir.resolve("solution");
    anotherSolutionDir = tmpDir.resolve("anotherSolution");
    endpoints = mock(OmnisharpEndpoints.class);
    responseProcessor = mock(OmnisharpResponseProcessor.class);
    commandBuilder = mock(OmnisharpCommandBuilder.class);
    underTest = new OmnisharpServer(System2.INSTANCE, Clock.systemUTC(), endpoints, responseProcessor, commandBuilder, Duration.of(1, ChronoUnit.SECONDS));
  }

  @AfterEach
  public void cleanup() {
    underTest.stop();
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

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    verify(endpoints).setServer(underTest);
    verify(responseProcessor).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verifyNoMoreInteractions(endpoints);

    assertThat(logTester.logs(LoggerLevel.ERROR)).contains("Process terminated unexpectedly");
  }

  @Test
  void multipleCallToStartOnlyStartsOnce() throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo Foo\npause");

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        CountDownLatch startLatch = invocation.getArgument(0, CountDownLatch.class);
        startLatch.countDown();
        CountDownLatch projectConfigLatch = invocation.getArgument(1, CountDownLatch.class);
        projectConfigLatch.countDown();
        return null;
      }
    }).when(responseProcessor).handleOmnisharpOutput(any(), any(), anyString());

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
  }

  @Test
  void automaticallyRestartIfDifferentSolutionDir() throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verify(endpoints, never()).stopServer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    underTest.lazyStart(anotherSolutionDir, false, null, null, null, null);
    verify(endpoints).stopServer();
    verify(responseProcessor, times(2)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different project basedir, OmniSharp has to be restarted");

    // Same solution, should not restart
    clearInvocations(endpoints);
    underTest.lazyStart(anotherSolutionDir, false, null, null, null, null);
    verifyNoInteractions(endpoints);
  }

  @Test
  void automaticallyRestartIfDifferentDotnetCliPath(@TempDir Path dotnetCliPath) throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verify(endpoints, never()).stopServer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    underTest.lazyStart(solutionDir, false, dotnetCliPath, null, null, null);
    verify(endpoints).stopServer();
    verify(responseProcessor, times(2)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different dotnet CLI path, OmniSharp has to be restarted");

    // Same dotnetCliPath, should not restart
    clearInvocations(endpoints);
    underTest.lazyStart(solutionDir, false, dotnetCliPath, null, null, null);
    verifyNoInteractions(endpoints);
  }

  @Test
  void automaticallyRestartIfDifferentMonoPath(@TempDir Path monoPath) throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");
    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(commandBuilder).build(solutionDir, null, null, null);
    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verify(endpoints, never()).stopServer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    clearInvocations(responseProcessor, commandBuilder);
    mockResponseProcessor();

    underTest.lazyStart(solutionDir, false, null, monoPath, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(endpoints).stopServer();
    verify(commandBuilder).build(solutionDir, monoPath, null, null);

    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different Mono location, OmniSharp has to be restarted");
    logTester.clear();

    // Same monoPath, should not restart
    clearInvocations(endpoints, commandBuilder);
    underTest.lazyStart(solutionDir, false, null, monoPath, null, null);
    verify(endpoints, never()).stopServer();
    verifyNoInteractions(commandBuilder);
  }

  @Test
  void automaticallyRestartIfDifferentMSBuildPath(@TempDir Path msBuildPath) throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");
    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verify(endpoints, never()).stopServer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    clearInvocations(responseProcessor);

    underTest.lazyStart(solutionDir, false, null, null, msBuildPath, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(endpoints).stopServer();

    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different MSBuild path, OmniSharp has to be restarted");
    logTester.clear();

    // Same msBuildPath, should not restart
    clearInvocations(endpoints);
    underTest.lazyStart(solutionDir, false, null, null, msBuildPath, null);
    verify(endpoints, never()).stopServer();
  }

  @Test
  void automaticallyRestartIfDifferentSolutionPath(@TempDir Path solutionPath) throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");
    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verify(endpoints, never()).stopServer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    clearInvocations(responseProcessor);

    underTest.lazyStart(solutionDir, false, null, null, null, solutionPath);
    assertThat(underTest.isOmnisharpStarted()).isTrue();
    verify(endpoints).stopServer();

    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different solution path, OmniSharp has to be restarted");
    logTester.clear();

    // Same solutionPath, should not restart
    clearInvocations(endpoints);
    underTest.lazyStart(solutionDir, false, null, null, null, solutionPath);
    verify(endpoints, never()).stopServer();
  }

  @Test
  void stopCallStopServer() throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo \"Foo\"\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        // Write something on stdin to resume program
        underTest.writeRequestOnStdIn("");
        return null;
      }
    }).when(endpoints).stopServer();

    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.stop();

    verify(endpoints).stopServer();
  }

  @Test
  void stopDontCallStopServerIfProcessDeadAlready() throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    // Write something on stdin to resume program
    underTest.writeRequestOnStdIn("");

    // give time for process to die
    Thread.sleep(1000);

    underTest.stop();

    verify(endpoints, never()).stopServer();
  }

  @Test
  void testWaitForProcessToEnd() throws Exception {
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, false, null, null, null, null);

    Thread waitForEnd = new Thread() {
      @Override
      public void run() {
        underTest.waitForProcessToEnd();
      }
    };
    waitForEnd.start();
    // Wait a bit to ensure the thread is waiting for process to end
    Thread.sleep(1000);
    assertThat(waitForEnd.isAlive()).isTrue();

    // Write something on stdin to resume program
    underTest.writeRequestOnStdIn("");
    // give time for process to die
    Thread.sleep(1000);

    waitForEnd.join(1000);
    assertThat(waitForEnd.isAlive()).isFalse();
    // The call should return immediately
    underTest.waitForProcessToEnd();
  }

  @Test
  void timeoutIfServerTakeTooLongToStart() throws Exception {
    mockOmnisharpRun("read -p \"Press enter to continue\"", "pause");

    underTest.start();

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

  private List<String> mockResponseProcessor() {
    List<String> stdOut = new ArrayList<>();
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        CountDownLatch startLatch = invocation.getArgument(0, CountDownLatch.class);
        startLatch.countDown();
        CountDownLatch projectConfigLatch = invocation.getArgument(1, CountDownLatch.class);
        projectConfigLatch.countDown();
        stdOut.add(invocation.getArgument(2, String.class));
        return null;
      }
    }).when(responseProcessor).handleOmnisharpOutput(any(), any(), anyString());
    return stdOut;
  }

}
