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
using System.Linq;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp.Mef;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations;
using SonarLint.OmniSharp.DotNet.Services.Services;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Services
{
    [TestClass]
    public class SonarLintCodeCheckServiceTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<SonarLintCodeCheckService, IRequestHandler>(
                CreateExport<ISonarLintDiagnosticWorker>());
        }

        [TestMethod]
        [DataRow(null)]
        [DataRow("")]
        public async Task Handle_NullFileName_ReturnsDiagnosticsForAllFilesInAllProjects(string fileName)
        {
            var diagnostics = new[]
            {
                CreateDocumentDiagnostics("file1.cs"),
                CreateDocumentDiagnostics("file2.cs")
            }.ToImmutableArray();

            var convertedLocations = new[]
            {
                new SonarLintDiagnosticLocation {Id = "test1"},
                new SonarLintDiagnosticLocation {Id = "test2"}
            }.ToImmutableArray();

            var diagnosticWorker = SetupDiagnosticWorker(diagnostics);
            var diagnosticsConverter = SetupDiagnosticsConverter(null, diagnostics, convertedLocations);

            var testSubject = CreateTestSubject(diagnosticWorker.Object, diagnosticsConverter.Object);

            var request = CreateRequest(fileName);
            var result = await testSubject.Handle(request);
            result.Should().NotBeNull();

            var quickFixes = result.QuickFixes.ToList();
            quickFixes.Should().BeEquivalentTo(convertedLocations);

            diagnosticWorker.Verify(x=> x.GetAllDiagnosticsAsync(), Times.Once);
            diagnosticWorker.VerifyNoOtherCalls();
        }

        [TestMethod]
        public async Task Handle_SpecificFileName_ReturnsDiagnosticsOnlyForSpecifiedFile()
        {
            var diagnostics = new[]
            {
                CreateDocumentDiagnostics("file1.cs"),
                CreateDocumentDiagnostics("file2.cs")
            }.ToImmutableArray();

            var convertedLocations = new[]
            {
                new SonarLintDiagnosticLocation {Id = "test1"},
                new SonarLintDiagnosticLocation {Id = "test2"}
            }.ToImmutableArray();

            var diagnosticWorker = SetupDiagnosticWorker("file1.cs", diagnostics);
            var diagnosticsConverter = SetupDiagnosticsConverter("file1.cs", diagnostics, convertedLocations);

            var testSubject = CreateTestSubject(diagnosticWorker.Object, diagnosticsConverter.Object);

            var request = CreateRequest("file1.cs");
            var result = await testSubject.Handle(request);
            result.Should().NotBeNull();

            var quickFixes = result.QuickFixes.ToList();
            quickFixes.Should().BeEquivalentTo(convertedLocations);

            diagnosticWorker.Verify(x => x.GetDiagnostics(
                    It.Is((ImmutableArray<string> filePaths) => filePaths.Length == 1 && filePaths[0] == "file1.cs")),
                Times.Once);
            diagnosticWorker.VerifyNoOtherCalls();
        }

        private SonarLintCodeCheckRequest CreateRequest(string fileName) => new() {FileName = fileName};

        private static SonarLintCodeCheckService CreateTestSubject(
            ISonarLintDiagnosticWorker diagnosticWorker,
            IDiagnosticsToCodeLocationsConverter converter) => new(diagnosticWorker, converter);

        private static DocumentDiagnostics CreateDocumentDiagnostics(string fileName)
        {
            var project = ProjectId.CreateNewId();

            var documentDiagnostics = new DocumentDiagnostics(DocumentId.CreateNewId(project),
                fileName,
                project,
                project.Id.ToString(),
                ImmutableArray<Diagnostic>.Empty);

            return documentDiagnostics;
        }

        private static Mock<ISonarLintDiagnosticWorker> SetupDiagnosticWorker(ImmutableArray<DocumentDiagnostics> documentDiagnostics)
        {
            var diagnosticWorker = new Mock<ISonarLintDiagnosticWorker>();

            diagnosticWorker
                .Setup(x => x.GetAllDiagnosticsAsync())
                .ReturnsAsync(documentDiagnostics);

            return diagnosticWorker;
        }

        private static Mock<ISonarLintDiagnosticWorker> SetupDiagnosticWorker(string fileName, ImmutableArray<DocumentDiagnostics> documentDiagnostics)
        {
            var diagnosticWorker = new Mock<ISonarLintDiagnosticWorker>();

            diagnosticWorker
                .Setup(x => x.GetDiagnostics(
                    It.Is((ImmutableArray<string> fileNames) => fileNames.Length == 1 && fileNames[0] == fileName)))
                .ReturnsAsync(documentDiagnostics);

            return diagnosticWorker;
        }

        private static Mock<IDiagnosticsToCodeLocationsConverter> SetupDiagnosticsConverter(
            string fileNameFilter,
            ImmutableArray<DocumentDiagnostics> diagnostics,
            ImmutableArray<SonarLintDiagnosticLocation> convertedLocations)
        {
            var diagnosticsConverter = new Mock<IDiagnosticsToCodeLocationsConverter>();

            diagnosticsConverter
                .Setup(x => x.Convert(diagnostics, fileNameFilter))
                .Returns(convertedLocations);

            return diagnosticsConverter;
        }
    }
}
