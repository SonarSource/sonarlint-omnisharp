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
using System.Collections.Immutable;
using System.Linq;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp.Models;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker.DiagnosticLocation;
using SonarLint.OmniSharp.Plugin.Services;

namespace SonarLint.OmniSharp.Plugin.UnitTests.Services
{
    [TestClass]
    public class SonarLintCodeCheckServiceTests
    {
        [TestMethod, Ignore]
        public void MefCtor_CheckIsExported()
        {
            // todo: add test
        }
        
        [TestMethod]
        [DataRow(null)]
        [DataRow("")]
        public async Task Handle_NullFileName_ReturnsDiagnosticsForAllFilesInAllProjects(string fileName)
        {
            var location1 = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var location2 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var location3 = new FileLinePositionSpan("file2.cs", new LinePosition(1, 2), new LinePosition(3, 4));

            var diagnosticWorker = SetupDiagnosticWorker(
                CreateDocumentDiagnostics("file1.cs", CreateDiagnostic(location1), CreateDiagnostic(location2)),
                CreateDocumentDiagnostics("file2.cs", CreateDiagnostic(location3)));

            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest(fileName);
            var result = await testSubject.Handle(request);

            (FileLinePositionSpan, FileLinePositionSpan[])[] expectedResult =
            {
                new(location1, Array.Empty<FileLinePositionSpan>()),
                new(location2, Array.Empty<FileLinePositionSpan>()),
                new(location3, Array.Empty<FileLinePositionSpan>())
            };
            
            AssertExpectedLocations(result, expectedResult);

            diagnosticWorker.Verify(x=> x.GetAllDiagnosticsAsync(), Times.Once);
            diagnosticWorker.VerifyNoOtherCalls();
        }

        [TestMethod]
        public async Task Handle_SpecificFileName_ReturnsDiagnosticsOnlyForSpecifiedFile()
        {
            var location1 = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var location2 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));

            var diagnosticWorker = SetupDiagnosticWorker(
                "file1.cs",
                CreateDiagnostic(location1),
                CreateDiagnostic(location2));
            
            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest("file1.cs");
            var result = await testSubject.Handle(request);
            
            (FileLinePositionSpan, FileLinePositionSpan[])[] expectedResult =
            {
                new(location1, Array.Empty<FileLinePositionSpan>()),
                new(location2, Array.Empty<FileLinePositionSpan>())
            };
            
            AssertExpectedLocations(result, expectedResult);

