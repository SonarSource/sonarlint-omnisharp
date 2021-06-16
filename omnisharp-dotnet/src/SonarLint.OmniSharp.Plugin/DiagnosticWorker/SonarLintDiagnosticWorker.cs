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
using System.ComponentModel.Composition;
using System.IO;
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
using SonarLint.OmniSharp.Plugin.Rules;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker
{
    interface ISonarLintDiagnosticWorker : ICsDiagnosticWorker
    {
    }

    [Export(typeof(ISonarLintDiagnosticWorker))]
    [PartCreationPolicy(CreationPolicy.Shared)]
    internal class SonarLintDiagnosticWorker : CopiedCSharpDiagnosticWorkerWithAnalyzers, ISonarLintDiagnosticWorker
    {
        private readonly IRuleDefinitionsRepository ruleDefinitionsRepository;
        private readonly IRulesToAdditionalTextConverter rulesToAdditionalTextConverter;
        private readonly IRulesToReportDiagnosticsConverter rulesToReportDiagnosticsConverter;
        private readonly ISonarLintCodeActionProvider sonarLintCodeActionProvider;

        [ImportingConstructor]
        public SonarLintDiagnosticWorker(IRuleDefinitionsRepository ruleDefinitionsRepository,
            OmniSharpWorkspace workspace,
            ISonarLintCodeActionProvider provider,
            ILoggerFactory loggerFactory,
            DiagnosticEventForwarder forwarder,
            IOptionsMonitor<OmniSharpOptions> options)
            : this(ruleDefinitionsRepository,
                new RulesToAdditionalTextConverter(),
                new RulesToReportDiagnosticsConverter(),
                workspace,
                provider,
                loggerFactory,
                forwarder,
                options)
        {
        }

        internal SonarLintDiagnosticWorker(IRuleDefinitionsRepository ruleDefinitionsRepository,
            IRulesToAdditionalTextConverter rulesToAdditionalTextConverter,
            IRulesToReportDiagnosticsConverter rulesToReportDiagnosticsConverter,
            OmniSharpWorkspace workspace,
            ISonarLintCodeActionProvider sonarLintCodeActionProvider,
            ILoggerFactory loggerFactory,
            DiagnosticEventForwarder forwarder,
            IOptionsMonitor<OmniSharpOptions> options)
            : base(workspace, new[] { sonarLintCodeActionProvider }, loggerFactory, forwarder, options.CurrentValue)
        {
            this.ruleDefinitionsRepository = ruleDefinitionsRepository;
            this.rulesToAdditionalTextConverter = rulesToAdditionalTextConverter;
            this.rulesToReportDiagnosticsConverter = rulesToReportDiagnosticsConverter;
            this.sonarLintCodeActionProvider = sonarLintCodeActionProvider;
        }

        protected override Task<ImmutableArray<Diagnostic>> AnalyzeDocument(Project project,
            ImmutableArray<DiagnosticAnalyzer> allAnalyzers, Compilation compilation,
            AnalyzerOptions workspaceAnalyzerOptions, Document document)
        {
            allAnalyzers = ForceOnlySonarLintAnalyzers();
            compilation = ForceSonarLintRuleSeverities(compilation);
            workspaceAnalyzerOptions = ForceSonarLintAdditionalFiles(workspaceAnalyzerOptions);

            return base.AnalyzeDocument(project, allAnalyzers, compilation, workspaceAnalyzerOptions, document);
        }

        /// <summary>
        ///  Ignore any other analyzers specified in the project and run only sonar-dotnet analyzers.
        /// </summary>
        private ImmutableArray<DiagnosticAnalyzer> ForceOnlySonarLintAnalyzers() =>
            sonarLintCodeActionProvider.CodeDiagnosticAnalyzerProviders;
        
        /// <summary>
        /// Update sonar-dotnet analyzers rule severities.
        /// </summary>
        private Compilation ForceSonarLintRuleSeverities(Compilation compilation)
        {
            var sonarLintRules = rulesToReportDiagnosticsConverter.Convert(ruleDefinitionsRepository.RuleDefinitions);
            var updatedCompilationOptions = compilation.Options.WithSpecificDiagnosticOptions(sonarLintRules);
            
            return compilation.WithOptions(updatedCompilationOptions);
        }
        
        /// <summary>
        /// Add sonar-dotnet analyzer additional files.
        /// Override any existing sonar-dotnet analyzer additional files that were already in the project.
        /// </summary>
        private AnalyzerOptions ForceSonarLintAdditionalFiles(AnalyzerOptions workspaceAnalyzerOptions)
        {
            var sonarLintAdditionalFile = rulesToAdditionalTextConverter.Convert(ruleDefinitionsRepository.RuleDefinitions);
            var sonarLintAdditionalFileName = Path.GetFileName(sonarLintAdditionalFile.Path);
            
            var additionalFiles = workspaceAnalyzerOptions.AdditionalFiles;
            var builder = ImmutableArray.CreateBuilder<AdditionalText>();
            builder.AddRange(additionalFiles.Where(x => !x.Path.EndsWith(sonarLintAdditionalFileName)));
            builder.Add(sonarLintAdditionalFile);
            
            var modifiedAdditionalFiles = builder.ToImmutable();
            var finalAnalyzerOptions = new AnalyzerOptions(modifiedAdditionalFiles, workspaceAnalyzerOptions.AnalyzerConfigOptionsProvider);
            
            return finalAnalyzerOptions;
        }
    }
}