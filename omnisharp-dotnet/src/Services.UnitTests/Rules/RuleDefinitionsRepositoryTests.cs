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

using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Rules
{
    [TestClass]
    public class RuleDefinitionsRepositoryTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<RuleDefinitionsRepository, IRuleDefinitionsRepository>();
        }

        [TestMethod]
        public void Get_ValueIsNotSet_EmptyList()
        {
            var testSubject = CreateTestSubject();
            testSubject.RuleDefinitions.Should().BeEmpty();
        }

        [TestMethod]
        public void Set_Null_ValueIsSetToEmptyList()
        {
            var testSubject = CreateTestSubject();
            testSubject.RuleDefinitions = null;

            testSubject.RuleDefinitions.Should().BeEmpty();
        }

        [TestMethod]
        public void Set_ValueIsSet()
        {
            var testSubject = CreateTestSubject();

            testSubject.RuleDefinitions.Should().BeEmpty();

            var rules = new[] {new RuleDefinition {RuleId = "1"}};
            testSubject.RuleDefinitions = rules;

            testSubject.RuleDefinitions.Should().BeSameAs(rules);

            rules = new[] {new RuleDefinition {RuleId = "2"}, new RuleDefinition{RuleId = "3"}};
            testSubject.RuleDefinitions = rules;

            testSubject.RuleDefinitions.Should().BeSameAs(rules);
        }

        private RuleDefinitionsRepository CreateTestSubject() => new RuleDefinitionsRepository();
    }
}
