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

import java.io.File;
import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileListener;

@SonarLintSide(lifespan = "MODULE")
public class OmnisharpFileListener implements ModuleFileListener {

  private final OmnisharpEndpoints omnisharpEndpoints;
  private final OmnisharpServerController serverController;

  public OmnisharpFileListener(OmnisharpServerController serverController, OmnisharpEndpoints omnisharpEndpoints) {
    this.serverController = serverController;
    this.omnisharpEndpoints = omnisharpEndpoints;
  }

  @Override
  public void process(ModuleFileEvent event) {
    if (!serverController.isOmnisharpStarted()) {
      return;
    }
    File file = event.getTarget().file();
    switch (event.getType()) {
      case CREATED:
        if (file.getName().endsWith(".sln") || file.getName().endsWith(".csproj")) {
          // Stop the server so that it is restarted during the next analysis and take into account changes to the solution, or added
          // projects
          serverController.stopServer();
        } else {
          omnisharpEndpoints.fileChanged(file, OmnisharpEndpoints.FileChangeType.CREATE);
        }
        break;
      case DELETED:
        omnisharpEndpoints.fileChanged(file, OmnisharpEndpoints.FileChangeType.DELETE);
        break;
      case MODIFIED:
        if (file.getName().endsWith(".sln")) {
          // Stop the server so that it is restarted during the next analysis and take into account changes to the solution
          serverController.stopServer();
        } else {
          omnisharpEndpoints.fileChanged(file, OmnisharpEndpoints.FileChangeType.CHANGE);
        }
        break;
      default:
        break;
    }

  }

}
