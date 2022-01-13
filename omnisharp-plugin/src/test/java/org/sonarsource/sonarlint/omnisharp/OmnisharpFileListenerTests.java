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
package org.sonarsource.sonarlint.omnisharp;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private OmnisharpServer omnisharpServer;
  private OmnisharpEndpoints omnisharpProtocol;

  @BeforeEach
  void prepare() throws IOException {
    omnisharpServer = mock(OmnisharpServer.class);
    when(omnisharpServer.isOmnisharpStarted()).thenReturn(true);
    omnisharpProtocol = mock(OmnisharpEndpoints.class);
    underTest = new OmnisharpFileListener(omnisharpServer, omnisharpProtocol);
  }

  @Test
  void dontBroadcastIfServerNotStarted() throws IOException {
    when(omnisharpServer.isOmnisharpStarted()).thenReturn(false);

    underTest.process(mock(ModuleFileEvent.class));

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  @Test
  void broadcastCreatedFileEvents() throws IOException {

    File f = new File("some/Foo.cs");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.CREATED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verify(omnisharpProtocol).fileChanged(f, FileChangeType.CREATE);
  }

  @Test
  void broadcastModifiedFileEvents() throws IOException {

    File f = new File("some/Foo.cs");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.MODIFIED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verify(omnisharpProtocol).fileChanged(f, FileChangeType.CHANGE);
  }

  @Test
  void broadcastDeletedFileEvents() throws IOException {

    File f = new File("some/Foo.cs");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.DELETED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verifyNoMoreInteractions(omnisharpServer);
    verify(omnisharpProtocol).fileChanged(f, FileChangeType.DELETE);
  }

  @Test
  void stopServerIfCreatedSolution() throws IOException {

    File f = new File("Solution.sln");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.CREATED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verify(omnisharpServer).stop();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  @Test
  void stopServerIfCreatedProject() throws IOException {
    File f = new File("foo/Project1.csproj");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.CREATED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verify(omnisharpServer).stop();
    verifyNoMoreInteractions(omnisharpServer);
    verifyNoInteractions(omnisharpProtocol);
  }

  @Test
  void stopServerIfModifiedSolution() throws IOException {
    File f = new File("Solution.sln");
    ModuleFileEvent event = mockEvent(ModuleFileEvent.Type.MODIFIED, f);

    underTest.process(event);

    verify(omnisharpServer).isOmnisharpStarted();
    verify(omnisharpServer).stop();
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
    InputFile inputFile = mock(InputFile.class);
    when(inputFile.file()).thenReturn(f);
    return inputFile;
  }

}
