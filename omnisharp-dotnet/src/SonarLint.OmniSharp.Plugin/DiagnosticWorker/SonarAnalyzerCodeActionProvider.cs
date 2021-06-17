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

using System.ComponentModel.Composition;
using OmniSharp.Services;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker
{
    /// <summary>
    /// Provide sonar-dotnet <see cref="Microsoft.CodeAnalysis.Diagnostics.DiagnosticAnalyzer"/>.
    /// </summary>
    internal interface ISonarAnalyzerCodeActionProvider : ICodeActionProvider
    {
    }
    /// <remarks>
    /// We only want this provider to be used by our custom diagnostic worker and not the "normal" OmniSharp workers,
    /// so we're exporting it using a different interface.
    /// However, we're reusing the OmniSharp <see cref="AbstractCodeActionProvider"/> class because it makes it easy to
    /// load assemblies from and extract diagnostic analyzers from them.
    /// </remarks>
    [Export(typeof(ISonarAnalyzerCodeActionProvider))]
    [PartCreationPolicy(CreationPolicy.Shared)]
    internal class SonarAnalyzerCodeActionProvider : AbstractCodeActionProvider, ISonarAnalyzerCodeActionProvider
    {
        [ImportingConstructor]
        public SonarAnalyzerCodeActionProvider(ISonarAnalyzerAssembliesProvider sonarAnalyzerAssembliesProvider)
            : base("SonarLint", sonarAnalyzerAssembliesProvider.Assemblies)
        {
        }
    }
}
