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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.sonar.api.Startable;
import org.sonar.api.config.Configuration;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.ZipUtils;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.LogOutputStream;

@ScannerSide
@SonarLintSide(lifespan = SonarLintSide.MULTIPLE_ANALYSES)
public class OmnisharpServer implements Startable {

  private static final Logger LOG = Loggers.get(OmnisharpServer.class);

  private static final String ROSLYN_ANALYZER_LOCATION = "sonarAnalyzer";

  private StartedProcess process;
  private volatile boolean omnisharpStarted;

  private PipedOutputStream output;

  private final System2 system2;

  private Path roslynAnalyzerDir;

  private final TempFolder tempFolder;

  private final Optional<String> omnisharpLocOpt;

  private Path cachedProjectBaseDir;

  private final OmnisharpProtocol omnisharpProtocol;

  public OmnisharpServer(System2 system2, TempFolder tempFolder, Configuration config, OmnisharpProtocol omnisharpProtocol) {
    this.system2 = system2;
    this.tempFolder = tempFolder;
    this.omnisharpProtocol = omnisharpProtocol;
    this.omnisharpLocOpt = config.get(CSharpPropertyDefinitions.getOmnisharpLocation());
  }

  public void lazyStart(Path projectBaseDir) throws InvalidExitValueException, IOException, InterruptedException {
    if (omnisharpStarted) {
      if (!cachedProjectBaseDir.equals(projectBaseDir)) {
        LOG.info("Using a different project basedir, OmniSharp have to be restarted");
        stop();
      } else {
        return;
      }
    }
    doStart(projectBaseDir);
  }

  private synchronized void doStart(Path projectBaseDir) throws IOException, InterruptedException {
    this.cachedProjectBaseDir = projectBaseDir;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateProjectLatch = new CountDownLatch(1);
    output = new PipedOutputStream();
    PipedInputStream input = new PipedInputStream(output);
    String omnisharpLoc = omnisharpLocOpt.orElseThrow(() -> new IllegalStateException("Property '" + CSharpPropertyDefinitions.getOmnisharpLocation() + "' is required"));
    ProcessExecutor processExecutor = buildOmnisharpCommand(projectBaseDir, omnisharpLoc);
    processExecutor.redirectOutput(omnisharpProtocol.buildOutputStreamHandler(startLatch, firstUpdateProjectLatch))
      .redirectError(new LogOutputStream() {

        @Override
        protected void processLine(String line) {
          LOG.error(line);
        }
      })
      .redirectInput(input)
      .destroyOnExit();

    LOG.info("Starting OmniSharp...");
    process = processExecutor.start();
    if (!startLatch.await(1, TimeUnit.MINUTES)) {
      process.getProcess().destroyForcibly();
      throw new IllegalStateException("Timeout waiting for Omnisharp server to start");
    }
    LOG.info("OmniSharp successfully started");

    LOG.info("Waiting for solution/project configuration to be loaded...");
    if (!firstUpdateProjectLatch.await(1, TimeUnit.MINUTES)) {
      process.getProcess().destroyForcibly();
      throw new IllegalStateException("Timeout waiting for solution/project configuration to be loaded");
    }
    LOG.info("Solution/project configuration loaded");

    omnisharpStarted = true;

    omnisharpProtocol.startRequestQueuePumper(output);
  }

  private ProcessExecutor buildOmnisharpCommand(Path projectBaseDir, String omnisharpLoc) {
    Path omnisharpPath = Paths.get(omnisharpLoc);
    List<String> args = new ArrayList<>();
    if (system2.isOsWindows()) {
      args.add(omnisharpPath.resolve("OmniSharp.exe").toString());
    } else {
      args.add("sh");
      args.add("run");
    }
    args.add("-v");
    args.add("-s");
    args.add(projectBaseDir.toString());
    args.add("RoslynExtensionsOptions:EnableAnalyzersSupport=true");
    args.add("RoslynExtensionsOptions:LocationPaths:0=" + roslynAnalyzerDir.toString());
    return new ProcessExecutor()
      .directory(omnisharpPath.toFile())
      .command(args);
  }

  @Override
  public void start() {
    String analyzerVersion = loadAnalyzerVersion();
    this.roslynAnalyzerDir = tempFolder.newDir(ROSLYN_ANALYZER_LOCATION).toPath();
    unzipAnalyzer(analyzerVersion);
  }

  private void unzipAnalyzer(String analyzerVersion) {
    InputStream bundle = getClass().getResourceAsStream("/static/SonarAnalyzer-" + analyzerVersion + ".zip");
    if (bundle == null) {
      throw new IllegalStateException("SonarAnalyzer not found in plugin jar");
    }
    try {
      ZipUtils.unzip(bundle, roslynAnalyzerDir.toFile());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract analyzers");
    }
  }

  private String loadAnalyzerVersion() {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/analyzer-version.txt"), StandardCharsets.UTF_8))) {
      return reader.lines().findFirst().orElseThrow(() -> new IllegalStateException("Unable to read analyzer version"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public synchronized void stop() {
    if (omnisharpStarted) {
      LOG.info("Stopping OmniSharp");
      omnisharpProtocol.stopServer();
      omnisharpProtocol.stopRequestQueuePumper();
    }
    closeOutputStream();
    waitForProcessToEnd();
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

  private void closeOutputStream() {
    if (output != null) {
      try {
        output.close();
      } catch (IOException e) {
        LOG.error("Unable to close", e);
      }
    }
  }

}
