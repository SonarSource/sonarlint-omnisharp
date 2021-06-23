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
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker.DiagnosticLocation;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintDiagnosticLocationExtensionsTests
    {
        [TestMethod]
        public void ToAdditionalLocations_NoAdditionalLocations_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            
            var result = diagnostic.ToAdditionalLocations();

            result.Should().BeEmpty();
        }

        [TestMethod]
        public void ToAdditionalLocations_HasAdditionalLocations_LocationsConverted()
        {
            var location1 = CreateRandomLocation("file1.cs");
            var location2 = CreateRandomLocation("file1.cs");
            var location3 = CreateRandomLocation("file1.cs");
            
            (Location, string)[] additionalLocationsWithMessages = {
                new(location1, "some message 1"),
                new(location2, null),
                new(location3, "some message 2"),
            };
            
            var diagnostic = CreateDiagnostic(additionalLocationsWithMessages);
            
            var result = diagnostic.ToAdditionalLocations();

            result.Should().NotBeEmpty();
            result.Length.Should().Be(3);

            AssertLocation(result[0], location1, "some message 1");
            AssertLocation(result[1], location2, null);
            AssertLocation(result[2], location3, "some message 2");
        }

        private void AssertLocation(ICodeLocation convertedLocation, Location reportedLocation, string expectedMessage)
        {
            convertedLocation.Text.Should().Be(expectedMessage);

            var expectedSpan = reportedLocation.GetMappedLineSpan();
            convertedLocation.FileName.Should().Be(expectedSpan.Path);
            convertedLocation.Line.Should().Be(expectedSpan.StartLinePosition.Line);
            convertedLocation.Column.Should().Be(expectedSpan.StartLinePosition.Character);
            convertedLocation.EndLine.Should().Be(expectedSpan.EndLinePosition.Line);
            convertedLocation.EndColumn.Should().Be(expectedSpan.EndLinePosition.Character);
        }

        private Diagnostic CreateDiagnostic(params (Location, string)[] additionalLocationsWithMessages)
        {
            var locations = additionalLocationsWithMessages.Select(x => x.Item1).ToArray();
            var locationMessages = GetLocationMessages(additionalLocationsWithMessages);

            var diagnostic = Diagnostic.Create(Descriptor,
                CreateRandomLocation("some file"),
                additionalLocations: locations,
                messageArgs: null,
                properties: locationMessages);

            return diagnostic;
        }

        private Location CreateRandomLocation(string fileName)
        {
            var random = new Random();
            var startLine = random.Next();
            var endLine = random.Next(startLine, startLine + 10);
            
            return Location.Create(fileName, 
                new TextSpan(1, 1000000), 
                new LinePositionSpan(new LinePosition(startLine, random.Next()), new LinePosition(endLine, random.Next())));
        }

        /// <summary>
        /// See the logic in <see cref="SonarLintDiagnosticLocationExtensions.GetLocationMessage"/>
        /// </summary>
        private ImmutableDictionary<string, string> GetLocationMessages((Location, string)[] additionalLocationWithMessages)
        {
            var locationMessages = additionalLocationWithMessages
                .Select((item, index) => new {Message = item.Item2, Index = index.ToString()})
                .ToDictionary(i => i.Index, i => i.Message)
                .ToImmutableDictionary();
            
            return locationMessages;
        }

        private static DiagnosticDescriptor Descriptor = new DiagnosticDescriptor(
            id: "id",
            title: "title",
            messageFormat: "format",
            category: "category",
            defaultSeverity: DiagnosticSeverity.Error,
            isEnabledByDefault: true);
    }
}