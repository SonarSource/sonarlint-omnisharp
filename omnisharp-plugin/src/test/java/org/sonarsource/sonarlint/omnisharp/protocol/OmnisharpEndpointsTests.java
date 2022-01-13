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

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.sonarlint.omnisharp.OmnisharpServer;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints.FileChangeType;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OmnisharpEndpointsTests {

  @RegisterExtension
  LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private OmnisharpEndpoints underTest;
  private CountDownLatch startLatch;
  private CountDownLatch firstUpdateLatch;

  private OmnisharpServer omnisharpServer;

  private final List<String> requests = new ArrayList<>();

  private OmnisharpResponseProcessor responseProcessor;

  @BeforeEach
  void prepare() throws IOException {
    requests.clear();
    responseProcessor = new OmnisharpResponseProcessor();
    underTest = new OmnisharpEndpoints(responseProcessor);

    startLatch = new CountDownLatch(1);
    firstUpdateLatch = new CountDownLatch(1);

    omnisharpServer = mock(OmnisharpServer.class);
    underTest.setServer(omnisharpServer);

    doAnswer(new Answer<Boolean>() {

      @Override
      public Boolean answer(InvocationOnMock invocation) throws Throwable {
        requests.add(invocation.getArgument(0));
        return true;
      }
    }).when(omnisharpServer).writeRequestOnStdIn(anyString());
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
  void stopServer() throws Exception {
    underTest.stopServer();

    assertThat(requests).containsExactly("{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/stopserver\"}");
  }

  @Test
  void updateBuffer() throws Exception {
    File f = new File("Foo.cs");

    // updateBuffer is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.updateBuffer(f, "Some content");
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requests).containsExactly(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/updatebuffer\",\"Arguments\":{\"FileName\":\"" + toJsonAbsolutePath(f) + "\",\"Buffer\":\"Some content\"}}"));

    emulateReceivedMessage("{\"Type\": \"response\", \"Request_seq\": 1}");

    // wait for updateBuffer to finish
    t.join(1000);
    assertThat(t.isAlive()).isFalse();
  }

  @Test
  void codeCheckReturnsEmpty() throws Exception {
    List<Diagnostic> issues = new ArrayList<>();
    File f = new File("Foo.cs");

    doCodeCheck(f, issues, "\"Body\": {"
      + "    \"QuickFixes\": []"
      + "  }");

    assertThat(issues).isEmpty();
  }

  @Test
  void codeCheck() throws Exception {
    List<Diagnostic> issues = new ArrayList<>();
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
      + "      },"
      + "      {"
      + "        \"AdditionalLocations\": ["
      + "          {"
      + "            \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "            \"Line\": 16,"
      + "            \"Column\": 25,"
      + "            \"EndLine\": 16,"
      + "            \"EndColumn\": 30,"
      + "            \"Text\": \"+4 (incl 3 for nesting)\""
      + "          },"
      + "          {"
      + "            \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "            \"Line\": 18,"
      + "            \"Column\": 29,"
      + "            \"EndLine\": 18,"
      + "            \"EndColumn\": 34,"
      + "            \"Text\": null"
      + "          },"
      + "          {"
      + "            \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "            \"Line\": 20,"
      + "            \"Column\": 33,"
      + "            \"EndLine\": 20,"
      + "            \"EndColumn\": 38"
      + "          }"
      + "        ],"
      + "        \"LogLevel\": \"Warning\","
      + "        \"Id\": \"S3776\","
      + "        \"Tags\": [],"
      + "        \"FileName\": \"" + toJsonAbsolutePath(f) + "\","
      + "        \"Line\": 7,"
      + "        \"Column\": 21,"
      + "        \"EndLine\": 7,"
      + "        \"EndColumn\": 25,"
      + "        \"Text\": \"Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed.\","
      + "        \"Projects\": ["
      + "          \"ConsoleApp2\""
      + "        ]"
      + "      }"
      + "    ]"
      + "  }");

    assertThat(issues)
      .extracting(Diagnostic::getId, Diagnostic::getLine, Diagnostic::getColumn, Diagnostic::getEndLine, Diagnostic::getEndColumn,
        Diagnostic::getText)
      .containsOnly(tuple("S1118", 5, 11, 5, 18, "Add a 'protected' constructor or the 'static' keyword to the class declaration."),
        tuple("S3776", 7, 21, 7, 25, "Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed."));

    assertThat(issues.get(1).getAdditionalLocations())
      .extracting(DiagnosticLocation::getLine, DiagnosticLocation::getColumn, DiagnosticLocation::getEndLine, DiagnosticLocation::getEndColumn,
        DiagnosticLocation::getText)
      .containsOnly(
        tuple(16, 25, 16, 30, "+4 (incl 3 for nesting)"),
        tuple(18, 29, 18, 34, null),
        tuple(20, 33, 20, 38, null));
  }

  @Test
  void config() throws Exception {
    JsonObject config = new JsonObject();
    config.addProperty("foo", "bar");
    // config is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.config(config);
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requests).containsExactly(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/sonarlint/config\",\"Arguments\":{\"foo\":\"bar\"}}"));

    emulateReceivedMessage("{\"Type\": \"response\", \"Request_seq\": 1}");

    // wait for config to finish
    t.join(1000);
    assertThat(t.isAlive()).isFalse();
  }

  @Test
  void fileChanged() throws Exception {
    File f = new File("Foo.cs");
    // fileChanged is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.fileChanged(f, FileChangeType.CHANGE);
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requests).containsExactly(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/filesChanged\",\"Arguments\":[{\"FileName\":\"" + toJsonAbsolutePath(f) + "\",\"changeType\":\"Change\"}]}"));

    emulateReceivedMessage("{\"Type\": \"response\", \"Request_seq\": 1}");

    // wait for fileChanged to finish
    t.join(1000);
    assertThat(t.isAlive()).isFalse();
  }

  @Test
  void failEarlyIfUnableToWriteRequestToServer() throws Exception {
    when(omnisharpServer.writeRequestOnStdIn(anyString())).thenReturn(false);
    JsonObject jsonObject = new JsonObject();
    assertThrows(IllegalStateException.class, () -> underTest.config(jsonObject));
  }

  private void doCodeCheck(File f, List<Diagnostic> issues, String jsonBody) throws IOException, InterruptedException {
    // codeCheck is blocking, so run it in a separate Thread
    Thread t = new Thread(() -> {
      underTest.codeCheck(f, i -> issues.add(i));
    });
    t.start();

    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(requests).containsExactly(
      "{\"Type\":\"request\",\"Seq\":1,\"Command\":\"/sonarlint/codecheck\",\"Arguments\":{\"FileName\":\"" + toJsonAbsolutePath(f) + "\"}}"));

    emulateReceivedMessage("{"
      + "  \"Request_seq\": 1,"
      + "  \"Command\": \"/sonarlint/codecheck\","
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
    return f.getAbsolutePath().replaceAll("\\\\", Matcher.quoteReplacement("\\\\"));
  }

  private void emulateReceivedMessage(String msg) throws IOException {
    responseProcessor.handleOmnisharpOutput(startLatch, firstUpdateLatch, msg);
  }

}
