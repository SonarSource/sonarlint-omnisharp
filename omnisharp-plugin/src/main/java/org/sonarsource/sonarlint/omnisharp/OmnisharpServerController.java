/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource Sàrl
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
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.sonar.api.Startable;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;

import static java.util.stream.Collectors.joining;

@ScannerSide
@SonarLintSide(lifespan = SonarLintSide.MODULE)
public class OmnisharpServerController implements Startable {

  private static final Logger LOG = Loggers.get(OmnisharpServerController.class);

  enum ServerState {
    PROCESS_STARTED,
    OMNISHARP_STARTED,
    STOPPING,
    STOPPED
  }

  private static class ServerStateMachine {
    private volatile ServerState state = ServerState.STOPPED;
    private ProcessWrapper processWrapper;
    private CompletableFuture<Integer> terminationFuture = CompletableFuture.completedFuture(0);
    private CompletableFuture<Void> startFuture = failedNotStarted();
    private CompletableFuture<Void> loadProjectsFuture = failedNotStarted();

    private static CompletableFuture<Void> failedNotStarted() {
      return CompletableFuture.failedFuture(new IllegalStateException("OmniSharp not started"));
    }

    private static CompletableFuture<Void> failedToStart(Exception e) {
      return CompletableFuture.failedFuture(e);
    }

    public boolean isStopped() {
      return state == ServerState.STOPPED;
    }

    public boolean isOmnisharpStarted() {
      return state == ServerState.OMNISHARP_STARTED;
    }

    public synchronized void processStarted(ProcessWrapper processWrapper, CompletableFuture<Void> startFuture, CompletableFuture<Void> loadProjectsFuture,
      boolean loadProjectsOnDemand) {
      this.processWrapper = processWrapper;
      this.terminationFuture = processWrapper.getTerminationFuture().whenComplete((r, t) -> {
        LOG.info("Omnisharp process terminated");
        this.stopped();
      });
      this.state = ServerState.PROCESS_STARTED;
      this.startFuture = startFuture
        .whenComplete((r, t) -> {
          if (t != null) {
            loadProjectsFuture.completeExceptionally(t);
            processWrapper.destroyForcibly();
          } else {
            this.state = ServerState.OMNISHARP_STARTED;
            LOG.info("OmniSharp successfully started");
          }
        });
      if (loadProjectsOnDemand) {
        this.loadProjectsFuture = this.startFuture;
      } else {
        this.loadProjectsFuture = loadProjectsFuture
          .thenRun(() -> LOG.info("Projects successfully loaded"));
      }
    }

    public synchronized void processStartFailed(IOException e) {
      startFuture = failedToStart(e);
      loadProjectsFuture = failedToStart(e);
      this.state = ServerState.STOPPED;
    }

    public synchronized void stopped() {
      boolean stoppedNormally = this.state == ServerState.STOPPING;
      this.state = ServerState.STOPPED;
      if (stoppedNormally) {
        startFuture.cancel(true);
        loadProjectsFuture.cancel(true);
      } else {
        startFuture.completeExceptionally(new IllegalStateException("Process terminated unexpectedly"));
        loadProjectsFuture.completeExceptionally(new IllegalStateException("Process terminated unexpectedly"));
      }
      startFuture = failedNotStarted();
      loadProjectsFuture = failedNotStarted();
    }

    public void stopping() {
      this.state = ServerState.STOPPING;
    }

    public void waitForStop() throws InterruptedException, ExecutionException {
      this.processWrapper.waitForProcessToEndOrKill(1, TimeUnit.SECONDS);
      terminationFuture.get();
    }
  }

  private final ServerStateMachine stateMachine = new ServerStateMachine();

  private Path cachedAnalyzerJarPath;
  private Path cachedProjectBaseDir;
  private Path cachedDotnetCliPath;
  private Path cachedMonoPath;
  private Path cachedMsBuildPath;
  private Path cachedSolutionPath;
  private boolean cachedUseNet6;
  private boolean cachedLoadProjectsOnDemand;

  private final OmnisharpEndpoints omnisharpEndpoints;

  private final OmnisharpResponseProcessor omnisharpResponseProcessor;

  private final OmnisharpCommandBuilder omnisharpCommandBuilder;

  public OmnisharpServerController(OmnisharpEndpoints omnisharpEndpoints, OmnisharpResponseProcessor omnisharpResponseProcessor, OmnisharpCommandBuilder omnisharpCommandBuilder) {
    this.omnisharpEndpoints = omnisharpEndpoints;
    this.omnisharpResponseProcessor = omnisharpResponseProcessor;
    this.omnisharpCommandBuilder = omnisharpCommandBuilder;
    omnisharpEndpoints.setServer(this);
  }

