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

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintAnalysisConfigProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<SonarLintAnalysisConfigProvider, ISonarLintAnalysisConfigProvider>(
                 CreateExport<IActiveRuleDefinitionsRepository>(),
                 CreateExport<ISonarAnalyzerCodeActionProvider>(CreateSonarCodeActionProvider(ImmutableArray<DiagnosticAnalyzer>.Empty)),
                 CreateExport<IRulesToReportDiagnosticsConverter>());
        }

        [TestMethod]
        public void Get_ReturnsCorrectAnalyzers()
        {
            var analyzers = new DiagnosticAnalyzer[] {new DummyAnalyzer()};

            var testSubject = CreateTestSubject(analyzers: analyzers);
            var analysisConfig = testSubject.Get(CreateCompilation(), CreateOptions());

            analysisConfig.Analyzers.Should().BeEquivalentTo(analyzers);
        }

        [TestMethod]
        public void Get_ReturnsCorrectAnalyzerRules()
        {
            var analyzers = new DiagnosticAnalyzer[] {new DummyAnalyzer()};

            var testSubject = CreateTestSubject(analyzers: analyzers);
            var analysisConfig = testSubject.Get(CreateCompilation(), CreateOptions());

            analysisConfig.AnalyzerRules.Should().BeEquivalentTo(DummyAnalyzer.Descriptor1.Id, DummyAnalyzer.Descriptor2.Id);
        }

        [TestMethod]
        public void Get_NoExistingRuleSeverities_ReturnsCorrectRuleSeverities()
        {
            var ruleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Error},
                {"rule2", ReportDiagnostic.Warn},
                {"rule3", ReportDiagnostic.Info},
                {"rule4", ReportDiagnostic.Suppress},
            };

            var testSubject = CreateTestSubject(ruleSeverities: ruleSeverities);

            var analysisConfig = testSubject.Get(CreateCompilation(), CreateOptions());

            var severities = analysisConfig.Compilation.Options.SpecificDiagnosticOptions;
            severities.Should().BeEquivalentTo(ruleSeverities);
        }

        [TestMethod]
        public void Get_HasExistingRuleSeverities_OverridesRuleSeverities()
        {
            var existingRuleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Error},
                {"rule2", ReportDiagnostic.Info},
                {"rule3", ReportDiagnostic.Warn}
            };

            var existingCompilation = CreateCompilation(existingRuleSeverities);

            var newRuleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Suppress},
                {"rule2", ReportDiagnostic.Info}
            };

            var testSubject = CreateTestSubject(ruleSeverities: newRuleSeverities);
            var analysisConfig = testSubject.Get(existingCompilation, CreateOptions());

            var severities = analysisConfig.Compilation.Options.SpecificDiagnosticOptions;
            severities.Should().BeEquivalentTo(newRuleSeverities);
        }

        [TestMethod]
        public void Get_NoExistingAdditionalFiles_AddsAdditionalFile()
        {
            var additionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("some path", "some content");
            var testSubject = CreateTestSubject(additionalFile: additionalFile);

            var analysisConfig = testSubject.Get(CreateCompilation(), CreateOptions());

            analysisConfig.AnalyzerOptions.AdditionalFiles.Should().BeEquivalentTo(new[] { additionalFile });
        }

        [TestMethod]
        public void Get_HasExistingAdditionalFiles_OverridesAdditionalFileWithSameName()
        {
            var existingAdditionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("a/test/sonar.xml", "some content1");
            var existingUnrelatedAdditionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("asonar.xml", "some content2");
            var existingOptions = CreateOptions(existingAdditionalFile, existingUnrelatedAdditionalFile);

            var newAdditionalFileWithSameName = new RulesToAdditionalTextConverter.AdditionalTextImpl("b/sonar.xml", "some new content");
            var testSubject = CreateTestSubject(additionalFile: newAdditionalFileWithSameName);

            var analysisConfig = testSubject.Get(CreateCompilation(), existingOptions);

            var additionalFiles = analysisConfig.AnalyzerOptions.AdditionalFiles;
            additionalFiles.Should().BeEquivalentTo(new[] { existingUnrelatedAdditionalFile, newAdditionalFileWithSameName });
            additionalFiles[0].GetText().ToString().Should().Be("some content2");
            additionalFiles[1].GetText().ToString().Should().Be("some new content");
        }

        private static Compilation CreateCompilation(Dictionary<string, ReportDiagnostic> existingRuleSeverities = null)
        {
            var compilation = CSharpCompilation.Create(null);

            return existingRuleSeverities == null
                ? compilation
                : compilation.WithOptions(compilation.Options.WithSpecificDiagnosticOptions(existingRuleSeverities));
        }

        private static AnalyzerOptions CreateOptions(params AdditionalText[] existingAdditionalFiles)
        {
            return new AnalyzerOptions(existingAdditionalFiles.ToImmutableArray());
        }

        private static SonarLintAnalysisConfigProvider CreateTestSubject(
            ActiveRuleDefinition[] activeRules = null,
            DiagnosticAnalyzer[] analyzers = null,
            Dictionary<string, ReportDiagnostic> ruleSeverities = null,
            AdditionalText additionalFile = null)
        {
            activeRules ??= new[] {new ActiveRuleDefinition {RuleId = "1"}, new ActiveRuleDefinition {RuleId = "2"}};
            analyzers ??= new DiagnosticAnalyzer[] {new DummyAnalyzer()};
            ruleSeverities ??= new Dictionary<string, ReportDiagnostic>();
            additionalFile ??= new RulesToAdditionalTextConverter.AdditionalTextImpl("some file", "some content");

            var ruleDefinitionsRepository = CreateRuleDefinitionsRepository(activeRules);
            var rulesToReportDiagnosticsConverter = CreateRulesToReportDiagnosticsConverter(activeRules, analyzers, ruleSeverities);
            var rulesToAdditionalTextConverter = CreateRulesToAdditionalTextConverter(activeRules, additionalFile);
            var sonarCodeActionProvider = CreateSonarCodeActionProvider(analyzers.ToImmutableArray());

            return new SonarLintAnalysisConfigProvider(ruleDefinitionsRepository,
                sonarCodeActionProvider,
                rulesToReportDiagnosticsConverter,
                rulesToAdditionalTextConverter);
        }

        private static ISonarAnalyzerCodeActionProvider CreateSonarCodeActionProvider(ImmutableArray<DiagnosticAnalyzer> sonarAnalyzers)
        {
            var sonarCodeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();

            sonarCodeActionProvider
                .Setup(x => x.CodeDiagnosticAnalyzerProviders)
                .Returns(sonarAnalyzers);

            return sonarCodeActionProvider.Object;
        }

        private static IActiveRuleDefinitionsRepository CreateRuleDefinitionsRepository(ActiveRuleDefinition[] rules)
        {
            var ruleDefinitionsRepository = new Mock<IActiveRuleDefinitionsRepository>();
            ruleDefinitionsRepository.Setup(x => x.ActiveRules).Returns(rules);

            return ruleDefinitionsRepository.Object;
        }

        private static IRulesToReportDiagnosticsConverter CreateRulesToReportDiagnosticsConverter(
            ActiveRuleDefinition[] activeRules,
            DiagnosticAnalyzer[] diagnosticAnalyzers,
            Dictionary<string, ReportDiagnostic> ruleSeverities)
        {
            var rulesToReportDiagnosticsConverter = new Mock<IRulesToReportDiagnosticsConverter>();
            var activeRuleIds = activeRules.Select(x => x.RuleId).ToImmutableHashSet();
            var analyzerRuleIds = diagnosticAnalyzers
                .SelectMany(x => x.SupportedDiagnostics)
                .Select(x => x.Id)
                .ToImmutableHashSet();

            rulesToReportDiagnosticsConverter
                .Setup(x => x.Convert(activeRuleIds, analyzerRuleIds))
                .Returns(ruleSeverities);

            return rulesToReportDiagnosticsConverter.Object;
        }

        private static IRulesToAdditionalTextConverter CreateRulesToAdditionalTextConverter(ActiveRuleDefinition[] rules, AdditionalText additionalText)
        {
            var rulesToAdditionalTextConverter = new Mock<IRulesToAdditionalTextConverter>();

            rulesToAdditionalTextConverter
                .Setup(x => x.Convert(rules))
                .Returns(additionalText);

            return rulesToAdditionalTextConverter.Object;
        }

        #region Helper Classes

        private class DummyAnalyzer : DiagnosticAnalyzer
        {
            public static DiagnosticDescriptor Descriptor1 =
                new("id1", "title1","message1","category1",DiagnosticSeverity.Error, true);

            public static DiagnosticDescriptor Descriptor2 =
                new("id2", "title2","message2","category2",DiagnosticSeverity.Warning, false);

            public override void Initialize(AnalysisContext context)
            {
            }

            public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics { get; } =
                new[] {Descriptor1, Descriptor2}.ToImmutableArray();
        }

        #endregion
    }
}
