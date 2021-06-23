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
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.OmniSharp.Plugin.Rules;
using SonarLint.VisualStudio.Integration.UnitTests;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintAnalysisConfigProviderTests
    {
        [TestMethod, Ignore]
        public void MefCtor_CheckIsExported()
        {
            // MefTestHelpers.CheckTypeCanBeImported<SonarLintAnalysisConfigProvider, ISonarLintAnalysisConfigProvider>(null, new []
            // {
            //     MefTestHelpers.CreateExport<IRuleDefinitionsRepository>(Mock.Of<IRuleDefinitionsRepository>()),
            //     MefTestHelpers.CreateExport<ISonarAnalyzerCodeActionProvider>(CreateSonarCodeActionProvider(ImmutableArray<DiagnosticAnalyzer>.Empty))
            // });
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

            analysisConfig.AnalyzerOptions.AdditionalFiles.Should().BeEquivalentTo(additionalFile);
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
            additionalFiles.Should().BeEquivalentTo(existingUnrelatedAdditionalFile, newAdditionalFileWithSameName);
            additionalFiles[0].GetText().ToString().Should().Be("some content2");
            additionalFiles[1].GetText().ToString().Should().Be("some new content");
        }

        private Compilation CreateCompilation(Dictionary<string, ReportDiagnostic> existingRuleSeverities = null)
        {
            var compilation = CSharpCompilation.Create(null);

            return existingRuleSeverities == null
                ? compilation
                : compilation.WithOptions(compilation.Options.WithSpecificDiagnosticOptions(existingRuleSeverities));
        }

        private AnalyzerOptions CreateOptions(params AdditionalText[] existingAdditionalFiles)
        {
            return new AnalyzerOptions(existingAdditionalFiles.ToImmutableArray());
        }

        private SonarLintAnalysisConfigProvider CreateTestSubject(
            RuleDefinition[] rules = null,
            DiagnosticAnalyzer[] analyzers = null,
            Dictionary<string, ReportDiagnostic> ruleSeverities = null,
            AdditionalText additionalFile = null)
        {
            rules ??= Array.Empty<RuleDefinition>();
            analyzers ??= Array.Empty<DiagnosticAnalyzer>();
            ruleSeverities ??= new Dictionary<string, ReportDiagnostic>();
            additionalFile ??= new RulesToAdditionalTextConverter.AdditionalTextImpl("some file", "some content");
            
            var ruleDefinitionsRepository = CreateRuleDefinitionsRepository(rules);
            var rulesToReportDiagnosticsConverter = CreateRulesToReportDiagnosticsConverter(rules, ruleSeverities);
            var rulesToAdditionalTextConverter = CreateRulesToAdditionalTextConverter(rules, additionalFile);
            var sonarCodeActionProvider = CreateSonarCodeActionProvider(analyzers.ToImmutableArray());

            return new SonarLintAnalysisConfigProvider(ruleDefinitionsRepository,
                sonarCodeActionProvider,
                rulesToAdditionalTextConverter,
                rulesToReportDiagnosticsConverter);
        }
        
        private static ISonarAnalyzerCodeActionProvider CreateSonarCodeActionProvider(ImmutableArray<DiagnosticAnalyzer> sonarAnalyzers)
        {
            var sonarCodeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            
            sonarCodeActionProvider
                .Setup(x => x.CodeDiagnosticAnalyzerProviders)
                .Returns(sonarAnalyzers);
            
            return sonarCodeActionProvider.Object;
        }
        
        private static IRuleDefinitionsRepository CreateRuleDefinitionsRepository(RuleDefinition[] rules)
        {
            var ruleDefinitionsRepository = new Mock<IRuleDefinitionsRepository>();
            ruleDefinitionsRepository.Setup(x => x.RuleDefinitions).Returns(rules);
                
            return ruleDefinitionsRepository.Object;
        }
        
        private static IRulesToReportDiagnosticsConverter CreateRulesToReportDiagnosticsConverter(
            RuleDefinition[] rules, 
            Dictionary<string, ReportDiagnostic> ruleSeverities)
        {
            var rulesToReportDiagnosticsConverter = new Mock<IRulesToReportDiagnosticsConverter>();
            
            rulesToReportDiagnosticsConverter
                .Setup(x => x.Convert(rules))
                .Returns(ruleSeverities);
            
            return rulesToReportDiagnosticsConverter.Object;
        }
        
        private IRulesToAdditionalTextConverter CreateRulesToAdditionalTextConverter(RuleDefinition[] rules, AdditionalText additionalText)
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
                new DiagnosticDescriptor("id1", "title1","message1","category1",DiagnosticSeverity.Error, true);
            
            public static DiagnosticDescriptor Descriptor2 = 
                new DiagnosticDescriptor("id2", "title2","message2","category2",DiagnosticSeverity.Warning, false);
            
            public override void Initialize(AnalysisContext context)
            {
            }

            public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics { get; } =
                new[] {Descriptor1, Descriptor2}.ToImmutableArray();
        }
        
        #endregion
    }
}
