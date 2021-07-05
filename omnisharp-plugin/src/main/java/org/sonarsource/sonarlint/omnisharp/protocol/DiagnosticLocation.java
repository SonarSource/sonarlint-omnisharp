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
package org.sonarsource.sonarlint.omnisharp.protocol;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;

public class DiagnosticLocation {

  @SerializedName("FileName")
  private String filename;

  @SerializedName("Line")
  private int line;

  @SerializedName("Column")
  private int column;

  @SerializedName("EndLine")
  private int endLine;

  @SerializedName("EndColumn")
  private int endColumn;

  @SerializedName("Text")
  @Nullable
  private String text;

  public String getFilename() {
    return filename;
  }

  public int getLine() {
    return line;
  }

  public int getEndLine() {
    return endLine;
  }

  public int getColumn() {
    return column;
  }

  public int getEndColumn() {
    return endColumn;
  }

  public String getText() {
    return text;
  }

}
