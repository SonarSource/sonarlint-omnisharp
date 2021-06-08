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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sonar.api.utils.System2;
import org.zeroturnaround.exec.stream.LogOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OmnisharpProtocolTests {

  @ParameterizedTest
  @ValueSource(strings = {"ProjectAdded", "ProjectChanged", "ProjectRemoved"})
  void testStartLatch(String firstConfigEvent) throws IOException {
    OmnisharpProtocol underTest = new OmnisharpProtocol(System2.INSTANCE);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstUpdateLatch = new CountDownLatch(1);
    LogOutputStream handler = underTest.buildOutputStreamHandler(startLatch, firstUpdateLatch);
    assertThat(startLatch.getCount()).isEqualTo(1);
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    IOUtils.write("Random\n", handler, StandardCharsets.UTF_8);

    assertThat(startLatch.getCount()).isEqualTo(1);
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    IOUtils.write("{\"Type\": \"event\", \"Event\": \"started\"}\n", handler, StandardCharsets.UTF_8);

    assertThat(startLatch.getCount()).isZero();
    assertThat(firstUpdateLatch.getCount()).isEqualTo(1);

    IOUtils.write("{\"Type\": \"event\", \"Event\": \"" + firstConfigEvent + "\"}\n", handler, StandardCharsets.UTF_8);

    assertThat(startLatch.getCount()).isZero();
    assertThat(firstUpdateLatch.getCount()).isZero();
  }

}
