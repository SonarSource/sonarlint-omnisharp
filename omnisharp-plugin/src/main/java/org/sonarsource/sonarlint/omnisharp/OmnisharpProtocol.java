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
import java.io.PipedOutputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
@SonarLintSide(lifespan = SonarLintSide.MULTIPLE_ANALYSES)
public class OmnisharpProtocol {

  private static final Logger LOG = Loggers.get(OmnisharpProtocol.class);

  private final AtomicLong requestId = new AtomicLong(1L);
  private final Queue<OmnisharpRequest> requestQueue = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<Long, OmnisharpResponseHandler> responseLatchQueue = new ConcurrentHashMap<>();

  private RequestQueuePumper requestQueuePumper;

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

  void startRequestQueuePumper(PipedOutputStream output) {
    requestQueuePumper = new RequestQueuePumper(requestQueue, output);
    new Thread(requestQueuePumper).start();
  }

  void stopRequestQueuePumper() {
    if (requestQueuePumper != null) {
      requestQueuePumper.stopProcessing();
      requestQueuePumper = null;
    }
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

  private void handleLog(JsonObject jsonObject) {
    String level = jsonObject.get("LogLevel").getAsString();
    String message = jsonObject.get("Message").getAsString();
    LOG.debug("Omnisharp: [" + level + "] " + message);
  }

  public void codeCheck(File f, Consumer<OmnisharpDiagnostic> issueHandler) {
    JsonObject args = new JsonObject();
    args.addProperty("FileName", f.getAbsolutePath());
    JsonObject resp = makeSyncRequest("/codecheck", args);
    handle(resp, issueHandler);
  }

  public void updateBuffer(File f, String buffer) {
    JsonObject args = new JsonObject();
    args.addProperty("FileName", f.getAbsolutePath());
    args.addProperty("Buffer", buffer);
    makeSyncRequest("/updatebuffer", args);
  }

  public void stopServer() {
    makeSyncRequest("/stopserver", null);
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

  private JsonObject makeSyncRequest(String command, @Nullable JsonObject dataJson) {
    long id = requestId.getAndIncrement();
    JsonObject args = new JsonObject();
    args.addProperty("Type", "request");
    args.addProperty("Seq", id);
    args.addProperty("Command", command);
    args.add("Arguments", dataJson);

    OmnisharpResponseHandler omnisharpResponseHandler = enqueue(id, args);
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

  private OmnisharpResponseHandler enqueue(long id, JsonObject args) {
    OmnisharpRequest req = new OmnisharpRequest(new Gson().toJson(args));

    OmnisharpResponseHandler omnisharpResponseHandler = new OmnisharpResponseHandler();
    responseLatchQueue.put(id, omnisharpResponseHandler);
    requestQueue.add(req);
    return omnisharpResponseHandler;
  }

  class OmnisharpRequest {
    private final String jsonPayload;

    OmnisharpRequest(String jsonPayload) {
      this.jsonPayload = jsonPayload;
    }

    public String getJsonPayload() {
      return jsonPayload;
    }
  }

  class OmnisharpResponseHandler {
    JsonObject response;
    CountDownLatch responseLatch = new CountDownLatch(1);
  }

}
