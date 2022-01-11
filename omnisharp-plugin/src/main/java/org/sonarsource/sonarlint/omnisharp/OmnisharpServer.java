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
/*
 * SonarC#
 * Copyright (C) 2014-2021 SonarSource SA
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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.Startable;
import org.sonar.api.config.Configuration;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.command.CommandException;
import org.sonar.api.utils.command.CommandExecutor;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpServer implements Startable {

  private static final Duration SERVER_STARTUP_MAX_WAIT = Duration.of(1, ChronoUnit.MINUTES);

  private static final Logger LOG = Loggers.get(OmnisharpServer.class);

  private boolean omnisharpStarted;

  private OutputStream output;

  private final System2 system2;

  private final OmnisharpServicesExtractor servicesExtractor;

  private Path cachedProjectBaseDir;
  private Path cachedDotnetCliPath;
  private Path cachedMonoPath;

  private final OmnisharpEndpoints omnisharpEndpoints;

  private final Path pathHelperLocationOnMac;

  private final Configuration config;

  private final String omnisharpExeWin;

  private final SonarLintRuntime sonarLintRuntime;

  private Process startedProcess;

  private final OmnisharpResponseProcessor omnisharpResponseProcessor;

  private StreamConsumer streamConsumer;

  private final Clock clock;

  private final Duration serverStartupMaxWait;

  public OmnisharpServer(System2 system2, OmnisharpServicesExtractor servicesExtractor, Configuration config, OmnisharpEndpoints omnisharpEndpoints,
    OmnisharpResponseProcessor omnisharpResponseProcessor, SonarLintRuntime sonarLintRuntime) {
    this(system2, Clock.systemDefaultZone(), servicesExtractor, config, omnisharpEndpoints, Paths.get("/usr/libexec/path_helper"), "OmniSharp.exe", omnisharpResponseProcessor,
      sonarLintRuntime, SERVER_STARTUP_MAX_WAIT);
  }

  // For testing
  OmnisharpServer(System2 system2, Clock clock, OmnisharpServicesExtractor servicesExtractor, Configuration config, OmnisharpEndpoints omnisharpEndpoints,
    Path pathHelperLocationOnMac,
    String omnisharpExeWin, OmnisharpResponseProcessor omnisharpResponseProcessor, SonarLintRuntime sonarLintRuntime, Duration serverStartupMaxWait) {
    this.system2 = system2;
    this.clock = clock;
    this.servicesExtractor = servicesExtractor;
    this.config = config;
    this.omnisharpEndpoints = omnisharpEndpoints;
    this.omnisharpResponseProcessor = omnisharpResponseProcessor;
    this.serverStartupMaxWait = serverStartupMaxWait;
    omnisharpEndpoints.setServer(this);
    this.pathHelperLocationOnMac = pathHelperLocationOnMac;
    this.omnisharpExeWin = omnisharpExeWin;
    this.sonarLintRuntime = sonarLintRuntime;
  }

  public synchronized void lazyStart(Path projectBaseDir, @Nullable Path dotnetCliPath, @Nullable Path monoPath) throws IOException, InterruptedException {
    checkAlive();
    if (omnisharpStarted) {
      if (!Objects.equals(cachedProjectBaseDir, projectBaseDir) || !Objects.equals(cachedDotnetCliPath, dotnetCliPath) || !Objects.equals(cachedMonoPath, monoPath)) {
        LOG.info("Using a different project basedir, dotnet CLI path or Mono location, OmniSharp has to be restarted");
        stop();
      } else {
        return;
      }
    }
    doStart(projectBaseDir, dotnetCliPath, monoPath);
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

  private void doStart(Path projectBaseDir, @Nullable Path dotnetCliPath, @Nullable Path monoPath) throws IOException, InterruptedException {
    this.cachedProjectBaseDir = projectBaseDir;
    this.cachedDotnetCliPath = dotnetCliPath;
    this.cachedMonoPath = monoPath;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateProjectLatch = new CountDownLatch(1);
    String omnisharpLoc = config.get(CSharpPropertyDefinitions.getOmnisharpLocation())
      .orElseThrow(() -> new IllegalStateException("Property '" + CSharpPropertyDefinitions.getOmnisharpLocation() + "' is required"));
    ProcessBuilder processBuilder = buildOmnisharpCommand(projectBaseDir, omnisharpLoc, monoPath);
    processBuilder.environment().put("PATH", buildPathEnv(dotnetCliPath));

    LOG.info("Starting OmniSharp...");
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

  private String buildPathEnv(@Nullable Path dotnetCliPath) {
    StringBuilder sb = new StringBuilder();
    if (dotnetCliPath != null) {
      sb.append(dotnetCliPath.getParent().toString());
      sb.append(File.pathSeparatorChar);
    }
    sb.append(getPathEnv());
    return sb.toString();
  }

  private String getPathEnv() {
    if (system2.isOsMac() && Files.exists(pathHelperLocationOnMac)) {
      String pathHelperOutput = runSimpleCommand(Command.create(pathHelperLocationOnMac.toString()).addArgument("-s"));
      if (pathHelperOutput != null) {
        Pattern regex = Pattern.compile(".*PATH=\"([^\"]*)\"; export PATH;.*");
        Matcher matchResult = regex.matcher(pathHelperOutput);
        if (matchResult.matches()) {
          return matchResult.group(1);
        }
      }
    }
    return system2.envVariable("PATH");
  }

  /**
   * Run a simple command that should return a single line on stdout
   */
  @CheckForNull
  static String runSimpleCommand(Command command) {
    List<String> output = new ArrayList<>();
    try {
      int result = CommandExecutor.create().execute(command, output::add, LOG::error, 10_000);
      if (result != 0) {
        LOG.debug("Command returned with error: " + command);
        output.forEach(LOG::debug);
        return null;
      }
      return output.isEmpty() ? null : output.get(0);
    } catch (CommandException e) {
      LOG.debug("Unable to execute command: " + command, e);
      return null;
    }
  }

  private ProcessBuilder buildOmnisharpCommand(Path projectBaseDir, String omnisharpLoc, @Nullable Path monoPath) {
    Path omnisharpPath = Paths.get(omnisharpLoc);
    List<String> args = new ArrayList<>();
    if (system2.isOsWindows()) {
      args.add(omnisharpPath.resolve(omnisharpExeWin).toString());
    } else if (monoPath != null) {
      args.add(monoPath.toString());
      args.add("omnisharp/OmniSharp.exe");
    } else {
      args.add("bash");
      args.add("run");
    }
    args.add("-v");
    if (sonarLintRuntime.getClientPid() != 0) {
      args.add("--hostPID");
      args.add(Long.toString(sonarLintRuntime.getClientPid()));
    }
    args.add("DotNet:enablePackageRestore=false");
    args.add("--encoding");
    args.add("utf-8");
    args.add("-s");
    args.add(projectBaseDir.toString());
    args.add("--plugin");
    args.add(servicesExtractor.getOmnisharpServicesDllPath().toString());
    return new ProcessBuilder(args).directory(omnisharpPath.toFile());
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
