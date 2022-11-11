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

using System;
using System.Collections.Generic;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes
{
    internal class QuickFix : IQuickFix
    {
        public QuickFix(string message, IReadOnlyList<IFix> fixes)
        {
            if (fixes == null || fixes.Count == 0)
            {
                throw new ArgumentNullException(nameof(fixes), "A quick fix should have at least one fix.");
            }

            Message = message;
            Fixes = fixes;
        }

        public string Message { get; }
        public IReadOnlyList<IFix> Fixes { get; }
    }

    internal class Fix : IFix
    {
        public Fix(string fileName, IReadOnlyList<IEdit> edits)
        {
            if (edits == null || edits.Count == 0)
            {
                throw new ArgumentNullException(nameof(edits), "A fix should have at least one edit.");
            }

            FileName = fileName;
            Edits = edits;
        }

        public string FileName { get; }
        public IReadOnlyList<IEdit> Edits { get; }
    }

    internal class Edit : IEdit
    {
        public Edit(int startLine, int endLine, int startColumn, int endColumn, string newText)
        {
            StartLine = startLine;
            EndLine = endLine;
            StartColumn = startColumn;
            EndColumn = endColumn;
            NewText = newText;
        }

        public int StartLine { get; }
        public int StartColumn { get; }
        public int EndLine { get; }
        public int EndColumn { get; }
        public string NewText { get; }
    }
}