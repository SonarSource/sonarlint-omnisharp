/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource Sàrl
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

import java.io.File;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.api.batch.fs.InputFile;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints.FileChangeType;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OmnisharpFileListenerTests {

  private OmnisharpFileListener underTest;
  private OmnisharpServerController omnisharpServer;
  private OmnisharpEndpoints omnisharpProtocol;

  @BeforeEach
  void prepare() {
    omnisharpServer = mock(OmnisharpServerController.class);
    when(omnisharpServer.isOmnisharpStarted()).thenReturn(true);
    omnisharpProtocol = mock(OmnisharpEndpoints.class);
    underTest = new OmnisharpFileListener(omnisharpServer, omnisharpProtocol);
  }

  @Test
  void dontBroadcastIfServerNotStarted() {
    when(omnisharpServer.isOmnisharpStarted()).thenReturn(false);

    underTest.process(mock(ModuleFileEvent.class));

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  @ParameterizedTest
  @CsvSource({
    "CREATED, CREATE",
    "MODIFIED, CHANGE",
    "DELETED, DELETE"
  })
  void broadcastFileEvents(ModuleFileEvent.Type eventType, FileChangeType expectedChangeType) {
    var f = new File("some/Foo.cs");
    var event = mockEvent(eventType, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verify(omnisharpProtocol).fileChanged(f, expectedChangeType);
  }

  @ParameterizedTest
  @MethodSource("solutionFileArguments")
  void stopServerWhenSolutionFileChanges(String fileName, ModuleFileEvent.Type eventType) {
    var f = new File(fileName);
    var event = mockEvent(eventType, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verify(omnisharpServer).stopServer();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  static Stream<Arguments> solutionFileArguments() {
    return Stream.of(
      Arguments.of("Solution.sln", ModuleFileEvent.Type.CREATED),
      Arguments.of("Solution.sln", ModuleFileEvent.Type.MODIFIED),
      Arguments.of("Solution.slnx", ModuleFileEvent.Type.CREATED),
      Arguments.of("Solution.slnx", ModuleFileEvent.Type.MODIFIED)
    );
  }

  @Test
  void stopServerIfCreatedProject() {
    var f = new File("foo/Project1.csproj");
    var event = mockEvent(ModuleFileEvent.Type.CREATED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verify(omnisharpServer).stopServer();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  private ModuleFileEvent mockEvent(ModuleFileEvent.Type type, File f) {
    return new ModuleFileEvent() {

      @Override
      public Type getType() {
        return type;
      }

      @Override
      public InputFile getTarget() {
        return mockInputFile(f);
      }

    };
  }

  private InputFile mockInputFile(File f) {
    var inputFile = mock(InputFile.class);
    when(inputFile.file()).thenReturn(f);
    return inputFile;
  }

}
