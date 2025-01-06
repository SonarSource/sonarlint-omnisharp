/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource SA
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class ProcessWrapper {

  private static final Logger LOG = Loggers.get(ProcessWrapper.class);

  private final Process p;
  private final Thread stdOutThread;
  private final Thread stdErrThread;
  private volatile boolean terminated = false;
  private final CompletableFuture<Integer> terminationFuture = new CompletableFuture<>();

  private ProcessWrapper(Process p, Consumer<String> stdOutConsumer, Consumer<String> stdErrConsumer) {
    this.p = p;
    stdOutThread = new Thread(() -> {
      try (BufferedReader streamReader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        while (!terminated) {
          String line = streamReader.readLine();
          if (line == null) {
            break;
          }
          stdOutConsumer.accept(line);
        }
      } catch (IOException e) {
        LOG.error("Error while reading stdout stream", e);
      }
    });
    stdOutThread.setName("omnisharp-stdout-stream-consumer-" + p.pid());
    stdErrThread = new Thread(() -> {
      try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
        while (!terminated) {
          String line = errorReader.readLine();
          if (line == null) {
            break;
          }
          stdErrConsumer.accept(line);
        }
      } catch (IOException e) {
        LOG.error("Error while reading stderr stream", e);
      }
    });
    stdErrThread.setName("omnisharp-stderr-stream-consumer-" + p.pid());
  }

  private void startStdIoConsumers() {
    stdOutThread.start();
    stdErrThread.start();
  }

  private void startWaitForThread() {
    Thread isAliveWatcherThread = new Thread(() -> {
      try {
        int exitCode = p.waitFor();
        LOG.debug("Process " + p.pid() + " exited with " + exitCode);
        terminationFuture.complete(exitCode);
      } catch (InterruptedException e) {
        p.destroyForcibly();
        Thread.currentThread().interrupt();
        terminationFuture.completeExceptionally(e);
      } finally {
        terminated = true;
        try {
          stdOutThread.join();
          stdErrThread.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    isAliveWatcherThread.setName("omnisharp-is-alive-watcher-" + p.pid());
    isAliveWatcherThread.start();
  }

  public static ProcessWrapper start(ProcessBuilder builder, Consumer<String> stdOutConsumer, Consumer<String> stdErrConsumer) throws IOException {
    Process process = builder.start();
    LOG.debug("Process {} started", process.pid());
    ProcessWrapper p = new ProcessWrapper(process, stdOutConsumer, stdErrConsumer);
    p.startStdIoConsumers();
    p.startWaitForThread();
    return p;
  }

  public CompletableFuture<Integer> getTerminationFuture() {
    return terminationFuture;
  }

  public void writeLnStdIn(String str) throws IOException {
    p.getOutputStream().write(str.getBytes(StandardCharsets.UTF_8));
    p.getOutputStream().write("\n".getBytes(StandardCharsets.UTF_8));
    p.getOutputStream().flush();
  }

  public void destroyForcibly() {
    p.destroyForcibly();
  }

  public void waitForProcessToEndOrKill(int timeout, TimeUnit unit) throws InterruptedException {
    if (!p.waitFor(timeout, unit)) {
      LOG.debug("Unable to terminate process, killing it");
      p.destroyForcibly();
    }
  }

}
