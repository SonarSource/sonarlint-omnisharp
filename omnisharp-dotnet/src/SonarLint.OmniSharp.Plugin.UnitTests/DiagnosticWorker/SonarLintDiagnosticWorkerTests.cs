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
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.CodeAnalysis.Text;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp;
using OmniSharp.Eventing;
using OmniSharp.FileWatching;
using OmniSharp.Options;
using OmniSharp.Roslyn;
using OmniSharp.Services;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.OmniSharp.Plugin.Rules;
using SonarLint.VisualStudio.Integration.UnitTests;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintDiagnosticWorkerTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            MefTestHelpers.CheckTypeCanBeImported<SonarLintDiagnosticWorker, ISonarLintDiagnosticWorker>(null, new []
            {
                MefTestHelpers.CreateExport<ISonarLintDiagnosticWorkerDataProvider>(Mock.Of<ISonarLintDiagnosticWorkerDataProvider>()),
                MefTestHelpers.CreateExport<OmniSharpWorkspace>(CreateOmniSharpWorkspace()),
                MefTestHelpers.CreateExport<ISonarAnalyzerCodeActionProvider>(Mock.Of<ISonarAnalyzerCodeActionProvider>()),
                MefTestHelpers.CreateExport<ILoggerFactory>(Mock.Of<ILoggerFactory>()),
                MefTestHelpers.CreateExport<DiagnosticEventForwarder>(new DiagnosticEventForwarder(Mock.Of<IEventEmitter>())),
                MefTestHelpers.CreateExport<IOptionsMonitor<OmniSharpOptions>>(CreateOptionsMonitor())
            });
        }
        
        [TestMethod]
        public async Task AnalyzeDocument_AnalyzersAreOverriden()
        {
            var getAnalyzers = new Mock<Func<IEnumerable<DiagnosticAnalyzer>>>();
            getAnalyzers
                .SetupSequence(x => x())
                .Returns(new []{new TestAnalyzer()})
                .Returns(Enumerable.Empty<DiagnosticAnalyzer>());

            var diagnosticWorkerDataProvider = CreateDiagnosticWorkerDataProvider(getAnalyzers: getAnalyzers.Object);
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, diagnosticWorkerDataProvider.Object);
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

            var diagnosticWorkerDataProvider = CreateDiagnosticWorkerDataProvider(modifyCompilation: ModifyCompilation);
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, diagnosticWorkerDataProvider.Object);
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
            var isFirstCall = true;
            AnalyzerOptions ModifyOptions(AnalyzerOptions originalOptions)
            {
                if (isFirstCall)
                {
                    isFirstCall = false;
                    return originalOptions;
                }
                
                var additionalFile = (AdditionalText)new RulesToAdditionalTextConverter.AdditionalTextImpl(TestAnalyzer.Descriptor.Id, string.Empty);
                return originalOptions.WithAdditionalFiles(new []{additionalFile}.ToImmutableArray());
            }
            
            var diagnosticWorkerDataProvider = CreateDiagnosticWorkerDataProvider(modifyOptions: ModifyOptions);
            var workspace = CreateOmnisharpWorkspaceWithDocument("dummyFile.cs", "class SonarLint_TestAnalyzer_Raise { }");
            var testSubject = CreateTestSubject(workspace, diagnosticWorkerDataProvider.Object);
            var document = workspace.GetDocument("dummyFile.cs");
            
            // first call has no additional files --> should report a diagnostic
            var result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.FirstOrDefault(x => x.Id == TestAnalyzer.Descriptor.Id).Should().NotBeNull();
            
            // second call has additional file --> signal to the analyzer that it should not report a diagnostic
            result = await testSubject.AnalyzeDocumentAsync(document, CancellationToken.None);
            result.Should().BeEmpty();
        }

        private static Mock<ISonarLintDiagnosticWorkerDataProvider> CreateDiagnosticWorkerDataProvider(
            Func<IEnumerable<DiagnosticAnalyzer>> getAnalyzers = null,
            Func<Compilation, Compilation> modifyCompilation = null,
            Func<AnalyzerOptions, AnalyzerOptions> modifyOptions = null)
        {
            getAnalyzers ??= () => new[] {new TestAnalyzer()};
            modifyCompilation ??= originalCompilation => originalCompilation;
            modifyOptions ??= originalOptions => originalOptions;

            Compilation compilation = null;
            AnalyzerOptions options = null;

            var diagnosticWorkerDataProvider = new Mock<ISonarLintDiagnosticWorkerDataProvider>();
            diagnosticWorkerDataProvider
                .Setup(x => x.Get(It.IsAny<Compilation>(), It.IsAny<AnalyzerOptions>()))
                .Callback((Compilation originalCompilation, AnalyzerOptions originalOptions) =>
                {
                    compilation = originalCompilation;
                    options = originalOptions;
                })
                .Returns(() => new DiagnosticWorkerModifications
                {
                    Analyzers = getAnalyzers().ToImmutableArray(),
                    Compilation = modifyCompilation(compilation),
                    AnalyzerOptions = modifyOptions(options)
                });

            return diagnosticWorkerDataProvider;
        }

        private OmniSharpWorkspace CreateOmnisharpWorkspaceWithDocument(string documentFileName, string documentContent)
        {
            var projectInfo = CreateProjectInfo();
            var textLoader = new InMemoryTextLoader();
            var documentInfo = CreateDocumentInfo(projectInfo, textLoader, documentFileName);
            textLoader.AddText(documentInfo.Id, documentContent);

            var workspace = CreateOmniSharpWorkspace();
            workspace.AddProject(projectInfo);
            workspace.AddDocument(documentInfo);
            
            return workspace;
        }

        private OmniSharpWorkspace CreateOmniSharpWorkspace() =>
            new OmniSharpWorkspace(
                new HostServicesAggregator(Enumerable.Empty<IHostServicesProvider>(), Mock.Of<ILoggerFactory>()),
                Mock.Of<ILoggerFactory>(),
                Mock.Of<IFileSystemWatcher>());

        private ProjectInfo CreateProjectInfo() =>
            ProjectInfo.Create(
                    id: ProjectId.CreateNewId(),
                    version: VersionStamp.Create(),
                    name: "SonarLintTest",
                    assemblyName: "AssemblyName",
                    language: LanguageNames.CSharp,
                    filePath: "dummy.csproj",
                    metadataReferences: new[] {MetadataReference.CreateFromFile(typeof(object).Assembly.Location)},
                    analyzerReferences: Enumerable.Empty<AnalyzerReference>())
                .WithDefaultNamespace("SonarLintTest");

        private DocumentInfo CreateDocumentInfo(ProjectInfo projectInfo, InMemoryTextLoader textLoader, string fileName) =>
            DocumentInfo.Create(
                DocumentId.CreateNewId(projectInfo.Id),
                name: fileName,
                loader: textLoader,
                filePath: fileName);

        private static IOptionsMonitor<OmniSharpOptions> CreateOptionsMonitor()
        {
            var optionsMonitor = new Mock<IOptionsMonitor<OmniSharpOptions>>();
            optionsMonitor.Setup(x => x.CurrentValue).Returns(new OmniSharpOptions());
            
            return optionsMonitor.Object;
        }

        private ISonarAnalyzerCodeActionProvider CreateCodeActionProvider()
        {
            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            codeActionProvider.SetupGet(x=> x.CodeDiagnosticAnalyzerProviders).Returns(ImmutableArray<DiagnosticAnalyzer>.Empty);

            return codeActionProvider.Object;
        }
        
        private SonarLintDiagnosticWorker CreateTestSubject(OmniSharpWorkspace workspace, ISonarLintDiagnosticWorkerDataProvider workerDataProvider) =>
            new SonarLintDiagnosticWorker(workerDataProvider,
                workspace,
                CreateCodeActionProvider(),
                Mock.Of<ILoggerFactory>(),
                new DiagnosticEventForwarder(Mock.Of<IEventEmitter>()),
                CreateOptionsMonitor());
        
        #region Helper Classes
        
        private class InMemoryTextLoader : TextLoader
        {
            private readonly Dictionary<DocumentId, string> documentsText = new Dictionary<DocumentId, string>();
            
            public void AddText(DocumentId documentInfoId, string someText)
            {
                documentsText.Add(documentInfoId, someText);
            }

            public override async Task<TextAndVersion> LoadTextAndVersionAsync(Workspace workspace, DocumentId documentId, CancellationToken cancellationToken)
            {
                var text = documentsText[documentId];
                return TextAndVersion.Create(SourceText.From(text),VersionStamp.Default);
            }
        }
        
        [DiagnosticAnalyzer(LanguageNames.CSharp)]
        private class TestAnalyzer : DiagnosticAnalyzer
        {
            public override void Initialize(AnalysisContext context)
            {
                context.ConfigureGeneratedCodeAnalysis(GeneratedCodeAnalysisFlags.Analyze | GeneratedCodeAnalysisFlags.ReportDiagnostics);
                context.EnableConcurrentExecution();
                context.RegisterSymbolAction(AnalyzeSymbol, SymbolKind.NamedType);
            }

            private void AnalyzeSymbol(SymbolAnalysisContext context)
            {
                // Hacky way to test that the analyzer options are being overriden
                var shouldIgnoreAnalysis = context.Options.AdditionalFiles.Any(x => x.Path.Equals(Descriptor.Id));
                if (shouldIgnoreAnalysis)
                {
                    return;
                }
                
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

            public static readonly DiagnosticDescriptor Descriptor = new DiagnosticDescriptor(
                "SonarLintTest",
                "Title",
                "Message",
                "Category",
                defaultSeverity: DiagnosticSeverity.Warning,
                isEnabledByDefault: true,
                description: "Description",
                helpLinkUri: "HelpLink",
                customTags: new[] {"CustomTag"});
        }
        
        #endregion
    }
}