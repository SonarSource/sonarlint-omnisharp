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

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Composition;
using System.IO;
using System.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.Rules;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker
{
    internal class AnalysisConfig
    {
        public ImmutableArray<DiagnosticAnalyzer> Analyzers { get; set; }
        public ImmutableHashSet<string> AnalyzerRules { get; set; }
        public Compilation Compilation { get; set; }
        public AnalyzerOptions AnalyzerOptions { get; set; }
    }

    internal interface ISonarLintAnalysisConfigProvider
    {
        /// <summary>
        /// Provide modified data for <see cref="ISonarLintDiagnosticWorker"/>
        /// </summary>
        AnalysisConfig Get(Compilation originalCompilation, AnalyzerOptions originalOptions);
    }

    [Export(typeof(ISonarLintAnalysisConfigProvider)), Shared]
    internal class SonarLintAnalysisConfigProvider : ISonarLintAnalysisConfigProvider
    {
        private readonly IRuleDefinitionsRepository ruleDefinitionsRepository;
        private readonly IRulesToAdditionalTextConverter rulesToAdditionalTextConverter;
        private readonly IRulesToReportDiagnosticsConverter rulesToReportDiagnosticsConverter;

        private readonly ImmutableArray<DiagnosticAnalyzer> analyzers;
        private readonly ImmutableHashSet<string> analyzerRules;

        [ImportingConstructor]
        public SonarLintAnalysisConfigProvider(IRuleDefinitionsRepository ruleDefinitionsRepository,
            ISonarAnalyzerCodeActionProvider sonarAnalyzerCodeActionProvider)
            : this(ruleDefinitionsRepository,
                sonarAnalyzerCodeActionProvider,
                new RulesToAdditionalTextConverter(),
                new RulesToReportDiagnosticsConverter())
        {
        }

        internal SonarLintAnalysisConfigProvider(IRuleDefinitionsRepository ruleDefinitionsRepository,
            ISonarAnalyzerCodeActionProvider sonarAnalyzerCodeActionProvider,
            IRulesToAdditionalTextConverter rulesToAdditionalTextConverter,
            IRulesToReportDiagnosticsConverter rulesToReportDiagnosticsConverter)
        {
            this.ruleDefinitionsRepository = ruleDefinitionsRepository;
            this.rulesToAdditionalTextConverter = rulesToAdditionalTextConverter;
            this.rulesToReportDiagnosticsConverter = rulesToReportDiagnosticsConverter;

            // performance optimization: cache the analyzers' rule descriptors
            analyzers = sonarAnalyzerCodeActionProvider.CodeDiagnosticAnalyzerProviders;
            analyzerRules = analyzers
                .SelectMany(x => x.SupportedDiagnostics)
                .Select(x => x.Id)
                .ToImmutableHashSet();
        }

        public AnalysisConfig Get(Compilation originalCompilation, AnalyzerOptions originalOptions)
        {
            var rules = ruleDefinitionsRepository.RuleDefinitions;

            return new AnalysisConfig
            {
                Compilation = GetWithSonarLintRuleSeverities(originalCompilation, rules),
                AnalyzerOptions = GetWithSonarLintAdditionalFiles(originalOptions, rules),
                Analyzers = analyzers,
                AnalyzerRules = analyzerRules,
            };
        }

        /// <summary>
        /// Update sonar-dotnet analyzers rule severities.
        /// </summary>
        private Compilation GetWithSonarLintRuleSeverities(Compilation compilation, IEnumerable<RuleDefinition> rules)
        {
            var ruleSeverities = rulesToReportDiagnosticsConverter.Convert(rules);
            var updatedCompilationOptions = compilation.Options.WithSpecificDiagnosticOptions(ruleSeverities);

            return compilation.WithOptions(updatedCompilationOptions);
        }

        /// <summary>
        /// Add sonar-dotnet analyzer additional files.
        /// Override any existing sonar-dotnet analyzer additional files that were already in the project.
        /// </summary>
        private AnalyzerOptions GetWithSonarLintAdditionalFiles(AnalyzerOptions workspaceAnalyzerOptions, IEnumerable<RuleDefinition> rules)
        {
            var sonarLintAdditionalFile = rulesToAdditionalTextConverter.Convert(rules);
            var sonarLintAdditionalFileName = Path.GetFileName(sonarLintAdditionalFile.Path);

            var additionalFiles = workspaceAnalyzerOptions.AdditionalFiles;
            var builder = ImmutableArray.CreateBuilder<AdditionalText>();
            builder.AddRange(additionalFiles.Where(x => !IsSonarLintAdditionalFile(x)));
            builder.Add(sonarLintAdditionalFile);

            var modifiedAdditionalFiles = builder.ToImmutable();
            var finalAnalyzerOptions = new AnalyzerOptions(modifiedAdditionalFiles, workspaceAnalyzerOptions.AnalyzerConfigOptionsProvider);

            return finalAnalyzerOptions;

            bool IsSonarLintAdditionalFile(AdditionalText existingAdditionalFile)
            {
                return Path.GetFileName(existingAdditionalFile.Path).Equals(
                    sonarLintAdditionalFileName,
                    StringComparison.OrdinalIgnoreCase);
            }
        }
    }
}
