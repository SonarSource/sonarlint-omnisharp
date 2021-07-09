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
package org.sonarsource.sonarlint.omnisharp.protocol;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.api.sonarlint.SonarLintSide;

@ScannerSide
@SonarLintSide(lifespan = "MODULE")
public class OmnisharpResponseProcessor {

  private static final Logger LOG = Loggers.get(OmnisharpResponseProcessor.class);

  private final ConcurrentHashMap<Long, OmnisharpResponseHandler> responseLatchQueue = new ConcurrentHashMap<>();

  public void handleOmnisharpOutput(CountDownLatch startLatch, CountDownLatch firstUpdateProjectLatch, String line) {
    JsonObject jsonObject;
    try {
      jsonObject = JsonParser.parseString(line).getAsJsonObject();
    } catch (Exception e) {
      LOG.debug(line);
      return;
    }
    handleJsonMessage(startLatch, firstUpdateProjectLatch, line, jsonObject);
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
    LOG.debug("Omnisharp: [" + level + "] " + message);
  }

  static class OmnisharpResponseHandler {
    JsonObject response;
    CountDownLatch responseLatch = new CountDownLatch(1);
  }

  public OmnisharpResponseHandler registerResponseHandler(long id) {
    OmnisharpResponseHandler omnisharpResponseHandler = new OmnisharpResponseHandler();
    responseLatchQueue.put(id, omnisharpResponseHandler);
    return omnisharpResponseHandler;
  }

  public void removeResponseHandler(long id) {
    responseLatchQueue.remove(id);
  }

}