  public synchronized void lazyStart(Path projectBaseDir, Path analyzerJarPath, boolean useNet6, boolean loadProjectsOnDemand, @Nullable Path dotnetCliPath,
    @Nullable Path monoPath,
    @Nullable Path msBuildPath,
    @Nullable Path solutionPath, int serverStartupTimeoutSec, int loadProjectsTimeoutSec)
    throws InterruptedException {
    AtomicBoolean shouldRestart = new AtomicBoolean(false);
    this.cachedProjectBaseDir = checkIfRestartRequired(cachedProjectBaseDir, projectBaseDir, "project basedir", shouldRestart);
    this.cachedAnalyzerJarPath = checkIfRestartRequired(cachedAnalyzerJarPath, analyzerJarPath, "analyzer JAR path", shouldRestart);
    this.cachedDotnetCliPath = checkIfRestartRequired(cachedDotnetCliPath, dotnetCliPath, "dotnet CLI path", shouldRestart);
    this.cachedMonoPath = checkIfRestartRequired(cachedMonoPath, monoPath, "Mono location", shouldRestart);
    this.cachedMsBuildPath = checkIfRestartRequired(cachedMsBuildPath, msBuildPath, "MSBuild path", shouldRestart);
    this.cachedSolutionPath = checkIfRestartRequired(cachedSolutionPath, solutionPath, "solution path", shouldRestart);
    this.cachedUseNet6 = checkIfRestartRequired(cachedUseNet6, useNet6, "flavor of OmniSharp", shouldRestart);
    this.cachedLoadProjectsOnDemand = checkIfRestartRequired(cachedLoadProjectsOnDemand, loadProjectsOnDemand, "load projects on demand setting", shouldRestart);
    if (shouldRestart.get()) {
      stopServer();
    }
    if (stateMachine.isStopped()) {
      startServer(serverStartupTimeoutSec, loadProjectsTimeoutSec);
    }
    try {
      stateMachine.startFuture.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof TimeoutException) {
        throw new IllegalStateException("Timeout waiting for Omnisharp server to start");
      }
      throw new IllegalStateException("Unable to start the Omnisharp server: " + e.getCause().getMessage(), e.getCause());
    }

  }

  public CompletableFuture<Void> whenReady() {
    return stateMachine.loadProjectsFuture;
  }

  private <G> G checkIfRestartRequired(@Nullable G oldValue, @Nullable G newValue, String label, AtomicBoolean shouldRestart) {
    if (stateMachine.isOmnisharpStarted() && !Objects.equals(oldValue, newValue)) {
      shouldRestart.set(true);
      LOG.info("Using a different {}, OmniSharp has to be restarted", label);
    }
    return newValue;
  }

  public boolean isOmnisharpStarted() {
    return stateMachine.isOmnisharpStarted();
  }

  private void startServer(int serverStartupTimeoutSec, int loadProjectsTimeoutSec) {
    var startFuture = new CompletableFuture<Void>()
      .orTimeout(serverStartupTimeoutSec, TimeUnit.SECONDS);
    var loadProjectsFuture = new CompletableFuture<Void>()
      .orTimeout(loadProjectsTimeoutSec, TimeUnit.SECONDS);
    ProcessBuilder processBuilder;
    if (cachedUseNet6) {
      processBuilder = omnisharpCommandBuilder.buildNet6(cachedProjectBaseDir, cachedDotnetCliPath, cachedMsBuildPath, cachedSolutionPath, cachedLoadProjectsOnDemand);
    } else {
      processBuilder = omnisharpCommandBuilder.build(cachedProjectBaseDir, cachedMonoPath, cachedMsBuildPath, cachedSolutionPath, cachedLoadProjectsOnDemand);
    }

    LOG.info("Starting OmniSharp...");
    LOG.debug(processBuilder.command().stream().collect(joining(" ")));
    try {
      var startedProcess = ProcessWrapper.start(processBuilder,
        s -> omnisharpResponseProcessor.handleOmnisharpOutput(startFuture, loadProjectsFuture, s), LOG::error);
      stateMachine.processStarted(startedProcess, startFuture, loadProjectsFuture, cachedLoadProjectsOnDemand);
    } catch (IOException e) {
      LOG.warn("Unable to start OmniSharp", e);
      stateMachine.processStartFailed(e);
    }
  }

  @Override
  public void start() {
    // Nothing to do
  }

  @Override
  public void stop() {
    stopServer();
  }

  public synchronized void stopServer() {
    if (!stateMachine.isStopped()) {
      stateMachine.stopping();
      LOG.info("Stopping OmniSharp");
      omnisharpEndpoints.stopServer();
      try {
        stateMachine.waitForStop();
        LOG.info("OmniSharp stopped");
      } catch (InterruptedException e) {
        LOG.debug("Interrupted!", e);
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOG.error("Could not stop Omnisharp properly", e);
        throw new IllegalStateException("Could not stop Omnisharp properly", e.getCause());
      }
    }
  }

  public synchronized boolean writeRequestOnStdIn(String str) {
    if (stateMachine.isStopped()) {
      LOG.debug("Server stopped, ignoring request");
      return false;
    }
    try {
      stateMachine.processWrapper.writeLnStdIn(str);
      return true;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to write in Omnisharp stdin", e);
    }
  }

}
