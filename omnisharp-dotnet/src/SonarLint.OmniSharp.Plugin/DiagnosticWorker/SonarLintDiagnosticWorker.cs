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

using System.Collections.Immutable;
using System.Composition;
using System.Linq;
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
using OmniSharp.Services;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker
{
    interface ISonarLintDiagnosticWorker : ICsDiagnosticWorker
    {
    }

    [Export(typeof(ISonarLintDiagnosticWorker)), Shared]
    internal class SonarLintDiagnosticWorker : CopiedCSharpDiagnosticWorkerWithAnalyzers, ISonarLintDiagnosticWorker
    {
        private readonly ISonarLintAnalysisConfigProvider sonarLintAnalysisConfigProvider;

        [ImportingConstructor]
        public SonarLintDiagnosticWorker(ISonarLintAnalysisConfigProvider sonarLintAnalysisConfigProvider,
            OmniSharpWorkspace workspace,
            ILoggerFactory loggerFactory,
            DiagnosticEventForwarder forwarder,
            IOptionsMonitor<OmniSharpOptions> options)
            : base(workspace, Enumerable.Empty<ICodeActionProvider>(), loggerFactory, forwarder, options.CurrentValue)
        {
            this.sonarLintAnalysisConfigProvider = sonarLintAnalysisConfigProvider;
        }
        
        protected override async Task<ImmutableArray<Diagnostic>> AnalyzeDocument(Project project,
            ImmutableArray<DiagnosticAnalyzer> allAnalyzers, Compilation compilation,
            AnalyzerOptions workspaceAnalyzerOptions, Document document)
        {
            var analysisConfig = sonarLintAnalysisConfigProvider.Get(compilation, workspaceAnalyzerOptions);
            
            var result = await base.AnalyzeDocument(project, 
                analysisConfig.Analyzers, 
                analysisConfig.Compilation, 
                analysisConfig.AnalyzerOptions, 
                document);

            var supportedRules = analysisConfig.AnalyzerRules;
            var resultsWithoutCompilerRules = result.Where(x => supportedRules.Contains(x.Id)).ToImmutableArray();

            return resultsWithoutCompilerRules;
        }
    }
}
