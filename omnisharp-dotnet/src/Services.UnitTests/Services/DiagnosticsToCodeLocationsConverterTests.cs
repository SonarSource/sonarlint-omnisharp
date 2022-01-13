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
using System.Collections.Immutable;
using System.Linq;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations;
using SonarLint.OmniSharp.DotNet.Services.Services;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Services
{
    [TestClass]
    public class DiagnosticsToCodeLocationsConverterTests
    {
        [TestMethod]
        public void Convert_NoDiagnostics_ReturnsEmptyArray()
        {
            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(ImmutableArray<DocumentDiagnostics>.Empty, null);

            result.Should().BeEmpty();
        }

        [TestMethod]
        public void Convert_NoFileNameFilter_ConvertsAllDiagnostics()
        {
            var location1 = CreateLocationSpan("file1.cs", 1, 2, 3, 4);
            var location2 = CreateLocationSpan("file2.cs", 1, 2, 3, 4);
            var location3 = CreateLocationSpan("file3.cs", 1, 2, 3, 4);

            var documentDiagnostics1 = CreateDocumentDiagnostics("file1.cs", location1);
            var documentDiagnostics2 = CreateDocumentDiagnostics(null, location2);
            var documentDiagnostics3 = CreateDocumentDiagnostics("file2.cs", location3);

            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(
                new[] {documentDiagnostics1, documentDiagnostics2, documentDiagnostics3}.ToImmutableArray(),
                fileNameFilter: null);

            result.Should().NotBeNull();
            result.Length.Should().Be(3);

            AssertLocation(result[0], location1);
            AssertLocation(result[1], location2);
            AssertLocation(result[2], location3);
        }

        [TestMethod]
        public void Convert_HasFileNameFilter_ConvertsDiagnosticsWithSpecifiedName()
        {
            var location1 = CreateLocationSpan("file1.cs", 1, 2, 3, 4);
            var location2 = CreateLocationSpan("file2.cs", 1, 2, 3, 4);
            var location3 = CreateLocationSpan("file3.cs", 1, 2, 3, 4);

            var documentDiagnostics1 = CreateDocumentDiagnostics("file1.cs", location1);
            var documentDiagnostics2 = CreateDocumentDiagnostics(null, location2);
            var documentDiagnostics3 = CreateDocumentDiagnostics("file2.cs", location3);

            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(
                new[] {documentDiagnostics1, documentDiagnostics2, documentDiagnostics3}.ToImmutableArray(),
                fileNameFilter: "file2.cs");

            result.Should().NotBeNull();
            result.Length.Should().Be(1);

            AssertLocation(result[0], location3);
        }

        [TestMethod]
        [DataRow(null)]
        [DataRow("file1.cs")]
        public void Convert_HasAdditionalLocations_ReturnsWithAdditionalLocations(string fileName)
        {
            var mainLocation = CreateLocationSpan("file1.cs", 1, 2, 3, 4);
            var additionalLocation1 = CreateAdditionalLocation("file1.cs", CreateLinePositionSpan(1, 2, 3, 4));
            var additionalLocation2 = CreateAdditionalLocation("file2.cs", CreateLinePositionSpan(5, 6, 7, 8));

            var additionalLocationsWithMessages = new[]
            {
                (additionalLocation1, "message1"),
                (additionalLocation2, "message2")
            };

            var diagnostic = CreateDiagnostic(mainLocation, additionalLocationsWithMessages);
            var documentDiagnostics = CreateDocumentDiagnostics(fileName, diagnostic);

            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(
                new[] {documentDiagnostics}.ToImmutableArray(),
                fileName);

            result.Should().NotBeNull();
            result.Length.Should().Be(1);

            var convertedLocation = result[0];
            AssertLocation(convertedLocation, mainLocation);

            convertedLocation.AdditionalLocations.Should().NotBeNullOrEmpty();
            convertedLocation.AdditionalLocations.Length.Should().Be(2);

            AssertLocation(convertedLocation.AdditionalLocations[0], additionalLocation1.GetMappedLineSpan());
            AssertLocation(convertedLocation.AdditionalLocations[1], additionalLocation2.GetMappedLineSpan());

            convertedLocation.AdditionalLocations[0].Text.Should().Be("message1");
            convertedLocation.AdditionalLocations[1].Text.Should().Be("message2");
        }

        [TestMethod]
        [DataRow(null)]
        [DataRow("file1.cs")]
        public void Convert_HasDuplicatedDiagnostics_ReturnsDistinctLocations(string fileName)
        {
            var location1 = CreateLocationSpan("file1.cs", 1, 2, 3, 4);
            var location2 = CreateLocationSpan("file2.cs", 1, 2, 3, 4);
            var location3 = location1;

            var documentDiagnostics = CreateDocumentDiagnostics(fileName, location1, location2, location3);

            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(
                new[] {documentDiagnostics}.ToImmutableArray(),
                fileName);

            result.Should().NotBeNull();
            result.Length.Should().Be(2);

            AssertLocation(result[0], location1);
            AssertLocation(result[1], location2);
        }

        private static void AssertLocation(ICodeLocation codeLocation, FileLinePositionSpan location)
        {
            codeLocation.FileName.Should().Be(location.Path);
            codeLocation.Line.Should().Be(location.StartLinePosition.Line);
            codeLocation.Column.Should().Be(location.StartLinePosition.Character);
            codeLocation.EndLine.Should().Be(location.EndLinePosition.Line);
            codeLocation.EndColumn.Should().Be(location.EndLinePosition.Character);
        }

        private static FileLinePositionSpan CreateLocationSpan(string fileName, int startLine, int column, int endLine, int endColumn) =>
            new(fileName, CreateLinePositionSpan(startLine, column, endLine, endColumn));

        private static LinePositionSpan CreateLinePositionSpan(int startLine, int column, int endLine, int endColumn) =>
            new(new LinePosition(startLine, column), new LinePosition(endLine, endColumn));

        private static Location CreateAdditionalLocation(string filePath, LinePositionSpan span) =>
            Location.Create(filePath, new TextSpan(1, 1000), span);

        private static Diagnostic CreateDiagnostic(FileLinePositionSpan locationSpan, params (Location, string)[] additionalLocationsWithMessages)
        {
            var location = new Mock<Location>();
            location.Setup(x => x.GetMappedLineSpan()).Returns(locationSpan);

            var descriptor = new DiagnosticDescriptor(
                id: Guid.NewGuid().ToString(),
                title: Guid.NewGuid().ToString(),
                messageFormat: Guid.NewGuid().ToString(),
                category: Guid.NewGuid().ToString(),
                defaultSeverity: DiagnosticSeverity.Hidden,
                isEnabledByDefault: true);

            var additionalLocations = additionalLocationsWithMessages.Select(x => x.Item1).ToArray();
            var additionalLocationsMessages = GetLocationMessages(additionalLocationsWithMessages);

            var diagnostic = new Mock<Diagnostic>();
            diagnostic.SetupGet(x => x.Id).Returns(locationSpan.GetHashCode().ToString);
            diagnostic.SetupGet(x => x.Location).Returns(location.Object);
            diagnostic.SetupGet(x => x.Severity).Returns(DiagnosticSeverity.Info);
            diagnostic.SetupGet(x => x.Descriptor).Returns(descriptor);
            diagnostic.SetupGet(x => x.AdditionalLocations).Returns(additionalLocations);
            diagnostic.SetupGet(x => x.Properties).Returns(additionalLocationsMessages);

            return diagnostic.Object;
        }

        /// <summary>
        /// See the logic in <see cref="SonarLintDiagnosticLocationExtensions.GetLocationMessage"/>
        /// </summary>
        private static ImmutableDictionary<string, string> GetLocationMessages((Location, string)[] additionalLocationWithMessages)
        {
            var locationMessages = additionalLocationWithMessages
                .Select((item, index) => new {Message = item.Item2, Index = index.ToString()})
                .ToDictionary(i => i.Index, i => i.Message)
                .ToImmutableDictionary();

            return locationMessages;
        }

        private static DocumentDiagnostics CreateDocumentDiagnostics(string fileName, params FileLinePositionSpan[] locationSpans) =>
            CreateDocumentDiagnostics(fileName, locationSpans.Select(x=> CreateDiagnostic(x)).ToArray());

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

        private static DiagnosticsToCodeLocationsConverter CreateTestSubject() => new();
    }
}
