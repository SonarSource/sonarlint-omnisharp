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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.sonarlint.omnisharp.OmnisharpProtocol.OmnisharpRequest;
import org.zeroturnaround.exec.stream.LogOutputStream;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class OmnisharpProtocolTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpProtocol underTest;
  private CountDownLatch startLatch;
  private CountDownLatch firstUpdateLatch;
  private LogOutputStream logOutputStream;

  private System2 system2;

  private PipedOutputStream output;
  private PipedInputStream input;
  private BufferedReader requestReader;

  @BeforeEach
  void prepare() throws IOException {
    system2 = spy(new System2());
    underTest = new OmnisharpProtocol(system2);

    startLatch = new CountDownLatch(1);
    firstUpdateLatch = new CountDownLatch(1);
    logOutputStream = underTest.buildOutputStreamHandler(startLatch, firstUpdateLatch);

    output = new PipedOutputStream();
    input = new PipedInputStream(output);
    requestReader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

    underTest.startRequestQueuePumper(output);
  }

  @AfterEach
  void cleanup() throws IOException {
    underTest.stopRequestQueuePumper();
    requestReader.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ProjectAdded", "ProjectChanged", "ProjectRemoved"})
  void testStartLatch(String firstConfigEvent) throws IOException {
    assertThat(startLatch.getCount()).isEqualTo(1);
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    emulateReceivedMessage("Random");

    assertThat(startLatch.getCount()).isEqualTo(1);
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    emulateReceivedMessage("{\"Type\": \"event\", \"Event\": \"started\"}");

    assertThat(startLatch.getCount()).isZero();
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    emulateReceivedMessage("{\"Type\": \"event\", \"Event\": \"" + firstConfigEvent + "\"}");

    assertThat(startLatch.getCount()).isZero();
    assertThat(firstUpdateLatch.getCount()).isZero();
  }

  @Test
  void testUnknownMessagesLoggedAsDebug() throws IOException {
    emulateReceivedMessage("Something not json");

    assertThat(logTester.logs(LoggerLevel.DEBUG)).containsExactly("Something not json");
  }

  @Test
  void testUnknownTypeLoggedAsDebug() throws IOException {
    emulateReceivedMessage("{\"Type\": \"unknown\", \"Body\": {\"Foo\": \"Bar\"}}");

    assertThat(logTester.logs(LoggerLevel.DEBUG)).containsExactly("{\"Type\": \"unknown\", \"Body\": {\"Foo\": \"Bar\"}}");
  }

  @Test
  void testUnknownEventTypeLoggedAsDebug() throws IOException {
    emulateReceivedMessage("{\"Type\": \"event\", \"Event\": \"unknown\", \"Body\": {\"Foo\": \"Bar\"}}");

    assertThat(logTester.logs(LoggerLevel.DEBUG)).containsExactly("{\"Type\": \"event\", \"Event\": \"unknown\", \"Body\": {\"Foo\": \"Bar\"}}");
  }

  @Test
  void testOmnisharpLogLoggedAsDebug() throws IOException {
    emulateReceivedMessage("{\"Type\": \"event\", \"Event\": \"log\", \"Body\": {\"LogLevel\": \"DEBUG\", \"Message\": \"Some message\"}}");

    assertThat(logTester.logs(LoggerLevel.DEBUG)).containsExactly("Omnisharp: [DEBUG] Some message");
  }

  @Test
  @Timeout(value = 1, unit = MINUTES)
  void testRequestQueuePumperStartStop() throws IOException, InterruptedException {
    underTest.enqueue(new OmnisharpRequest("first_request"));

    assertThat(requestReader.readLine()).isEqualTo("first_request");

    underTest.enqueue(new OmnisharpRequest("second_request"));

    assertThat(requestReader.readLine()).isEqualTo("second_request");

    underTest.stopRequestQueuePumper();

    underTest.enqueue(new OmnisharpRequest("late_request"));

    // Normally the late request should no be processed, but give it time to be sure we don't have a bug
    Thread.sleep(100);

    output.close();

    // By reading -1, we know nothing more has been written to the stream, and in particular not the "late_request"
    assertThat(input.read()).isEqualTo(-1);
  }

  @Test
  void stopServerOnUnix() throws Exception {
    when(system2.isOsWindows()).thenReturn(false);

    // stopServer is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.stopServer();
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requestReader.readLine()).isEqualTo("{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/stopserver\"}"));

    emulateReceivedMessage("{\"Type\": \"response\", \"Request_seq\": 1}");

    // wait for stopServer to finish
    t.join(1000);
    assertThat(t.isAlive()).isFalse();
  }

  @Test
  void stopServerOnWindows() throws Exception {
    when(system2.isOsWindows()).thenReturn(true);

    underTest.stopServer();

    assertThat(requestReader.readLine()).isEqualTo("{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/stopserver\"}");
  }

  @Test
  void updateBuffer() throws Exception {
    File f = new File("Foo.cs");

    // updateBuffer is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.updateBuffer(f, "Some content");
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requestReader.readLine()).isEqualTo(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/updatebuffer\",\"Arguments\":{\"FileName\":\"" + toJsonAbsolutePath(f) + "\",\"Buffer\":\"Some content\"}}"));

    emulateReceivedMessage("{\"Type\": \"response\", \"Request_seq\": 1}");

    // wait for updateBuffer to finish
    t.join(1000);
    assertThat(t.isAlive()).isFalse();
  }

  @Test
  void codeCheckReturnsEmpty() throws Exception {
    List<OmnisharpDiagnostic> issues = new ArrayList<>();
    File f = new File("Foo.cs");

    doCodeCheck(f, issues, "\"Body\": {"
      + "    \"QuickFixes\": []"
      + "  }");

    assertThat(issues).isEmpty();
  }

  @Test
  void codeCheck() throws Exception {
    List<OmnisharpDiagnostic> issues = new ArrayList<>();
    File f = new File("Foo.cs");

    doCodeCheck(f, issues, "\"Body\": {"
      + "    \"QuickFixes\": ["
      + "      {"
      + "        \"LogLevel\": \"Warning\","
      + "        \"Id\": \"S1118\","
      + "        \"Tags\": [],"
      + "        \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "        \"Line\": 5,"
      + "        \"Column\": 11,"
      + "        \"EndLine\": 5,"
      + "        \"EndColumn\": 18,"
      + "        \"Text\": \"Add a 'protected' constructor or the 'static' keyword to the class declaration.\","
      + "        \"Projects\": ["
      + "          \"ConsoleApp1\""
      + "        ]"
      + "      },"
      + "      {"
      + "        \"LogLevel\": \"Info\","
      + "        \"Id\": \"IDE0060\","
      + "        \"Tags\": ["
      + "          \"Unnecessary\""
      + "        ],"
      + "        \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "        \"Line\": 7,"
      + "        \"Column\": 35,"
      + "        \"EndLine\": 7,"
      + "        \"EndColumn\": 39,"
      + "        \"Text\": \"Remove unused parameter 'args'\","
      + "        \"Projects\": ["
      + "          \"ConsoleApp1\""
      + "        ]"
      + "      }"
      + "    ]"
      + "  }");

    assertThat(issues).extracting(o -> o.id, o -> o.line, o -> o.column, o -> o.endLine, o -> o.endColumn, o -> o.text)
      .containsOnly(tuple("S1118", 5, 11, 5, 18, "Add a 'protected' constructor or the 'static' keyword to the class declaration."));
  }

  private void doCodeCheck(File f, List<OmnisharpDiagnostic> issues, String jsonBody) throws IOException, InterruptedException {
    // codeCheck is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.codeCheck(f, i -> issues.add(i));
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requestReader.readLine()).isEqualTo(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/codecheck\",\"Arguments\":{\"FileName\":\"" + toJsonAbsolutePath(f) + "\"}}"));

    emulateReceivedMessage("{"
      + "  \"Request_seq\": 1,"
      + "  \"Command\": \"/codecheck\","
      + "  \"Running\": true,"
      + "  \"Success\": true,"
      + "  \"Message\": null,"
      + jsonBody + ","
      + "  \"Seq\": 409,"
      + "  \"Type\": \"response\""
      + "}");

    // wait for codeCheck to finish
    t.join(1000);
  }

  private String toJsonAbsolutePath(File f) {
    return f.getAbsolutePath().replaceAll("\\\\", "\\\\");
  }

  private void emulateReceivedMessage(String msg) throws IOException {
    IOUtils.write(msg + "\n", logOutputStream, StandardCharsets.UTF_8);
  }

}
