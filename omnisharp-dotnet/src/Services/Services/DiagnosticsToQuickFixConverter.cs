﻿/*
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

using System.Collections.Immutable;
using System.Linq;
using OmniSharp.Helpers;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations;

namespace SonarLint.OmniSharp.DotNet.Services.Services
{
    internal interface IDiagnosticsToCodeLocationsConverter
    {
        ImmutableArray<SonarLintDiagnosticLocation> Convert(ImmutableArray<DocumentDiagnostics> documentDiagnostics, string fileNameFilter);
    }
    
    internal class DiagnosticsToCodeLocationsConverter : IDiagnosticsToCodeLocationsConverter
    {
        public ImmutableArray<SonarLintDiagnosticLocation> Convert(ImmutableArray<DocumentDiagnostics> documentDiagnostics, string fileNameFilter)
        {
            var diagnosticLocations = documentDiagnostics
                .Where(x => string.IsNullOrEmpty(fileNameFilter) || x.DocumentPath == fileNameFilter)
                .DistinctDiagnosticLocationsByProject()
                .Where(x => x.FileName != null)
                .ToImmutableArray();

            return diagnosticLocations;
        }
    }
}
