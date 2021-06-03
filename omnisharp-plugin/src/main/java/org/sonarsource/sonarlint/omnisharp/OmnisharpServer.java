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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
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
  private long requestId = 1;
  private final JSONParser parser = new JSONParser();
  private final Map<Long, JSONObject> responseQueue = new ConcurrentHashMap<>();
  private volatile boolean omnisharpStarted;
  private final ConcurrentLinkedQueue<String> requestQueue = new ConcurrentLinkedQueue<>();

  private RequestQueuePumper requestQueuePumper;

  private PipedOutputStream output;

  private final System2 system2;

  private Path roslynAnalyzerDir;

  private final TempFolder tempFolder;

  private final Optional<String> omnisharpLocOpt;

  public OmnisharpServer(System2 system2, TempFolder tempFolder, Configuration config) {
    this.system2 = system2;
    this.tempFolder = tempFolder;
    this.omnisharpLocOpt = config.get(CSharpPropertyDefinitions.getOmnisharpLocation());
  }

  public synchronized void lazyStart(Path projectBaseDir) throws InvalidExitValueException, IOException, InterruptedException {
    if (omnisharpStarted) {
      return;
    }
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateProjectLatch = new CountDownLatch(1);
    output = new PipedOutputStream();
    PipedInputStream input = new PipedInputStream(output);
    String omnisharpLoc = omnisharpLocOpt.orElseThrow(() -> new IllegalStateException("Property '" + CSharpPropertyDefinitions.getOmnisharpLocation() + "' is required"));
    ProcessExecutor processExecutor = buildOmnisharpCommand(projectBaseDir, omnisharpLoc);
    processExecutor.redirectOutput(new LogOutputStream() {
      @Override
      protected void processLine(String line) {
        JSONObject jsonObject;
        try {
          jsonObject = (JSONObject) parser.parse(line);
        } catch (Exception e) {
          LOG.error("Unable to parse message as Json", e);
          return;
        }
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
    ProcessExecutor processExecutor = new ProcessExecutor().directory(Paths.get(omnisharpLoc).toFile());
    if (system2.isOsWindows()) {
      processExecutor.command("OmniSharp.exe", "-v", "-s",
        projectBaseDir.toString(), "RoslynExtensionsOptions:EnableAnalyzersSupport=true",
        "RoslynExtensionsOptions:LocationPaths=" + roslynAnalyzerDir.toString());
    } else {
      processExecutor.command("sh", "run", "-v", "-s",
        projectBaseDir.toString(), "RoslynExtensionsOptions:EnableAnalyzersSupport=true",
        "RoslynExtensionsOptions:LocationPaths=" + roslynAnalyzerDir.toString());
    }
    return processExecutor;
  }

  private void handleJsonMessage(CountDownLatch startLatch, CountDownLatch firstUpdateProjectLatch, String line, JSONObject jsonObject) {
    String type = (String) jsonObject.get("Type");
    switch (type) {
      case "response":
        long reqSeq = (long) jsonObject.get("Request_seq");
        responseQueue.put(reqSeq, jsonObject);
        break;
      case "event":
        String eventType = (String) jsonObject.get("Event");
        switch (eventType) {
          case "log":
            handleLog((JSONObject) jsonObject.get("Body"));
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

  private void handleLog(JSONObject jsonObject) {
    String level = (String) jsonObject.get("LogLevel");
    String message = (String) jsonObject.get("Message");
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
    if (output != null) {
      try {
        output.close();
      } catch (IOException e) {
        LOG.error("Unable to close", e);
      }
    }
    if (process != null) {
      process.getProcess().destroyForcibly();
      process = null;
    }
    omnisharpStarted = false;
  }

  public void codeCheck(String filename, Consumer<OmnisharpDiagnostic> issueHandler) {
    JSONObject args = new JSONObject();
    args.put("FileName", filename);
    JSONObject resp = makeSyncRequest("/codecheck", args);
    handle(resp, issueHandler);
  }

  public void updateBuffer(String filename, String buffer) {
    JSONObject args = new JSONObject();
    args.put("FileName", filename);
    args.put("Buffer", buffer);
    makeSyncRequest("/updatebuffer", args);
  }

  private void handle(JSONObject response, Consumer<OmnisharpDiagnostic> issueHandler) {
    JSONObject body = (JSONObject) response.get("Body");
    JSONArray issues = (JSONArray) body.get("QuickFixes");
    issues.forEach(i -> {
      JSONObject issue = (JSONObject) i;
      String ruleId = (String) issue.get("Id");
      if (!ruleId.startsWith("S")) {
        // Ignore non SonarCS issues
        return;
      }
      OmnisharpDiagnostic diag = new OmnisharpDiagnostic();
      diag.id = ruleId;
      diag.line = (int) (long) issue.get("Line");
      diag.column = (int) (long) issue.get("Column");
      diag.endLine = (int) (long) issue.get("EndLine");
      diag.endColumn = (int) (long) issue.get("EndColumn");
      diag.text = (String) issue.get("Text");
      issueHandler.accept(diag);
    });
  }

  private JSONObject makeSyncRequest(String command, @Nullable JSONObject dataJson) {
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
