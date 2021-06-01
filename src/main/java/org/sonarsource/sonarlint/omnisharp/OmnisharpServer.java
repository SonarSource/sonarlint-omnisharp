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

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.sonar.api.Startable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.rule.RuleKey;
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
  private boolean started;
  private final ConcurrentLinkedQueue<String> requestQueue = new ConcurrentLinkedQueue<>();

  private RequestQueuePumper requestQueuePumper;

  private PipedOutputStream output;

  private String cachedOmnisharpLoc;

  private final System2 system2;

  private Path roslynAnalyzerDir;

  private final TempFolder tempFolder;

  public OmnisharpServer(System2 system2, TempFolder tempFolder) {
    this.system2 = system2;
    this.tempFolder = tempFolder;
  }

  public void start(Path projectBaseDir) throws InvalidExitValueException, IOException, InterruptedException {
    output = new PipedOutputStream();
    PipedInputStream input = new PipedInputStream(output);
    AtomicBoolean projectChangedEvent = new AtomicBoolean();
    ProcessExecutor processExecutor = new ProcessExecutor()
      .directory(Paths.get(cachedOmnisharpLoc).toFile());
    if (system2.isOsWindows()) {
      processExecutor.command("OmniSharp.exe", "-v", "-s",
        projectBaseDir.toString(), "RoslynExtensionsOptions:EnableAnalyzersSupport=true",
        "RoslynExtensionsOptions:LocationPaths=" + roslynAnalyzerDir.toString());
    } else {
      processExecutor.command("sh", "run", "-v", "-s",
        projectBaseDir.toString(), "RoslynExtensionsOptions:EnableAnalyzersSupport=true",
        "RoslynExtensionsOptions:LocationPaths=" + roslynAnalyzerDir.toString());
    }
    processExecutor.redirectOutput(new LogOutputStream() {
      @Override
      protected void processLine(String line) {
        LOG.debug(line);
        try {
          JSONObject jsonObject = (JSONObject) parser.parse(line);
          String type = (String) jsonObject.get("Type");
          switch (type) {
            case "response":
              long reqSeq = (long) jsonObject.get("Request_seq");
              responseQueue.put(reqSeq, jsonObject);
              break;
            case "event":
              // HACK wait for ProjectChanged event to be sure Omnisharp is ready to receive codecheck requests
              if ("ProjectChanged".equals(jsonObject.get("Event"))) {
                projectChangedEvent.set(true);
              }
              break;
            default:
          }
        } catch (Exception e) {
          LOG.error(e.getMessage(), e);
        }
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
    process = processExecutor.start();
    while (!projectChangedEvent.get()) {
      Thread.sleep(100);
    }
    started = true;
    requestQueuePumper = new RequestQueuePumper(requestQueue, output);
    new Thread(requestQueuePumper).start();
  }

  @Override
  public void start() {
    this.roslynAnalyzerDir = tempFolder.newDir(ROSLYN_ANALYZER_LOCATION).toPath();
    InputStream bundle = getClass().getResourceAsStream("/static/SonarAnalyzer-8.23-0-32424.zip");
    if (bundle == null) {
      throw new IllegalStateException("eslint-bridge not found in plugin jar");
    }
    try {
      ZipUtils.unzip(bundle, roslynAnalyzerDir.toFile());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract analyzers");
    }
  }

  @Override
  public void stop() {
    makeSyncRequest("/stopserver", null);
    if (requestQueuePumper != null) {
      requestQueuePumper.stopProcessing();
      requestQueuePumper = null;
    }
    if (output != null) {
      try {
        output.close();
      } catch (IOException e) {
        LOG.error("Unable to close", e);
      }
    }
    process.getProcess().destroyForcibly();
    process = null;
    started = false;
  }

  public void analyze(SensorContext context) {
    String omnisharpLoc = context.config().get(CSharpPropertyDefinitions.getOmnisharpLocation())
      .orElseThrow(() -> new IllegalStateException("Property '" + CSharpPropertyDefinitions.getOmnisharpLocation() + "' is required"));
    if (started && !Objects.equals(cachedOmnisharpLoc, omnisharpLoc)) {
      stop();
    }
    this.cachedOmnisharpLoc = omnisharpLoc;
    if (!started) {
      try {
        start(context.fileSystem().baseDir().toPath());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    for (InputFile f : context.fileSystem().inputFiles(context.fileSystem().predicates().hasLanguage(CSharpPlugin.LANGUAGE_KEY))) {
      updateBuffer(f);
      codeCheck(context, f);
    }
  }

  private void codeCheck(SensorContext context, InputFile f) {
    JSONObject args = new JSONObject();
    args.put("FileName", f.absolutePath());
    JSONObject resp = makeSyncRequest("/codecheck", args);
    handle(context, f, resp);
  }

  private void updateBuffer(InputFile f) {
    JSONObject args = new JSONObject();
    args.put("FileName", f.absolutePath());
    try {
      args.put("Buffer", f.contents());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    makeSyncRequest("/updatebuffer", args);
  }

  private void handle(SensorContext context, InputFile f, JSONObject response) {
    JSONObject body = (JSONObject) response.get("Body");
    JSONArray issues = (JSONArray) body.get("QuickFixes");
    issues.forEach(i -> {
      JSONObject issue = (JSONObject) i;
      NewIssue newIssue = context.newIssue();
      String ruleId = (String) issue.get("Id");
      if (!ruleId.startsWith("S")) {
        // Ignore non SonarCS issues
        return;
      }
      newIssue
        .forRule(RuleKey.of(CSharpPlugin.REPOSITORY_KEY, ruleId))
        .at(
          newIssue.newLocation()
            .on(f)
            .at(f.newRange((int) (long) issue.get("Line"), (int) (long) issue.get("Column") - 1, (int) (long) issue.get("EndLine"), (int) (long) issue.get("EndColumn") - 1))
            .message((String) issue.get("Text")))
        .save();
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
