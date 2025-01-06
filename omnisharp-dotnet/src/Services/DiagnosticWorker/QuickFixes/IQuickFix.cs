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

using System.Collections.Generic;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes
{
    public interface IQuickFix
    {
        string Message { get; }
        IReadOnlyList<IFix> Fixes { get; }
    }

    public interface IFix
    {
        string FileName { get; }
        IReadOnlyList<IEdit> Edits { get; }
    }

    public interface IEdit
    {
        /// <summary>
        /// 1-based line
        /// </summary>
        int StartLine { get; }
        
        /// <summary>
        /// 1-based line
        /// </summary>
        int EndLine { get; }
        
        /// <summary>
        /// 1-based column
        /// </summary>
        int StartColumn { get; }

        /// <summary>   
        /// 1-based column
        /// </summary>
        int EndColumn { get; }
        
        /// <summary>
        /// The new text to insert. Can be empty if the edit is a deletion.
        /// </summary>
        string NewText { get; }
    }
}
