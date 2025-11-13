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

import org.sonar.api.Plugin;
import org.sonar.api.SonarProduct;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpEndpoints;
import org.sonarsource.sonarlint.omnisharp.protocol.OmnisharpResponseProcessor;

public class OmnisharpPlugin implements Plugin {

  @Override
  public void define(Context context) {
    if (context.getRuntime().getProduct() == SonarProduct.SONARLINT) {
      context.addExtensions(
        OmnisharpServerController.class,
        OmnisharpSensor.class,
        OmnisharpEndpoints.class,
        OmnisharpServicesExtractor.class,
        OmnisharpFileListener.class,
        OmnisharpResponseProcessor.class,
        OmnisharpCommandBuilder.class);
    }

    context.addExtension(CSharpLanguage.class);

    context.addExtensions(new CSharpPropertyDefinitions().create());
  }

}
