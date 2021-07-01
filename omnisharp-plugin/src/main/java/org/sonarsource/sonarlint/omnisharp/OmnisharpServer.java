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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.Startable;
import org.sonar.api.config.Configuration;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.plugin.api.SonarLintRuntime;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.listener.ProcessListener;
import org.zeroturnaround.exec.stream.LogOutputStream;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpServer implements Startable {

  private static final Logger LOG = Loggers.get(OmnisharpServer.class);

  private boolean omnisharpStarted;

  private OutputStream output;

  private final System2 system2;

  private final OmnisharpServicesExtractor servicesExtractor;

  private Path cachedProjectBaseDir;
  private Path cachedDotnetCliPath;

  private final OmnisharpProtocol omnisharpProtocol;

  private final Path pathHelperLocationOnMac;

  private final Configuration config;

  private final String omnisharpExeWin;

  private final SonarLintRuntime sonarLintRuntime;

  private PipedInputStream pipeInput;

  private StartedProcess process;

  public OmnisharpServer(System2 system2, OmnisharpServicesExtractor servicesExtractor, Configuration config, OmnisharpProtocol omnisharpProtocol,
    SonarLintRuntime sonarLintRuntime) {
    this(system2, servicesExtractor, config, omnisharpProtocol, Paths.get("/usr/libexec/path_helper"), "OmniSharp.exe", sonarLintRuntime);
  }

  // For testing
  OmnisharpServer(System2 system2, OmnisharpServicesExtractor servicesExtractor, Configuration config, OmnisharpProtocol omnisharpProtocol, Path pathHelperLocationOnMac,
    String omnisharpExeWin,
    SonarLintRuntime sonarLintRuntime) {
    this.system2 = system2;
    this.servicesExtractor = servicesExtractor;
    this.config = config;
    this.omnisharpProtocol = omnisharpProtocol;
    this.pathHelperLocationOnMac = pathHelperLocationOnMac;
    this.omnisharpExeWin = omnisharpExeWin;
    this.sonarLintRuntime = sonarLintRuntime;
  }

  public void lazyStart(Path projectBaseDir, @Nullable Path dotnetCliPath) throws IOException, InterruptedException {
    if (omnisharpStarted) {
      if (!Objects.equals(cachedProjectBaseDir, projectBaseDir) || !Objects.equals(cachedDotnetCliPath, dotnetCliPath)) {
        LOG.info("Using a different project basedir or dotnet CLI path, OmniSharp have to be restarted");
        stop();
      } else {
        return;
      }
    }
    doStart(projectBaseDir, dotnetCliPath);
  }

  private synchronized void doStart(Path projectBaseDir, @Nullable Path dotnetCliPath) throws IOException, InterruptedException {
    this.cachedProjectBaseDir = projectBaseDir;
    this.cachedDotnetCliPath = dotnetCliPath;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateProjectLatch = new CountDownLatch(1);
    String omnisharpLoc = config.get(CSharpPropertyDefinitions.getOmnisharpLocation())
      .orElseThrow(() -> new IllegalStateException("Property '" + CSharpPropertyDefinitions.getOmnisharpLocation() + "' is required"));
    ProcessExecutor processExecutor = buildOmnisharpCommand(projectBaseDir, omnisharpLoc);
    processExecutor.addListener(new ProcessListener() {
      @Override
      public void afterStop(Process process) {
        // Release startLatch if the process stops immediately
        startLatch.countDown();

        omnisharpStarted = false;
      }
    });
    pipeInput = new PipedInputStream();
    output = new PipedOutputStream(pipeInput);

    processExecutor.redirectOutput(omnisharpProtocol.buildOutputStreamHandler(startLatch, firstUpdateProjectLatch))
      .redirectError(new LogOutputStream() {

        @Override
        protected void processLine(String line) {
          LOG.error(line);
        }
      })
      .redirectInput(pipeInput)
      .environment("PATH", buildPathEnv(dotnetCliPath))
      .destroyOnExit();

    LOG.info("Starting OmniSharp...");
    process = processExecutor.start();
    if (!startLatch.await(1, TimeUnit.MINUTES)) {
      process.getProcess().destroyForcibly();
      throw new IllegalStateException("Timeout waiting for Omnisharp server to start");
    }
    if (!process.getProcess().isAlive()) {
      LOG.error("Process terminated unexpectedly");
      return;
    }
    LOG.info("OmniSharp successfully started");

    LOG.info("Waiting for solution/project configuration to be loaded...");
    if (!firstUpdateProjectLatch.await(1, TimeUnit.MINUTES)) {
      process.getProcess().destroyForcibly();
      throw new IllegalStateException("Timeout waiting for solution/project configuration to be loaded");
    }
    LOG.info("Solution/project configuration loaded");

    omnisharpStarted = true;

    omnisharpProtocol.setOmnisharpProcessStdIn(output);
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
      String pathHelperOutput = runSimpleCommand(pathHelperLocationOnMac.toString(), "-s");
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
  private static String runSimpleCommand(String... command) {
    try {
      return new ProcessExecutor().command(command)
        .readOutput(true)
        .exitValueNormal()
        .timeout(10, TimeUnit.SECONDS)
        .execute()
        .outputUTF8();
    } catch (InvalidExitValueException | IOException | TimeoutException e) {
      LOG.debug("Unable to execute command", e);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    }
  }

  private ProcessExecutor buildOmnisharpCommand(Path projectBaseDir, String omnisharpLoc) {
    Path omnisharpPath = Paths.get(omnisharpLoc);
    List<String> args = new ArrayList<>();
    if (system2.isOsWindows()) {
      args.add(omnisharpPath.resolve(omnisharpExeWin).toString());
    } else {
      args.add("bash");
      args.add("run");
    }
    args.add("-v");
    if (sonarLintRuntime.getClientPid() != 0) {
      args.add("-hpid");
      args.add(Long.toString(sonarLintRuntime.getClientPid()));
    }
    args.add("-s");
    args.add(projectBaseDir.toString());
    args.add("-pl");
    args.add(servicesExtractor.getOmnisharpServicesDllPath().toString());
    return new ProcessExecutor()
      .directory(omnisharpPath.toFile())
      .command(args);
  }

  @Override
  public void start() {
    // Nothing to do
  }

  @Override
  public synchronized void stop() {
    if (omnisharpStarted) {
      LOG.info("Stopping OmniSharp");
      omnisharpProtocol.stopServer();
      omnisharpProtocol.setOmnisharpProcessStdIn(null);
    }
    closeStreams();
    // Don't wait for process to end on Linux, because it takes too long
    // On Windows, it is important to wait to avoid locks on some dlls
    if (system2.isOsWindows()) {
      waitForProcessToEnd();
    }
    omnisharpStarted = false;
  }

  private void waitForProcessToEnd() {
    if (process != null) {
      try {
        process.getFuture().get(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOG.error("Error while executing OmniSharp", e);
      } catch (TimeoutException e) {
        LOG.warn("Unable to terminate OmniSharp, killing it");
        process.getProcess().destroyForcibly();
      }

      process = null;
    }
  }

  private void closeStreams() {
    closeQuitely(output);
    output = null;
    closeQuitely(pipeInput);
    pipeInput = null;
  }

  private static void closeQuitely(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        LOG.error("Unable to close", e);
      }
    }
  }

}
