/*
 * SonarOmnisharp
 * Copyright (C) 2021-2023 SonarSource SA
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
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.QuickFixes
{
    [TestClass]
    public class FixTests
    {
        [TestMethod]
        public void Ctor_NullEdits_ArgumentNullException()
        {
            Action act = () => new Fix("some file", null);

            act.Should().ThrowExactly<ArgumentNullException>().And.ParamName.Should().Be("edits");
        }

        [TestMethod]
        public void Ctor_NoFixes_ArgumentNullException()
        {
            Action act = () => new Fix("some file", Array.Empty<IEdit>());

            act.Should().ThrowExactly<ArgumentNullException>().And.ParamName.Should().Be("edits");
        }

        [TestMethod]
        public void Ctor_ValidArgs_PopulatedCorrectly()
        {
            var edits = new[] {Mock.Of<IEdit>(), Mock.Of<IEdit>()};

            var testSubject = new Fix("some file", edits);

            testSubject.FileName.Should().Be("some file");
            testSubject.Edits.Should().BeEquivalentTo(edits);
        }
    }
}
