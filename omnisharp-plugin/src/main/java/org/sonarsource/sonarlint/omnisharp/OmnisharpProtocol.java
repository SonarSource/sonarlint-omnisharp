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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.zeroturnaround.exec.stream.LogOutputStream;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpProtocol {

  private static final Logger LOG = Loggers.get(OmnisharpProtocol.class);

  private final AtomicLong requestId = new AtomicLong(1L);
  private final ConcurrentHashMap<Long, OmnisharpResponseHandler> responseLatchQueue = new ConcurrentHashMap<>();

  private OutputStream output;

  LogOutputStream buildOutputStreamHandler(CountDownLatch startLatch, CountDownLatch firstUpdateProjectLatch) {
    return new LogOutputStream() {
      @Override
      protected void processLine(String line) {
        JsonObject jsonObject;
        try {
          jsonObject = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
          LOG.debug(line);
          return;
        }
        handleJsonMessage(startLatch, firstUpdateProjectLatch, line, jsonObject);
      }

    };
  }

  void setOmnisharpProcessStdIn(@Nullable OutputStream output) {
    this.output = output;
  }

  private void handleJsonMessage(CountDownLatch startLatch, CountDownLatch firstUpdateProjectLatch, String line, JsonObject jsonObject) {
    String type = jsonObject.get("Type").getAsString();
    switch (type) {
      case "response":
        long reqSeq = jsonObject.get("Request_seq").getAsLong();
        OmnisharpResponseHandler omnisharpResponseHandler = responseLatchQueue.get(reqSeq);
        if (omnisharpResponseHandler != null) {
          omnisharpResponseHandler.response = jsonObject;
          omnisharpResponseHandler.responseLatch.countDown();
        }
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
          case "Diagnostic":
            // For now we ignore diagnostics "pushed" by Omnisharp
            break;
          default:
            LOG.debug(line);
        }
        break;
      default:
        LOG.debug(line);
    }
  }

  private static void handleLog(JsonObject jsonObject) {
    String level = jsonObject.get("LogLevel").getAsString();
    String message = jsonObject.get("Message").getAsString();
    // Workaround for SonarDotnet bug flooding logs
    if (message.contains("SonarLint.xml\"")) {
      return;
    }
    LOG.debug("Omnisharp: [" + level + "] " + message);
  }

  public void codeCheck(File f, Consumer<OmnisharpDiagnostic> issueHandler) {
    JsonObject args = new JsonObject();
    args.addProperty("FileName", f.getAbsolutePath());
    JsonObject resp = doRequestAndWaitForResponse("/sonarlint/codecheck", args);
    handle(resp, issueHandler);
  }

  public void config(JsonObject config) {
    doRequestAndWaitForResponse("/sonarlint/config", config);
  }

  public void updateBuffer(File f, String buffer) {
    JsonObject args = new JsonObject();
    args.addProperty("FileName", f.getAbsolutePath());
    args.addProperty("Buffer", buffer);
    doRequestAndWaitForResponse("/updatebuffer", args);
  }

  public void stopServer() {
    // Don't wait for the response, because sometimes the process seems to die before receiving it
    doRequest("/stopserver", null);
  }

  private static void handle(JsonObject response, Consumer<OmnisharpDiagnostic> issueHandler) {
    JsonObject body = response.get("Body").getAsJsonObject();
    JsonArray issues = body.get("QuickFixes").getAsJsonArray();
    issues.forEach(i -> {
      JsonObject issue = i.getAsJsonObject();
      String ruleId = issue.get("Id").getAsString();
      if (!ruleId.startsWith("S")) {
        // Optimization: ignore some non SonarCS issues
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

  private JsonObject doRequestAndWaitForResponse(String command, @Nullable JsonObject dataJson) {
    long id = requestId.getAndIncrement();
    OmnisharpRequest req = buildRequest(command, dataJson, id);

    OmnisharpResponseHandler omnisharpResponseHandler = new OmnisharpResponseHandler();
    responseLatchQueue.put(id, omnisharpResponseHandler);
    writeRequest(req);
    try {
      if (!omnisharpResponseHandler.responseLatch.await(1, TimeUnit.MINUTES)) {
        throw new IllegalStateException("Timeout waiting for request: " + command);
      }
      return omnisharpResponseHandler.response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted!", e);
    } finally {
      responseLatchQueue.remove(id);
    }
  }

  synchronized void writeRequest(OmnisharpRequest req) {
    if (output == null) {
      LOG.warn("Unable to write request, server has been stopped");
      return;
    }
    try {
      output.write(req.getJsonPayload().getBytes(StandardCharsets.UTF_8));
      output.write("\n".getBytes(StandardCharsets.UTF_8));
      output.flush();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to write in Omnisharp stdin", e);
    }
  }

  private void doRequest(String command, @Nullable JsonObject dataJson) {
    long id = requestId.getAndIncrement();
    OmnisharpRequest req = buildRequest(command, dataJson, id);
    writeRequest(req);
  }

  private static OmnisharpRequest buildRequest(String command, JsonObject dataJson, long id) {
    JsonObject args = new JsonObject();
    args.addProperty("Type", "request");
    args.addProperty("Seq", id);
    args.addProperty("Command", command);
    args.add("Arguments", dataJson);

    return new OmnisharpRequest(new Gson().toJson(args));
  }

  static class OmnisharpRequest {
    private final String jsonPayload;

    OmnisharpRequest(String jsonPayload) {
      this.jsonPayload = jsonPayload;
    }

    public String getJsonPayload() {
      return jsonPayload;
    }
  }

  static class OmnisharpResponseHandler {
    JsonObject response;
    CountDownLatch responseLatch = new CountDownLatch(1);
  }

}
