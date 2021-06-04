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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.sonar.api.Startable;
import org.sonar.api.config.Configuration;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.ZipUtils;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.analyzer.commons.internal.json.simple.JSONObject;
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
  private long requestId = 1;
  private final Map<Long, JsonObject> responseQueue = new ConcurrentHashMap<>();
  private volatile boolean omnisharpStarted;
  private final ConcurrentLinkedQueue<String> requestQueue = new ConcurrentLinkedQueue<>();

  private RequestQueuePumper requestQueuePumper;

  private PipedOutputStream output;

  private final System2 system2;

  private Path roslynAnalyzerDir;

  private final TempFolder tempFolder;

  private final Optional<String> omnisharpLocOpt;

  private Path cachedProjectBaseDir;

  public OmnisharpServer(System2 system2, TempFolder tempFolder, Configuration config) {
    this.system2 = system2;
    this.tempFolder = tempFolder;
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
    processExecutor.redirectOutput(new LogOutputStream() {
      @Override
      protected void processLine(String line) {
        JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
        handleJsonMessage(startLatch, firstUpdateProjectLatch, line, jsonObject);
      }

    })
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
      throw new IllegalStateException("Timeout waiting for Omnisharp server to start");
    }
    LOG.info("OmniSharp successfully started");
    LOG.info("Waiting for solution/project configuration to be loaded...");
    if (!firstUpdateProjectLatch.await(1, TimeUnit.MINUTES)) {
      throw new IllegalStateException("Timeout waiting for solution/project configuration to be loaded");
    }
    LOG.info("Solution/project configuration loaded");
    omnisharpStarted = true;

    requestQueuePumper = new RequestQueuePumper(requestQueue, output);
    new Thread(requestQueuePumper).start();
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

  private void handleJsonMessage(CountDownLatch startLatch, CountDownLatch firstUpdateProjectLatch, String line, JsonObject jsonObject) {
    String type = jsonObject.get("Type").getAsString();
    switch (type) {
      case "response":
        long reqSeq = jsonObject.get("Request_seq").getAsLong();
        responseQueue.put(reqSeq, jsonObject);
        break;
      case "event":
        String eventType = jsonObject.get("Event").getAsString();
        switch (eventType) {
          case "log":
            handleLog(jsonObject.get("Body").getAsJsonObject());
            break;
          case "started":
            startLatch.countDown();
            break;
          case "ProjectAdded":
          case "ProjectChanged":
          case "ProjectRemoved":
            firstUpdateProjectLatch.countDown();
            break;
          default:
            LOG.debug(line);
        }
        break;
      default:
        LOG.debug(line);
    }
  }

  private void handleLog(JsonObject jsonObject) {
    String level = jsonObject.get("LogLevel").getAsString();
    String message = jsonObject.get("Message").getAsString();
    LOG.debug("Omnisharp: [" + level + "] " + message);
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
      makeSyncRequest("/stopserver", null);
      if (requestQueuePumper != null) {
        requestQueuePumper.stopProcessing();
        requestQueuePumper = null;
      }
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

  public void codeCheck(String filename, Consumer<OmnisharpDiagnostic> issueHandler) {
    JSONObject args = new JSONObject();
    args.put("FileName", filename);
    JsonObject resp = makeSyncRequest("/codecheck", args);
    handle(resp, issueHandler);
  }

  public void updateBuffer(String filename, String buffer) {
    JSONObject args = new JSONObject();
    args.put("FileName", filename);
    args.put("Buffer", buffer);
    makeSyncRequest("/updatebuffer", args);
  }

  private void handle(JsonObject response, Consumer<OmnisharpDiagnostic> issueHandler) {
    JsonObject body = response.get("Body").getAsJsonObject();
    JsonArray issues = body.get("QuickFixes").getAsJsonArray();
    issues.forEach(i -> {
      JsonObject issue = i.getAsJsonObject();
      String ruleId = issue.get("Id").getAsString();
      if (!ruleId.startsWith("S")) {
        // Ignore non SonarCS issues
        return;
      }
      OmnisharpDiagnostic diag = new OmnisharpDiagnostic();
      diag.id = ruleId;
      diag.line = issue.get("Line").getAsInt();
      diag.column = issue.get("Column").getAsInt();
      diag.endLine = issue.get("EndLine").getAsInt();
      diag.endColumn = issue.get("EndColumn").getAsInt();
      diag.text = issue.get("Text").getAsString();
      issueHandler.accept(diag);
    });
  }

  private JsonObject makeSyncRequest(String command, @Nullable JSONObject dataJson) {
    long id = requestId++;
    JSONObject args = new JSONObject();
    args.put("Type", "request");
    args.put("Seq", id);
    args.put("Command", command);
    args.put("Arguments", dataJson);
    String requestJson = args.toJSONString();
    LOG.debug("Request: " + requestJson);
    requestQueue.add(requestJson);
    long t = System.currentTimeMillis();
    while (System.currentTimeMillis() < t + 10_000) {
      if (responseQueue.containsKey(id)) {
        return responseQueue.remove(id);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    throw new IllegalStateException("timeout");
  }

}
