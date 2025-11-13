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

using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.QuickFixes
{
    [TestClass]
    public class EditTests
    {
        [TestMethod]
        [DataRow(null)]
        [DataRow("")]
        [DataRow("some new text")]
        public void Ctor_ValidArgs_PopulatedCorrectly(string newText)
        {
            var testSubject = new Edit(
                startLine: 1,
                startColumn: 2,
                endLine: 3,
                endColumn: 4,
                newText: newText
            );

            testSubject.StartLine.Should().Be(1);
            testSubject.StartColumn.Should().Be(2);
            testSubject.EndLine.Should().Be(3);
            testSubject.EndColumn.Should().Be(4);
            testSubject.NewText.Should().Be(newText);
        }
    }
}
