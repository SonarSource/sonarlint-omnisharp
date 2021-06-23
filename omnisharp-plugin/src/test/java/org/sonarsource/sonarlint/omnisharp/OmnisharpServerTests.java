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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.impl.utils.DefaultTempFolder;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OmnisharpServerTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpServer underTest;
  private Path slTmpDir;
  private Path omnisharpDir;
  private Path solutionDir;
  private OmnisharpProtocol protocol;
  private MapSettings mapSettings;

  @BeforeEach
  void prepare(@TempDir Path tmpDir) throws IOException {
    slTmpDir = tmpDir.resolve("tmp");
    omnisharpDir = tmpDir.resolve("omnisharp");
    Files.createDirectory(omnisharpDir);
    solutionDir = tmpDir.resolve("solution");
    protocol = mock(OmnisharpProtocol.class);
    mapSettings = new MapSettings();
    SonarLintRuntime runtime = mock(SonarLintRuntime.class);
    when(runtime.getClientPid()).thenReturn(123L);
    underTest = new OmnisharpServer(System2.INSTANCE, new DefaultTempFolder(slTmpDir.toFile()), mapSettings.asConfig(), protocol, Paths.get("/usr/libexec/path_helper"), "run.bat",
      runtime);
  }

  @Test
  void extractAnalyzersOnStartup() {
    underTest.start();
    assertThat(slTmpDir.resolve("sonarAnalyzer"))
      .isDirectoryContaining("glob:**/SonarAnalyzer.dll")
      .isDirectoryContaining("glob:**/SonarAnalyzer.CSharp.dll")
      .isDirectoryContaining("glob:**/SonarAnalyzer.CFG.dll")
      .isDirectoryContaining("glob:**/Google.Protobuf.dll");
  }

  @Test
  void stopDoesNothingIfNotStarted() {
    underTest.stop();
    verifyNoInteractions(protocol);
  }

  @Test
  void omnisharpLocationRequired() throws Exception {
    underTest.start();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> underTest.lazyStart(slTmpDir, null));
    assertThat(thrown).hasMessage("Property 'sonar.cs.internal.omnisharpLocation' is required");

  }

  @Test
  void processTerminatesBeforeReachingStartState() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("echo Foo", "echo \"Foo\"");

    underTest.start();

    underTest.lazyStart(solutionDir, null);

    verify(protocol).buildOutputStreamHandler(any(), any());
    verifyNoMoreInteractions(protocol);

    assertThat(logTester.logs(LoggerLevel.ERROR)).contains("Process terminated unexpectedly");
  }

  @Test
  void stopCallStopServer() throws Exception {
    mapSettings.setProperty("sonar.cs.internal.omnisharpLocation", omnisharpDir.toString());

    mockOmnisharpRun("read -p \"Press enter to continue\"", "pause");

    underTest.start();

    when(protocol.buildOutputStreamHandler(any(), any())).thenAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        CountDownLatch startLatch = invocation.getArgument(0, CountDownLatch.class);
        startLatch.countDown();
        CountDownLatch projectConfigLatch = invocation.getArgument(1, CountDownLatch.class);
        projectConfigLatch.countDown();
        return null;
      }
    });

    underTest.lazyStart(solutionDir, null);

    underTest.stop();

    verify(protocol).stopServer();
    verify(protocol).stopRequestQueuePumper();
  }

  private void mockOmnisharpRun(String bashScript, String batScript) throws IOException {
    if (System2.INSTANCE.isOsWindows()) {
      Path run = omnisharpDir.resolve("run.bat");
      Files.write(run, batScript.getBytes(StandardCharsets.UTF_8));
    } else {
      Path run = omnisharpDir.resolve("run");
      Set<PosixFilePermission> ownerWritable = PosixFilePermissions.fromString("rwxr-xr-x");
      FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(ownerWritable);
      Files.createFile(run, permissions);
      Files.write(run, ("#!/bin/bash \n" + bashScript).getBytes(StandardCharsets.UTF_8));
    }
  }

}
