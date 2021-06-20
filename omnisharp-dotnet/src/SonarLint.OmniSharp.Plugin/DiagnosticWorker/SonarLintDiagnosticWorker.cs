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
using System.ComponentModel.Composition;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using OmniSharp;
using OmniSharp.Options;
using OmniSharp.Roslyn;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using OmniSharp.Roslyn.CSharp.Workers.Diagnostics;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker
{
    interface ISonarLintDiagnosticWorker : ICsDiagnosticWorker
    {
    }

    [Export(typeof(ISonarLintDiagnosticWorker))]
    [PartCreationPolicy(CreationPolicy.Shared)]
    internal class SonarLintDiagnosticWorker : CopiedCSharpDiagnosticWorkerWithAnalyzers, ISonarLintDiagnosticWorker
    {
        private readonly ISonarLintDiagnosticWorkerDataProvider sonarLintDiagnosticWorkerDataProvider;

        [ImportingConstructor]
        public SonarLintDiagnosticWorker(ISonarLintDiagnosticWorkerDataProvider sonarLintDiagnosticWorkerDataProvider,
            OmniSharpWorkspace workspace,
            ISonarAnalyzerCodeActionProvider sonarAnalyzerCodeActionProvider,
            ILoggerFactory loggerFactory,
            DiagnosticEventForwarder forwarder,
            IOptionsMonitor<OmniSharpOptions> options)
            : base(workspace, new[] {sonarAnalyzerCodeActionProvider}, loggerFactory, forwarder, options.CurrentValue)
        {
            this.sonarLintDiagnosticWorkerDataProvider = sonarLintDiagnosticWorkerDataProvider;
        }
        
        protected override Task<ImmutableArray<Diagnostic>> AnalyzeDocument(Project project,
            ImmutableArray<DiagnosticAnalyzer> allAnalyzers, Compilation compilation,
            AnalyzerOptions workspaceAnalyzerOptions, Document document)
        {
            var diagnosticWorkerData = sonarLintDiagnosticWorkerDataProvider.Get(compilation, workspaceAnalyzerOptions);
            
            return base.AnalyzeDocument(project, 
                diagnosticWorkerData.Analyzers, 
                diagnosticWorkerData.Compilation, 
                diagnosticWorkerData.AnalyzerOptions, 
                document);
        }
    }
}