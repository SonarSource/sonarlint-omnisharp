/*
 * SonarOmnisharp
 * Copyright (C) 2021-2021 SonarSource SA
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.apache.commons.lang.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class OmnisharpServerTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpServer underTest;
  private Path omnisharpDir;
  private Path solutionDir;
  private Path anotherSolutionDir;
  private Path dotnetCliPath;
  private Path monoPath;
  private OmnisharpEndpoints endpoints;
  private MapSettings mapSettings;
  private OmnisharpResponseProcessor responseProcessor;

  @BeforeEach
  void prepare(@TempDir Path tmpDir) throws IOException {
    omnisharpDir = tmpDir.resolve("omnisharp");
    Files.createDirectory(omnisharpDir);
    solutionDir = tmpDir.resolve("solution");
    anotherSolutionDir = tmpDir.resolve("anotherSolution");
    if (SystemUtils.IS_OS_WINDOWS) {
      dotnetCliPath = tmpDir.resolve("dotnetCli/dotnet.exe");
      monoPath = tmpDir.resolve("mono.bat");
    } else {
      dotnetCliPath = tmpDir.resolve("dotnetCli/dotnet");
      monoPath = tmpDir.resolve("mono.sh");
    }
    mockMono();
    endpoints = mock(OmnisharpEndpoints.class);
    mapSettings = new MapSettings();
    SonarLintRuntime runtime = mock(SonarLintRuntime.class);
    when(runtime.getClientPid()).thenReturn(123L);
    OmnisharpServicesExtractor servicesExtractor = mock(OmnisharpServicesExtractor.class);
    when(servicesExtractor.getOmnisharpServicesDllPath()).thenReturn(tmpDir.resolve("fake/services.dll"));
    responseProcessor = mock(OmnisharpResponseProcessor.class);
    underTest = new OmnisharpServer(System2.INSTANCE, Clock.systemUTC(), servicesExtractor, mapSettings.asConfig(), endpoints, Paths.get("/usr/libexec/path_helper"), "run.bat",
      responseProcessor, runtime, Duration.of(1, ChronoUnit.SECONDS));
  }

  @AfterEach
  public void cleanup() {
    underTest.stop();
  }

  @Test
  void testSimpleCommandNoOutput() {
    if (SystemUtils.IS_OS_WINDOWS) {
      assertThat(OmnisharpServer.runSimpleCommand(Command.create("cmd").addArgument("/c").addArgument("echo>NUL"))).isNull();
    } else {
      assertThat(OmnisharpServer.runSimpleCommand(Command.create("bash").addArgument("-c").addArgument("echo>/dev/null"))).isNull();
    }
    assertThat(logTester.logs(LoggerLevel.DEBUG)).isEmpty();
  }

  @Test
  void testSimpleCommand() {
    assertThat(OmnisharpServer.runSimpleCommand(Command.create("echo").addArgument("Hello World!"))).isEqualTo("Hello World!");
  }

  @Test
  void testSimpleCommandError() {
    assertThat(OmnisharpServer.runSimpleCommand(Command.create("doesnt_exists"))).isNull();
    assertThat(logTester.logs(LoggerLevel.DEBUG)).contains("Unable to execute command: doesnt_exists");
  }

  @Test
  void testSimpleCommandNonZeroExitCode() {
    if (SystemUtils.IS_OS_WINDOWS) {
      assertThat(OmnisharpServer.runSimpleCommand(Command.create("cmd").addArgument("/c").addArgument("exit 1"))).isNull();
      assertThat(logTester.logs(LoggerLevel.DEBUG)).contains("Command returned with error: cmd /c exit 1");
    } else {
      assertThat(OmnisharpServer.runSimpleCommand(Command.create("bash").addArgument("-c").addArgument("exit 1"))).isNull();
      assertThat(logTester.logs(LoggerLevel.DEBUG)).contains("Command returned with error: bash -c exit 1");
    }
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
  void omnisharpLocationRequired() throws Exception {
    underTest.start();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(null, null, null));
    assertThat(thrown).hasMessage("Property 'sonar.cs.internal.omnisharpLocation' is required");

  }

  @Test
  void processTerminatesBeforeReachingStartState() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo", "@echo off\necho Foo");

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);

    verify(endpoints).setServer(underTest);
    verify(responseProcessor).handleOmnisharpOutput(any(), any(), eq("Foo"));
    verifyNoMoreInteractions(endpoints);

    assertThat(logTester.logs(LoggerLevel.ERROR)).contains("Process terminated unexpectedly");
  }

  @Test
  void multipleCallToStartOnlyStartsOnce() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

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

    underTest.lazyStart(solutionDir, null, null);
    assertThat(underTest.isOmnisharpStarted()).isTrue();

    underTest.lazyStart(solutionDir, null, null);

    verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
  }

  @Test
  void automaticallyRestartIfDifferentSolutionDir() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);
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

    underTest.lazyStart(anotherSolutionDir, null, null);
    verify(endpoints).stopServer();
    verify(responseProcessor, times(2)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different project basedir, dotnet CLI path or Mono location, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentDotnetCliPath() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);
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

    underTest.lazyStart(solutionDir, dotnetCliPath, null);
    verify(endpoints).stopServer();
    verify(responseProcessor, times(2)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different project basedir, dotnet CLI path or Mono location, OmniSharp has to be restarted");
  }

  @Test
  void automaticallyRestartIfDifferentMonoPath() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "@echo off\necho Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);
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

    underTest.lazyStart(solutionDir, null, monoPath);
    verify(endpoints).stopServer();
    if (SystemUtils.IS_OS_WINDOWS) {
      // On Windows we don't use the Mono path to run Omnisharp
      verify(responseProcessor, times(2)).handleOmnisharpOutput(any(), any(), eq("Foo"));
    } else {
      verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Foo"));
      verify(responseProcessor, times(1)).handleOmnisharpOutput(any(), any(), eq("Argument=omnisharp/OmniSharp.exe"));
    }
    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Using a different project basedir, dotnet CLI path or Mono location, OmniSharp has to be restarted");
  }

  @Test
  void shouldPassDotnetCliInPathEnv() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo \"PATH=$PATH\"\nread -p \"Press enter to continue\"", "@echo off\necho PATH=%PATH%\npause");

    List<String> stdOut = mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, dotnetCliPath, null);

    // Wait for process to run until echo
    Thread.sleep(1000);

    assertThat(stdOut).hasSize(1);
    assertThat(stdOut.get(0)).startsWith("PATH=" + dotnetCliPath.getParent().toString() + File.pathSeparator);
  }

  @Test
  void stopCallStopServer() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo \"Foo\"\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);

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
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);

    // Write something on stdin to resume program
    underTest.writeRequestOnStdIn("");

    // give time for process to die
    Thread.sleep(1000);

    underTest.stop();

    verify(endpoints, never()).stopServer();
  }

  @Test
  void testWaitForProcessToEnd() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());
    mockOmnisharpRun("echo Foo\nread -p \"Press enter to continue\"", "echo Foo\npause");

    mockResponseProcessor();

    underTest.start();

    underTest.lazyStart(solutionDir, null, null);

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
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("read -p \"Press enter to continue\"", "pause");

    underTest.start();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(solutionDir, null, null));
    assertThat(thrown).hasMessage("Timeout waiting for Omnisharp server to start");
    assertThat(underTest.isOmnisharpStarted()).isFalse();
  }

  private void mockOmnisharpRun(String bashScript, String batScript) throws IOException {
    if (System2.INSTANCE.isOsWindows()) {
      Path run = omnisharpDir.resolve("run.bat");
      Files.write(run, ("@echo off\n" + batScript).getBytes(StandardCharsets.UTF_8));
    } else {
      Path run = omnisharpDir.resolve("run");
      Set<PosixFilePermission> ownerWritable = PosixFilePermissions.fromString("rwxr-xr-x");
      FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(ownerWritable);
      Files.createFile(run, permissions);
      Files.write(run, ("#!/bin/bash \n" + bashScript).getBytes(StandardCharsets.UTF_8));
    }
  }

  private void mockMono() throws IOException {
    if (System2.INSTANCE.isOsWindows()) {
      Files.write(monoPath, ("echo Argument=%1").getBytes(StandardCharsets.UTF_8));
    } else {
      Set<PosixFilePermission> ownerWritable = PosixFilePermissions.fromString("rwxr-xr-x");
      FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(ownerWritable);
      Files.createFile(monoPath, permissions);
      Files.write(monoPath, ("#!/bin/bash \n echo \"Argument=$1\"").getBytes(StandardCharsets.UTF_8));
    }
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
