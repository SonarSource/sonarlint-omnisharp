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
    public class SonarLintDiagnosticWorkerDataProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            MefTestHelpers.CheckTypeCanBeImported<SonarLintDiagnosticWorkerDataProvider, ISonarLintDiagnosticWorkerDataProvider>(null, new []
            {
                MefTestHelpers.CreateExport<IRuleDefinitionsRepository>(Mock.Of<IRuleDefinitionsRepository>()),
                MefTestHelpers.CreateExport<ISonarAnalyzerCodeActionProvider>(Mock.Of<ISonarAnalyzerCodeActionProvider>())
            });
        }

        [TestMethod]
        public void Get_ReturnsSonarAnalyzers()
        {
            var sonarAnalyzers = new DiagnosticAnalyzer[] {new DummyAnalyzer()}.ToImmutableArray();

            var testSubject = CreateTestSubject(sonarAnalyzers: sonarAnalyzers);
            var diagnosticWorkerModifications = testSubject.Get(CreateCompilation(), CreateOptions());

            diagnosticWorkerModifications.Analyzers.Should().BeEquivalentTo(sonarAnalyzers);
        }

        [TestMethod]
        public void Get_NoExistingSonarRuleSeverities_ReturnsSonarRuleSeverities()
        {
            var ruleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Error},
                {"rule2", ReportDiagnostic.Warn},
                {"rule3", ReportDiagnostic.Info},
                {"rule4", ReportDiagnostic.Suppress},
            };
            
            var testSubject = CreateTestSubject(ruleSeverities: ruleSeverities);
            
            var diagnosticWorkerModifications = testSubject.Get(CreateCompilation(), CreateOptions());

            var severities = diagnosticWorkerModifications.Compilation.Options.SpecificDiagnosticOptions;
            severities.Should().BeEquivalentTo(ruleSeverities);
        }
        
        [TestMethod]
        public void Get_HasExistingSonarRuleSeverities_OverridesSonarRuleSeverities()
        {
            var existingRuleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Error},
                {"rule2", ReportDiagnostic.Info},
                {"rule3", ReportDiagnostic.Warn}
            };
            
            var existingCompilation = CreateCompilation(existingRuleSeverities);
            
            var sonarRuleSeverities = new Dictionary<string, ReportDiagnostic>
            {
                {"rule1", ReportDiagnostic.Suppress},
                {"rule2", ReportDiagnostic.Info}
            };
            
            var testSubject = CreateTestSubject(ruleSeverities: sonarRuleSeverities);
            var diagnosticWorkerModifications = testSubject.Get(existingCompilation, CreateOptions());

            var severities = diagnosticWorkerModifications.Compilation.Options.SpecificDiagnosticOptions;
            severities.Should().BeEquivalentTo(sonarRuleSeverities);
        }
        
        [TestMethod]
        public void Get_NoExistingSonarAdditionalFile_AddsSonarAdditionalFile()
        {
            var additionalText = new RulesToAdditionalTextConverter.AdditionalTextImpl("some path", "some content");
            var testSubject = CreateTestSubject(additionalFile: additionalText);
            
            var diagnosticWorkerModifications = testSubject.Get(CreateCompilation(), CreateOptions());

            diagnosticWorkerModifications.AnalyzerOptions.AdditionalFiles.Should().BeEquivalentTo(additionalText);
        }
        
        [TestMethod]
        public void Get_HasExistingSonarAdditionalFile_OverridesSonarAdditionalFile()
        {
            var existingSonarAdditionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("a/test/sonar.xml", "some content1");
            var existingUnrelatedAdditionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("asonar.xml", "some content2");
            var existingOptions = CreateOptions(existingSonarAdditionalFile, existingUnrelatedAdditionalFile);
            
            var sonarAdditionalFile = new RulesToAdditionalTextConverter.AdditionalTextImpl("b/sonar.xml", "some new content");
            var testSubject = CreateTestSubject(additionalFile: sonarAdditionalFile);
            
            var diagnosticWorkerModifications = testSubject.Get(CreateCompilation(), existingOptions);
            
            diagnosticWorkerModifications.AnalyzerOptions.AdditionalFiles.Should().BeEquivalentTo(sonarAdditionalFile, existingUnrelatedAdditionalFile);
            diagnosticWorkerModifications.AnalyzerOptions.AdditionalFiles[0].GetText().ToString().Should().Be("some content2");
            diagnosticWorkerModifications.AnalyzerOptions.AdditionalFiles[1].GetText().ToString().Should().Be("some new content");
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

        private SonarLintDiagnosticWorkerDataProvider CreateTestSubject(
            RuleDefinition[] rules = null,
            ImmutableArray<DiagnosticAnalyzer> sonarAnalyzers = default,
            Dictionary<string, ReportDiagnostic> ruleSeverities = null,
            AdditionalText additionalFile = null)
        {
            rules ??= Array.Empty<RuleDefinition>();
            ruleSeverities ??= new Dictionary<string, ReportDiagnostic>();
            additionalFile ??= new RulesToAdditionalTextConverter.AdditionalTextImpl("some file", "some content");
            
            var ruleDefinitionsRepository = CreateRuleDefinitionsRepository(rules);
            var rulesToReportDiagnosticsConverter = CreateRulesToReportDiagnosticsConverter(rules, ruleSeverities);
            var rulesToAdditionalTextConverter = CreateRulesToAdditionalTextConverter(rules, additionalFile);
            var sonarCodeActionProvider = CreateSonarCodeActionProvider(sonarAnalyzers);

            return new SonarLintDiagnosticWorkerDataProvider(ruleDefinitionsRepository,
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
            public override void Initialize(AnalysisContext context)
            {
            }

            public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics { get; }
        }
        
        #endregion
    }
}