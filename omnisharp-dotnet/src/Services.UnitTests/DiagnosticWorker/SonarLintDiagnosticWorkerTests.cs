/*
 * SonarOmnisharp
 * Copyright (C) 2021-2022 SonarSource SA
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
using System.Threading;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp;
using OmniSharp.Eventing;
using OmniSharp.Options;
using OmniSharp.Roslyn;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.OmniSharpWorkspaceHelper;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintDiagnosticWorkerTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<SonarLintDiagnosticWorker, ISonarLintDiagnosticWorker>(
                CreateExport<ISonarLintAnalysisConfigProvider>(),
                CreateExport<OmniSharpWorkspace>(CreateOmniSharpWorkspace()),
                CreateExport<ILoggerFactory>(),
                CreateExport<DiagnosticEventForwarder>(new DiagnosticEventForwarder(Mock.Of<IEventEmitter>())),
                CreateExport<IOptionsMonitor<OmniSharpOptions>>(CreateOptionsMonitor()),
                CreateExport<IDiagnosticQuickFixesProvider>());
        }

        [TestMethod]
        public async Task AnalyzeDocument_AnalyzersAreOverriden()
        {
            var getAnalyzers = new Mock<Func<IEnumerable<DiagnosticAnalyzer>>>();
            getAnalyzers
                .SetupSequence(x => x())
                .Returns(new[] { new TestAnalyzer() })
                .Returns(Enumerable.Empty<DiagnosticAnalyzer>());

            var analysisConfigProvider = CreateAnalysisConfigProvider(getAnalyzers: getAnalyzers.Object);
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, analysisConfigProvider.Object);
            var document = workspace.GetDocument("dummyFile.cs");

            // first call has an analyzer --> should report a diagnostic
            var result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.FirstOrDefault(x => x.Id == TestAnalyzer.Descriptor.Id).Should().NotBeNull();

            // second call has no analyzer --> should not report
            result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.Should().BeEmpty();
        }

        [TestMethod]
        public async Task AnalyzeDocument_CompilationIsOverriden()
        {
            var isFirstCall = true;
            Compilation ModifyCompilation(Compilation originalCompilation)
            {
                if (isFirstCall)
                {
                    isFirstCall = false;
                    return originalCompilation;
                }
                return originalCompilation.WithOptions(originalCompilation.Options.WithGeneralDiagnosticOption(ReportDiagnostic.Suppress));
            }

            var analysisConfigProvider = CreateAnalysisConfigProvider(modifyCompilation: ModifyCompilation);
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, analysisConfigProvider.Object);
            var document = workspace.GetDocument("dummyFile.cs");

            // first call has the original analyzer's severity (warning) --> should report a diagnostic
            var result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.FirstOrDefault(x => x.Id == TestAnalyzer.Descriptor.Id).Should().NotBeNull();

            // second call has global severity set to Suppress --> should not report
            result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.Should().BeEmpty();
        }

        [TestMethod]
        public async Task AnalyzeDocument_AnalyzerOptionsAreOverriden()
        {
            var additionalFile = (AdditionalText)new RulesToAdditionalTextConverter.AdditionalTextImpl(TestAnalyzer.Descriptor.Id, string.Empty);

            var isFirstCall = true;
            AnalyzerOptions ModifyOptions(AnalyzerOptions originalOptions)
            {
                if (isFirstCall)
                {
                    isFirstCall = false;
                    return originalOptions;
                }
                return originalOptions.WithAdditionalFiles(new[] { additionalFile }.ToImmutableArray());
            }

            var analyzer = new TestAnalyzer();
            var analysisConfigProvider = CreateAnalysisConfigProvider(
                modifyOptions: ModifyOptions,
                getAnalyzers: () => new[] { analyzer });

            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, analysisConfigProvider.Object);
            var document = workspace.GetDocument("dummyFile.cs");

            // first call should have no additional files
            var result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.FirstOrDefault(x => x.Id == TestAnalyzer.Descriptor.Id).Should().NotBeNull();
            analyzer.SuppliedAdditionalFiles.Should().BeEmpty();

            // second call should have an additional file
            result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.FirstOrDefault(x => x.Id == TestAnalyzer.Descriptor.Id).Should().NotBeNull();
            analyzer.SuppliedAdditionalFiles.Should().BeEquivalentTo(new[] { additionalFile });
        }

        [TestMethod]
        public async Task AnalyzeDocument_CompilerWarningsAndErrorsAreIgnored()
        {
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "invalid code");
            var testSubject = CreateTestSubject(workspace);
            var document = workspace.GetDocument("dummyFile.cs");

            var result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.Should().BeEmpty();
        }

        private static Mock<ISonarLintAnalysisConfigProvider> CreateAnalysisConfigProvider(
            Func<IEnumerable<DiagnosticAnalyzer>> getAnalyzers = null,
            Func<Compilation, Compilation> modifyCompilation = null,
            Func<AnalyzerOptions, AnalyzerOptions> modifyOptions = null)
        {
            getAnalyzers ??= () => new[] { new TestAnalyzer() };
            modifyCompilation ??= originalCompilation => originalCompilation;
            modifyOptions ??= originalOptions => originalOptions;

            Compilation compilation = null;
            AnalyzerOptions options = null;

            var analysisConfigProvider = new Mock<ISonarLintAnalysisConfigProvider>();
            analysisConfigProvider
                .Setup(x => x.Get(It.IsAny<Compilation>(), It.IsAny<AnalyzerOptions>()))
                .Callback((Compilation originalCompilation, AnalyzerOptions originalOptions) =>
                {
                    compilation = originalCompilation;
                    options = originalOptions;
                })
                .Returns(() =>
                {
                    var analyzers = getAnalyzers().ToImmutableArray();
                    var analyzerRules = analyzers.SelectMany(x => x.SupportedDiagnostics)
                        .Select(x => x.Id)
                        .ToImmutableHashSet();

                    return new AnalysisConfig
                    {
                        Analyzers = analyzers,
                        AnalyzerRules = analyzerRules,
                        Compilation = modifyCompilation(compilation),
                        AnalyzerOptions = modifyOptions(options)
                    };
                });

            return analysisConfigProvider;
        }

        private static IOptionsMonitor<OmniSharpOptions> CreateOptionsMonitor()
        {
            var optionsMonitor = new Mock<IOptionsMonitor<OmniSharpOptions>>();
            optionsMonitor.Setup(x => x.CurrentValue).Returns(new OmniSharpOptions());

            return optionsMonitor.Object;
        }

        private static SonarLintDiagnosticWorker CreateTestSubject(OmniSharpWorkspace workspace, ISonarLintAnalysisConfigProvider analysisConfigProvider = null) =>
            new(analysisConfigProvider ?? CreateAnalysisConfigProvider().Object,
                workspace,
                Mock.Of<ILoggerFactory>(),
                new DiagnosticEventForwarder(Mock.Of<IEventEmitter>()),
                CreateOptionsMonitor(),
                null);

        #region Helper Classes

        [DiagnosticAnalyzer(LanguageNames.CSharp)]
        private class TestAnalyzer : DiagnosticAnalyzer
        {
            public ImmutableArray<AdditionalText> SuppliedAdditionalFiles { get; set; }

            public override void Initialize(AnalysisContext context)
            {
                context.ConfigureGeneratedCodeAnalysis(GeneratedCodeAnalysisFlags.Analyze | GeneratedCodeAnalysisFlags.ReportDiagnostics);
                context.EnableConcurrentExecution();
                context.RegisterSymbolAction(AnalyzeSymbol, SymbolKind.NamedType);
            }

            private void AnalyzeSymbol(SymbolAnalysisContext context)
            {
                SuppliedAdditionalFiles = context.Options.AdditionalFiles;

                var namedTypeSymbol = (INamedTypeSymbol)context.Symbol;

                if (namedTypeSymbol.Name == "SonarLint_TestAnalyzer_Raise")
                {
                    context.ReportDiagnostic(Diagnostic.Create(
                        Descriptor,
                        namedTypeSymbol.Locations[0],
                        namedTypeSymbol.Name
                    ));
                }
            }

            public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics => ImmutableArray.Create(Descriptor);

            public static readonly DiagnosticDescriptor Descriptor = new(
                "SonarLintTest",
                "Title",
                "Message",
                "Category",
                defaultSeverity: DiagnosticSeverity.Warning,
                isEnabledByDefault: true,
                description: "Description",
                helpLinkUri: "HelpLink",
                customTags: new[] { "CustomTag" });
        }

        #endregion
    }
}
