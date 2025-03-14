﻿/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource SA
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

using System.IO;
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using SonarLint.OmniSharp.DotNet.Services.Rules.SonarLintXml;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Rules
{
    [TestClass]
    public class RulesToAdditionalTextConverterTests
    {
        [TestMethod]
        public void Convert_CreatesAdditionalTextNamedSonarLintXml()
        {
            var activeRules = new[] {new ActiveRuleDefinition {RuleId = "some rule"}};
            var sonarLintConfiguration = new SonarLintConfiguration();

            var rulesConverter = new Mock<IRulesToSonarLintConfigurationConverter>();
            rulesConverter.Setup(x => x.Convert(activeRules)).Returns(sonarLintConfiguration);

            var serializer = new Mock<ISonarLintConfigurationSerializer>();
            serializer.Setup(x => x.Serialize(sonarLintConfiguration)).Returns("serialized sonarlint.xml");

            var testSubject = CreateTestSubject(rulesConverter.Object, serializer.Object);

            var result = testSubject.Convert(activeRules);

            result.Path.Should().NotBeNullOrEmpty();
            Path.IsPathRooted(result.Path).Should().BeTrue();
            Path.GetFileName(result.Path).Should().Be("SonarLint.xml");

            var sourceText = result.GetText();
            sourceText.Should().NotBeNull();
            sourceText.ToString().Should().Be("serialized sonarlint.xml");
        }

        private static RulesToAdditionalTextConverter CreateTestSubject(
            IRulesToSonarLintConfigurationConverter rulesConverter,
            ISonarLintConfigurationSerializer rulesSerializer) =>
                new(rulesConverter, rulesSerializer);
    }
}
