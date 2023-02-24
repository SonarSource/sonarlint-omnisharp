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
    public class QuickFixTests
    {
        [TestMethod]
        public void Ctor_NullFixes_ArgumentNullException()
        {
            Action act = () => new QuickFix("message", null);

            act.Should().ThrowExactly<ArgumentNullException>().And.ParamName.Should().Be("fixes");
        }

        [TestMethod]
        public void Ctor_NoFixes_ArgumentNullException()
        {
            Action act = () => new QuickFix("message", Array.Empty<IFix>());

            act.Should().ThrowExactly<ArgumentNullException>().And.ParamName.Should().Be("fixes");
        }

        [TestMethod]
        public void Ctor_ValidArgs_PopulatedCorrectly()
        {
            var fixes = new[] {Mock.Of<IFix>(), Mock.Of<IFix>()};

            var testSubject = new QuickFix("some message", fixes);

            testSubject.Message.Should().Be("some message");
            testSubject.Fixes.Should().BeEquivalentTo(fixes);
        }
    }
}
