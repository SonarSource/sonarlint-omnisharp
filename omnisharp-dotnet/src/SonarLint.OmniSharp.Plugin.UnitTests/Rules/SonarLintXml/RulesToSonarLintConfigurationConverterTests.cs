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
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.Plugin.Rules;
using SonarLint.OmniSharp.Plugin.Rules.SonarLintXml;
using SonarLint.VisualStudio.Core.CSharpVB;

namespace SonarLint.OmniSharp.Plugin.UnitTests.Rules.SonarLintXml
{
    [TestClass]
    public class RulesToSonarLintConfigurationConverterTests
    {
        [TestMethod]
        public void Convert_NoSettings_ConfigurationWithoutSettings()
        {
            var testSubject = CreateTestSubject();

            var sonarLintConfiguration = testSubject.Convert(Array.Empty<RuleDefinition>());

            sonarLintConfiguration.Settings.Should().BeNull();
        }
        
        [TestMethod]
        public void Convert_NoRules_ConfigurationWithoutRules()
        {
            var testSubject = CreateTestSubject();

            var sonarLintConfiguration = testSubject.Convert(Array.Empty<RuleDefinition>());

            sonarLintConfiguration.Rules.Should().BeEmpty();
        }
        
        [TestMethod]
        public void Convert_HasRules_ConfigurationWithRules()
        {
            var testSubject = CreateTestSubject();

            var rule1 = new RuleDefinition
            {
                RuleId = "rule1",
                Parameters = new Dictionary<string, string> {{"param1", "value1"}}
            };
            
            var rule2 = new RuleDefinition
            {
                RuleId = "rule2",
                Parameters = null
            };
            
            var rule3 = new RuleDefinition
            {
                RuleId = "rule3",
                Parameters = new Dictionary<string, string> {{"param2", "value2"}, {"param3", "value3"}}
            };

            var sonarLintConfiguration = testSubject.Convert(new[] {rule1, rule2, rule3});

            sonarLintConfiguration.Rules.Should().NotBeEmpty();
            sonarLintConfiguration.Rules.Count.Should().Be(3);

            sonarLintConfiguration.Rules[0].Key.Should().Be("rule1");
            sonarLintConfiguration.Rules[0].Parameters.Should().BeEquivalentTo(
                new SonarLintKeyValuePair{Key = "param1", Value = "value1"});
            
            sonarLintConfiguration.Rules[1].Key.Should().Be("rule2");
            sonarLintConfiguration.Rules[1].Parameters.Should().BeNull();

            sonarLintConfiguration.Rules[2].Key.Should().Be("rule3");
            sonarLintConfiguration.Rules[2].Parameters.Should().BeEquivalentTo(
                new SonarLintKeyValuePair{Key = "param2", Value = "value2"},
                new SonarLintKeyValuePair{Key = "param3", Value = "value3"});
        }
        
        private RulesToSonarLintConfigurationConverter CreateTestSubject() => new RulesToSonarLintConfigurationConverter();
    }
}
