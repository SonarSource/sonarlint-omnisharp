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
package org.sonarsource.sonarlint.omnisharp.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.scanner.ScannerSide;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.omnisharp.OmnisharpServer;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor.OmnisharpResponseHandler;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpEndpoints {

  private static final String FILENAME_PROPERTY = "FileName";

  private final AtomicLong requestId = new AtomicLong(1L);

  private OmnisharpServer server;

  private final OmnisharpResponseProcessor responseProcessor;

  public OmnisharpEndpoints(OmnisharpResponseProcessor responseProcessor) {
    this.responseProcessor = responseProcessor;
  }

  public void setServer(OmnisharpServer server) {
    this.server = server;
  }

  public void codeCheck(File f, Consumer<Diagnostic> issueHandler) {
    JsonObject args = new JsonObject();
    args.addProperty(FILENAME_PROPERTY, f.getAbsolutePath());
    JsonObject resp = doRequestAndWaitForResponse("/sonarlint/codecheck", args);
    handle(resp, issueHandler);
  }

  public void config(JsonObject config) {
    doRequestAndWaitForResponse("/sonarlint/config", config);
  }

  public enum FileChangeType {
    CHANGE("Change"),
    CREATE("Create"),
    DELETE("Delete"),
    DIRECTORY_DELETE("DirectoryDelete");

    private final String protocolValue;

    FileChangeType(String protocolValue) {
      this.protocolValue = protocolValue;
    }

  }

  public void fileChanged(File f, FileChangeType type) {
    JsonArray args = new JsonArray();
    JsonObject req = new JsonObject();
    req.addProperty(FILENAME_PROPERTY, f.getAbsolutePath());
    req.addProperty("changeType", type.protocolValue);
    args.add(req);
    doRequestAndWaitForResponse("/filesChanged", args);
  }

  public void updateBuffer(File f, String buffer) {
    JsonObject args = new JsonObject();
    args.addProperty(FILENAME_PROPERTY, f.getAbsolutePath());
    args.addProperty("Buffer", buffer);
    doRequestAndWaitForResponse("/updatebuffer", args);
  }

  public void stopServer() {
    // Don't wait for the response, because sometimes the process seems to die before receiving it
    doRequest("/stopserver", null);
  }

  private static void handle(JsonObject response, Consumer<Diagnostic> issueHandler) {
    JsonObject body = response.get("Body").getAsJsonObject();
    JsonArray issues = body.get("QuickFixes").getAsJsonArray();
    Diagnostic[] diagnostics = new Gson().fromJson(issues, Diagnostic[].class);
    Stream.of(diagnostics).forEach(i -> {
      if (!i.getId().startsWith("S")) {
        // Optimization: ignore some non SonarCS issues
        return;
      }
      issueHandler.accept(i);
    });
  }

  @CheckForNull
  private static String getAsStringOrNull(@Nullable JsonElement element) {
    return (element == null || element.isJsonNull()) ? null : element.getAsString();
  }

  private JsonObject doRequestAndWaitForResponse(String command, @Nullable JsonElement dataJson) {
    long id = requestId.getAndIncrement();
    OmnisharpRequest req = buildRequest(command, dataJson, id);

    OmnisharpResponseHandler omnisharpResponseHandler = responseProcessor.registerResponseHandler(id);
    try {
      if (!server.writeRequestOnStdIn(req.getJsonPayload())) {
        throw new IllegalStateException("Unable to send request to the OmniSharp server: " + command);
      }
      if (!omnisharpResponseHandler.responseLatch.await(1, TimeUnit.MINUTES)) {
        throw new IllegalStateException("Timeout waiting for response to: " + command);
      }
      return omnisharpResponseHandler.response;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted!", e);
    } finally {
      responseProcessor.removeResponseHandler(id);
    }
  }

  private void doRequest(String command, @Nullable JsonObject dataJson) {
    long id = requestId.getAndIncrement();
    OmnisharpRequest req = buildRequest(command, dataJson, id);
    server.writeRequestOnStdIn(req.getJsonPayload());
  }

  private static OmnisharpRequest buildRequest(String command, JsonElement dataJson, long id) {
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

}
