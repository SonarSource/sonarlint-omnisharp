/*
 * SonarOmnisharp
 * Copyright (C) 2021-2024 SonarSource SA
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
using System.Collections.Immutable;
using System.Composition;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using OmniSharp;
using OmniSharp.Options;
using OmniSharp.Roslyn;
using OmniSharp.Roslyn.CSharp.Workers.Diagnostics;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker
{
    internal interface ISonarLintDiagnosticWorker : ICsDiagnosticWorker
    {
    }

    [Export(typeof(ISonarLintDiagnosticWorker)), Shared]
    internal class SonarLintDiagnosticWorker : CopiedCSharpDiagnosticWorker, ISonarLintDiagnosticWorker
    {
        private readonly ISonarLintAnalysisConfigProvider sonarLintAnalysisConfigProvider;

        [ImportingConstructor]
        public SonarLintDiagnosticWorker(ISonarLintAnalysisConfigProvider sonarLintAnalysisConfigProvider,
            OmniSharpWorkspace workspace,
            ILoggerFactory loggerFactory,
            DiagnosticEventForwarder forwarder,
            IOptionsMonitor<OmniSharpOptions> options)
            : base(workspace, forwarder, loggerFactory, options.CurrentValue)
        {
            this.sonarLintAnalysisConfigProvider = sonarLintAnalysisConfigProvider;
        }

        protected override async Task<ImmutableArray<Diagnostic>> GetDiagnosticsForDocument(Document document, string projectName)
        {
            var compilation = await document.Project.GetCompilationAsync();
            var analysisConfig = sonarLintAnalysisConfigProvider.Get(compilation, document.Project.AnalyzerOptions);

            var result = await AnalyzeDocument(document.Project,
                analysisConfig.Analyzers,
                analysisConfig.Compilation,
                analysisConfig.AnalyzerOptions,
                document);

            var supportedRules = analysisConfig.AnalyzerRules;
            var resultsWithoutCompilerRules = result.Where(x => supportedRules.Contains(x.Id)).ToImmutableArray();

            return resultsWithoutCompilerRules;
        }
        
        /// <summary>
        /// Copied as-is from https://github.com/OmniSharp/omnisharp-roslyn/blob/v1.39.0/src/OmniSharp.Roslyn.CSharp/Workers/Diagnostics/CSharpDiagnosticWorkerWithAnalyzers.cs#L307
        /// </summary>
        [ExcludeFromCodeCoverage]
        private async Task<ImmutableArray<Diagnostic>> AnalyzeDocument(Project project, ImmutableArray<DiagnosticAnalyzer> allAnalyzers, Compilation compilation, AnalyzerOptions workspaceAnalyzerOptions, Document document)
        {
            try
            {
                // There's real possibility that bug in analyzer causes analysis hang at document.
                var perDocumentTimeout =
                    new CancellationTokenSource(_options.RoslynExtensionsOptions.DocumentAnalysisTimeoutMs);

                var documentSemanticModel = await document.GetSemanticModelAsync(perDocumentTimeout.Token);

                // Analyzers cannot be called with empty analyzer list.
                var canDoFullAnalysis = allAnalyzers.Length > 0
                    && (!_options.RoslynExtensionsOptions.AnalyzeOpenDocumentsOnly
                        || _workspace.IsDocumentOpen(document.Id));
                
                // Only basic syntax check is available if file is miscellanous like orphan .cs file.
                // Those projects are on hard coded virtual project
                if (project.Name == $"{Configuration.OmniSharpMiscProjectName}.csproj")
                {
                    var syntaxTree = await document.GetSyntaxTreeAsync();
                    return syntaxTree.GetDiagnostics().ToImmutableArray();
                }
                else if (canDoFullAnalysis)
                {
                    var compilationWithAnalyzers = compilation.WithAnalyzers(allAnalyzers, new CompilationWithAnalyzersOptions(
                        workspaceAnalyzerOptions,
                        onAnalyzerException: OnAnalyzerException,
                        concurrentAnalysis: false,
                        logAnalyzerExecutionTime: false,
                        reportSuppressedDiagnostics: false));

                    var semanticDiagnosticsWithAnalyzers = await compilationWithAnalyzers
                        .GetAnalyzerSemanticDiagnosticsAsync(documentSemanticModel, filterSpan: null, perDocumentTimeout.Token);

                    var syntaxDiagnosticsWithAnalyzers = await compilationWithAnalyzers
                        .GetAnalyzerSyntaxDiagnosticsAsync(documentSemanticModel.SyntaxTree, perDocumentTimeout.Token);

                    return semanticDiagnosticsWithAnalyzers
                        .Concat(syntaxDiagnosticsWithAnalyzers)
                        .Where(d => !d.IsSuppressed)
                        .Concat(documentSemanticModel.GetDiagnostics())
                        .ToImmutableArray();
                }
                else
                {
                    return documentSemanticModel.GetDiagnostics();
                }
            }
            catch (Exception ex)
            {
                _logger.LogError($"Analysis of document {document.Name} failed or cancelled by timeout: {ex.Message}, analysers: {string.Join(", ", allAnalyzers)}");
                return ImmutableArray<Diagnostic>.Empty;
            }
        }

        /// <summary>
        /// Copied as-is from https://github.com/OmniSharp/omnisharp-roslyn/blob/v1.39.0/src/OmniSharp.Roslyn.CSharp/Workers/Diagnostics/CSharpDiagnosticWorkerWithAnalyzers.cs#L370
        /// </summary>
        [ExcludeFromCodeCoverage]
        private void OnAnalyzerException(Exception ex, DiagnosticAnalyzer analyzer, Diagnostic diagnostic)
        {
            _logger.LogDebug("Exception in diagnostic analyzer." +
                             $"\n            analyzer: {analyzer}" +
                             $"\n            diagnostic: {diagnostic}" +
                             $"\n            exception: {ex.Message}");
        }
    }
}