            diagnosticWorker.Verify(x => x.GetDiagnostics(
                    It.Is((ImmutableArray<string> filePaths) => filePaths.Length == 1 && filePaths[0] == "file1.cs")),
                Times.Once);
            diagnosticWorker.VerifyNoOtherCalls();
        }

        [TestMethod]
        [DataRow(null)]
        [DataRow("")]
        public async Task Handle_NullFileName_ReturnsDistinctDiagnostics(string fileName)
        {
            var location1 = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var location2 = new FileLinePositionSpan("file2.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var duplicateLocation = location2;

            var diagnosticWorker = SetupDiagnosticWorker(
                CreateDocumentDiagnostics("file1.cs", CreateDiagnostic(location1)),
                CreateDocumentDiagnostics("file2.cs", CreateDiagnostic(location2), CreateDiagnostic(duplicateLocation)));

            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest(fileName);
            var result = await testSubject.Handle(request);
            
            (FileLinePositionSpan, FileLinePositionSpan[])[] expectedResult =
            {
                new(location1, Array.Empty<FileLinePositionSpan>()),
                new(location2, Array.Empty<FileLinePositionSpan>())
            };
            
            AssertExpectedLocations(result, expectedResult);
        }
        
        [TestMethod]
        public async Task Handle_SpecificFileName_ReturnsDistinctDiagnostics()
        {
            var location1 = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var location2 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var duplicateLocation = location1;

            var diagnosticWorker = SetupDiagnosticWorker(
                "file1.cs",
                CreateDiagnostic(location1),
                CreateDiagnostic(location2),
                CreateDiagnostic(duplicateLocation));
            
            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest("file1.cs");
            var result = await testSubject.Handle(request);

            (FileLinePositionSpan, FileLinePositionSpan[])[] expectedResult =
            {
                new(location1, Array.Empty<FileLinePositionSpan>()),
                new(location2, Array.Empty<FileLinePositionSpan>())
            };
            
            AssertExpectedLocations(result, expectedResult);
        }

        [TestMethod]
        public async Task Handle_SpecificFileName_FileHasAdditionalLocations_ReturnsAdditionalLocations()
        {
            var mainLocation = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var additionalLocation1 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var additionalLocation2 = new FileLinePositionSpan("otherFile1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            
            var diagnostic = CreateDiagnostic(mainLocation, additionalLocation1, additionalLocation2);
            var diagnosticWorker = SetupDiagnosticWorker("file1.cs", diagnostic);

            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest("file1.cs");
            var result = await testSubject.Handle(request);

            (FileLinePositionSpan, FileLinePositionSpan[]) expectedResult = (mainLocation, new[] {additionalLocation1, additionalLocation2});
            AssertExpectedLocations(result, expectedResult);
        }

        [TestMethod]
        [DataRow(null)]
        [DataRow("")]
        public async Task Handle_NullFileName_SomeFileHasAdditionalLocations_ReturnsAdditionalLocations(string fileName)
        {
            var mainLocation1 = new FileLinePositionSpan("file1.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var mainLocation2 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var mainLocation3 = new FileLinePositionSpan("file2.cs", new LinePosition(1, 2), new LinePosition(3, 4));
            var additionalLocation1 = new FileLinePositionSpan("file1.cs", new LinePosition(5, 6), new LinePosition(7, 8));
            var additionalLocation2 = new FileLinePositionSpan("otherFile1.cs", new LinePosition(1, 2), new LinePosition(3, 4));

            var diagnosticWorker = SetupDiagnosticWorker(
                CreateDocumentDiagnostics("file1.cs", CreateDiagnostic(mainLocation1, additionalLocation1)),
                CreateDocumentDiagnostics("file2.cs", CreateDiagnostic(mainLocation2)),
                CreateDocumentDiagnostics("file3.cs", CreateDiagnostic(mainLocation3, additionalLocation2)));

            var testSubject = CreateTestSubject(diagnosticWorker.Object);

            var request = CreateRequest(fileName);
            var result = await testSubject.Handle(request);

            (FileLinePositionSpan, FileLinePositionSpan[])[] expectedResult =
            {
                new(mainLocation1, new[] {additionalLocation1}),
                new(mainLocation2, Array.Empty<FileLinePositionSpan>()),
                new(mainLocation3, new[] {additionalLocation2})
            };
            
            AssertExpectedLocations(result, expectedResult);
        }

        private void AssertExpectedLocations(QuickFixResponse result, params (FileLinePositionSpan, FileLinePositionSpan[])[] expectedLocations)
        {
            result.Should().NotBeNull();

            var quickFixes = result.QuickFixes.ToList();
            quickFixes.Should().AllBeAssignableTo<SonarLintDiagnosticLocation>();
            quickFixes.Count.Should().Be(expectedLocations.Length);

            for (var i = 0; i < expectedLocations.Length; i++)
            {
                var mainLocation = (SonarLintDiagnosticLocation) quickFixes[i];
                var expectedMainLocation = expectedLocations[i].Item1;
                AssertLocation(mainLocation, expectedMainLocation);

                var expectedAdditionalLocations = expectedLocations[i].Item2;
                AssertAdditionalLocations(mainLocation.AdditionalLocations, expectedAdditionalLocations);
            }
        }

        private void AssertAdditionalLocations(ICodeLocation[] actualLocations, FileLinePositionSpan[] expectedLocations)
        {
            actualLocations.Should().NotBeNull();
            actualLocations.Length.Should().Be(expectedLocations.Length);

            for (int i = 0; i < actualLocations.Length; i++)
            {
                AssertLocation(actualLocations[i], expectedLocations[i]);
            }
        }

        private void AssertLocation(ICodeLocation quickFix, FileLinePositionSpan location)
        {
            quickFix.FileName.Should().Be(location.Path);
            quickFix.Line.Should().Be(location.StartLinePosition.Line);
            quickFix.Column.Should().Be(location.StartLinePosition.Character);
            quickFix.EndLine.Should().Be(location.EndLinePosition.Line);
            quickFix.EndColumn.Should().Be(location.EndLinePosition.Character);
        }

        private SonarLintCodeCheckRequest CreateRequest(string fileName) =>
            new SonarLintCodeCheckRequest {FileName = fileName};

        private SonarLintCodeCheckService CreateTestSubject(ISonarLintDiagnosticWorker diagnosticWorker) =>
            new SonarLintCodeCheckService(diagnosticWorker);
        
        private static DocumentDiagnostics CreateDocumentDiagnostics(string fileName, params Diagnostic[] diagnostics)
        {
            var project = ProjectId.CreateNewId();
            
            var documentDiagnostics = new DocumentDiagnostics(DocumentId.CreateNewId(project),
                fileName,
                project,
                project.Id.ToString(),
                diagnostics.ToImmutableArray());
            
            return documentDiagnostics;
        }
        
        private static Diagnostic CreateDiagnostic(FileLinePositionSpan locationSpan, params FileLinePositionSpan[] additionalLocationSpans)
        {
            var descriptor = new DiagnosticDescriptor(
                id: Guid.NewGuid().ToString(),
                title: Guid.NewGuid().ToString(),
                messageFormat: Guid.NewGuid().ToString(),
                category: Guid.NewGuid().ToString(),
                defaultSeverity: DiagnosticSeverity.Hidden,
                isEnabledByDefault: true);

            var additionalLocations = additionalLocationSpans.Select(CreateLocation).ToArray();
            
            var diagnostic = new Mock<Diagnostic>();
            diagnostic.SetupGet(x => x.Id).Returns(locationSpan.GetHashCode().ToString);
            diagnostic.SetupGet(x => x.Location).Returns(CreateLocation(locationSpan));
            diagnostic.SetupGet(x => x.Severity).Returns(DiagnosticSeverity.Info);
            diagnostic.SetupGet(x => x.Descriptor).Returns(descriptor);
            diagnostic.SetupGet(x => x.AdditionalLocations).Returns(additionalLocations);
            diagnostic.SetupGet(x => x.Properties).Returns(ImmutableDictionary<string, string>.Empty);

            return diagnostic.Object;
        }

        private static Location CreateLocation(FileLinePositionSpan locationSpan)
        {
            var location = new Mock<Location>();
            location.Setup(x => x.GetMappedLineSpan()).Returns(locationSpan);
            
            return location.Object;
        }

        private static Mock<ISonarLintDiagnosticWorker> SetupDiagnosticWorker(params DocumentDiagnostics[] documentDiagnostics)
        {
            var diagnosticWorker = new Mock<ISonarLintDiagnosticWorker>();

            diagnosticWorker
                .Setup(x => x.GetAllDiagnosticsAsync())
                .ReturnsAsync(documentDiagnostics.ToImmutableArray());
            
            return diagnosticWorker;
        }
        
        private static Mock<ISonarLintDiagnosticWorker> SetupDiagnosticWorker(string fileName, params Diagnostic[] diagnostics)
        {
            var diagnosticWorker = new Mock<ISonarLintDiagnosticWorker>();

            diagnosticWorker
                .Setup(x => x.GetDiagnostics(
                    It.Is((ImmutableArray<string> fileNames) => fileNames.Length == 1 && fileNames[0] == fileName)))
                .ReturnsAsync(new[] {CreateDocumentDiagnostics(fileName, diagnostics)}.ToImmutableArray());

            return diagnosticWorker;
        }
    }
}