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

using OmniSharp.Models.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations
{
    /// <summary>
    /// Extends <see cref="DiagnosticLocation"/> and provides additional diagnostic locations.
    /// </summary>
    /// <remarks>
    /// <see cref="AdditionalLocations"/> and <see cref="QuickFixes"/> are excluded from <see cref="DiagnosticLocation.Equals"/>
    /// </remarks>
    internal class SonarLintDiagnosticLocation : DiagnosticLocation, ICodeLocation
    {
        public ICodeLocation[] AdditionalLocations { get; set; }
        public IQuickFix[] QuickFixes { get; set; }
    }
}
