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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarsource.sonarlint.omnisharp.OmnisharpProtocol.OmnisharpRequest;

public class RequestQueuePumper implements Runnable {

  private static final Logger LOG = Loggers.get(RequestQueuePumper.class);

  public static final int SLEEPING_TIME = 100;

  /** the input stream to pump from */
  private final Queue<OmnisharpRequest> requestQueue;

  /** the output stream to pmp into */
  private final OutputStream os;

  /** flag to stop the stream pumping */
  private volatile boolean stop;

  /**
   * Create a new stream pumper.
   *
   * @param is input stream to read data from
   * @param os output stream to write data to.
   */
  public RequestQueuePumper(final Queue<OmnisharpRequest> requestQueue, final OutputStream os) {
    this.requestQueue = requestQueue;
    this.os = os;
    this.stop = false;
  }

  /**
   * Copies data from the input stream to the output stream. Terminates as
   * soon as the input stream is closed or an error occurs.
   */
  @Override
  public void run() {
    try {
      while (!stop) {
        synchronized (requestQueue) {
          while (!requestQueue.isEmpty() && !stop) {
            OmnisharpRequest request = requestQueue.poll();
            if (request != null) {
              os.write(request.getJsonPayload().getBytes(StandardCharsets.UTF_8));
              os.write("\n".getBytes(StandardCharsets.UTF_8));
            }
          }
          os.flush();
          requestQueue.wait(SLEEPING_TIME);
        }
      }
    } catch (Exception e) {
      LOG.error("Got exception while reading/writing the stream", e);
    }
  }

  public void stopProcessing() {
    stop = true;
  }

}
