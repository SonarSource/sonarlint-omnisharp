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

import org.sonarsource.api.sonarlint.SonarLintSide;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileEvent;
import org.sonarsource.sonarlint.plugin.api.module.file.ModuleFileListener;

@SonarLintSide(lifespan = "MODULE")
public class OmnisharpFileListener implements ModuleFileListener {

  private final OmnisharpProtocol omnisharpProtocol;
  private final OmnisharpServer server;

  public OmnisharpFileListener(OmnisharpServer server, OmnisharpProtocol omnisharpProtocol) {
    this.server = server;
    this.omnisharpProtocol = omnisharpProtocol;
  }

  @Override
  public void process(ModuleFileEvent event) {
    if (!server.isOmnisharpStarted()) {
      return;
    }
    switch (event.getType()) {
      case CREATED:
        omnisharpProtocol.fileChanged(event.getTarget().file(), OmnisharpProtocol.FileChangeType.CREATE);
        break;
      case DELETED:
        omnisharpProtocol.fileChanged(event.getTarget().file(), OmnisharpProtocol.FileChangeType.DELETE);
        break;
      case MODIFIED:
        omnisharpProtocol.fileChanged(event.getTarget().file(), OmnisharpProtocol.FileChangeType.CHANGE);
        break;
      default:
        break;

    }

  }

}
