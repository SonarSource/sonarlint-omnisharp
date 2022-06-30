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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.api.Startable;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpServer implements Startable {

  private static final Duration SERVER_STARTUP_MAX_WAIT = Duration.of(1, ChronoUnit.MINUTES);

  private static final Logger LOG = Loggers.get(OmnisharpServer.class);

  private boolean omnisharpStarted;

  private OutputStream output;

  private final System2 system2;

  private Path cachedProjectBaseDir;
  private Path cachedDotnetCliPath;
  private Path cachedMonoPath;
  private Path cachedMsBuildPath;
  private Path cachedSolutionPath;
  private boolean cachedUseNet6;

  private final OmnisharpEndpoints omnisharpEndpoints;

  private Process startedProcess;

  private final OmnisharpResponseProcessor omnisharpResponseProcessor;

  private StreamConsumer streamConsumer;

  private final Clock clock;

  private final Duration serverStartupMaxWait;

  private final OmnisharpCommandBuilder omnisharpCommandBuilder;

  public OmnisharpServer(System2 system2, OmnisharpEndpoints omnisharpEndpoints,
    OmnisharpResponseProcessor omnisharpResponseProcessor, OmnisharpCommandBuilder omnisharpCommandBuilder) {
    this(system2, Clock.systemDefaultZone(), omnisharpEndpoints, omnisharpResponseProcessor,
      omnisharpCommandBuilder, SERVER_STARTUP_MAX_WAIT);
  }

  // For testing
  OmnisharpServer(System2 system2, Clock clock, OmnisharpEndpoints omnisharpEndpoints,
    OmnisharpResponseProcessor omnisharpResponseProcessor, OmnisharpCommandBuilder omnisharpCommandBuilder, Duration serverStartupMaxWait) {
    this.system2 = system2;
    this.clock = clock;
    this.omnisharpEndpoints = omnisharpEndpoints;
    this.omnisharpResponseProcessor = omnisharpResponseProcessor;
    this.omnisharpCommandBuilder = omnisharpCommandBuilder;
    this.serverStartupMaxWait = serverStartupMaxWait;
    omnisharpEndpoints.setServer(this);
  }

  public synchronized void lazyStart(Path projectBaseDir, boolean useNet6, @Nullable Path dotnetCliPath, @Nullable Path monoPath, @Nullable Path msBuildPath,
    @Nullable Path solutionPath)
    throws IOException, InterruptedException {
    checkAlive();
    AtomicBoolean shouldRestart = new AtomicBoolean(false);
    this.cachedProjectBaseDir = checkIfRestartRequired(cachedProjectBaseDir, projectBaseDir, "project basedir", shouldRestart);
    this.cachedDotnetCliPath = checkIfRestartRequired(cachedDotnetCliPath, dotnetCliPath, "dotnet CLI path", shouldRestart);
    this.cachedMonoPath = checkIfRestartRequired(cachedMonoPath, monoPath, "Mono location", shouldRestart);
    this.cachedMsBuildPath = checkIfRestartRequired(cachedMsBuildPath, msBuildPath, "MSBuild path", shouldRestart);
    this.cachedSolutionPath = checkIfRestartRequired(cachedSolutionPath, solutionPath, "solution path", shouldRestart);
    this.cachedUseNet6 = checkIfRestartRequired(cachedUseNet6, useNet6, "flavor of OmniSharp", shouldRestart);
    if (shouldRestart.get()) {
      stop();
    }
    if (!omnisharpStarted) {
      doStart();
    }
  }

  private <G> G checkIfRestartRequired(G oldValue, G newValue, String label, AtomicBoolean shouldRestart) {
    if (omnisharpStarted && !Objects.equals(oldValue, newValue)) {
      shouldRestart.set(true);
      LOG.info("Using a different {}, OmniSharp has to be restarted", label);
    }
    return newValue;
  }

  public boolean isOmnisharpStarted() {
    return omnisharpStarted && startedProcess.isAlive();
  }

  private synchronized void checkAlive() {
    if (omnisharpStarted && startedProcess != null && !startedProcess.isAlive()) {
      LOG.error("Process terminated unexpectedly");
      cleanResources();
    }
  }

  private void doStart()
    throws IOException, InterruptedException {

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateProjectLatch = new CountDownLatch(1);
    ProcessBuilder processBuilder;
    if (cachedUseNet6) {
      processBuilder = omnisharpCommandBuilder.buildNet6(cachedProjectBaseDir, cachedDotnetCliPath, cachedMsBuildPath, cachedSolutionPath);
    } else {
      processBuilder = omnisharpCommandBuilder.build(cachedProjectBaseDir, cachedMonoPath, cachedMsBuildPath, cachedSolutionPath);
    }

    LOG.info("Starting OmniSharp...");
    LOG.debug(processBuilder.command().stream().collect(Collectors.joining(" ")));
    startedProcess = processBuilder.start();
    streamConsumer = new StreamConsumer();
    streamConsumer.consumeStream(startedProcess.getInputStream(), l -> omnisharpResponseProcessor.handleOmnisharpOutput(startLatch, firstUpdateProjectLatch, l));
    streamConsumer.consumeStream(startedProcess.getErrorStream(), LOG::error);
    output = startedProcess.getOutputStream();

    waitForLatchOrProcessDied(startLatch);
    if (!startedProcess.isAlive()) {
      LOG.error("Process terminated unexpectedly");
      cleanResources();
      return;
    }
    if (!startLatch.await(1, TimeUnit.SECONDS)) {
      startedProcess.destroyForcibly();
      cleanResources();
      throw new IllegalStateException("Timeout waiting for Omnisharp server to start");
    }
    LOG.info("OmniSharp successfully started");

    LOG.info("Waiting for solution/project configuration to be loaded...");
    waitForLatchOrProcessDied(firstUpdateProjectLatch);

    if (!firstUpdateProjectLatch.await(1, TimeUnit.MINUTES)) {
      startedProcess.destroyForcibly();
      throw new IllegalStateException("Timeout waiting for solution/project configuration to be loaded");
    }
    LOG.info("Solution/project configuration loaded");

    omnisharpStarted = true;
  }

  /**
   * Wait for the latch to be counted down, or the process to died, which one come first.
   */
  private void waitForLatchOrProcessDied(CountDownLatch latch) throws InterruptedException {
    Instant start = clock.instant();
    do {
      if (latch.await(1, TimeUnit.SECONDS)) {
        break;
      }
    } while (startedProcess.isAlive() && Duration.between(start, clock.instant()).compareTo(serverStartupMaxWait) < 0);
  }

  @Override
  public void start() {
    // Nothing to do
  }

  @Override
  public synchronized void stop() {
    checkAlive();
    if (omnisharpStarted) {
      LOG.info("Stopping OmniSharp");
      omnisharpEndpoints.stopServer();
    }
    // Don't wait for startedProcess to end on Linux, because it takes too long.
    // On Windows, it is important to wait to avoid locks on some dlls
    if (system2.isOsWindows()) {
      waitForProcessToEnd();
    }
    cleanResources();
  }

  private void cleanResources() {
    if (streamConsumer != null) {
      try {
        streamConsumer.await();
      } catch (InterruptedException e) {
        LOG.debug("Interrupted!", e);
        Thread.currentThread().interrupt();
      }
      streamConsumer = null;
    }
    startedProcess = null;
    omnisharpStarted = false;
  }

  // Visible for testing
  void waitForProcessToEnd() {
    if (startedProcess != null) {
      try {
        if (!startedProcess.waitFor(1, TimeUnit.MINUTES)) {
          LOG.warn("Unable to terminate OmniSharp, killing it");
          startedProcess.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      startedProcess = null;
    }
  }

  public synchronized boolean writeRequestOnStdIn(String str) {
    checkAlive();
    if (!omnisharpStarted) {
      LOG.debug("Server stopped, ignoring request");
      return false;
    }
    try {
      output.write(str.getBytes(StandardCharsets.UTF_8));
      output.write("\n".getBytes(StandardCharsets.UTF_8));
      output.flush();
      return true;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to write in Omnisharp stdin", e);
    }
  }

}
