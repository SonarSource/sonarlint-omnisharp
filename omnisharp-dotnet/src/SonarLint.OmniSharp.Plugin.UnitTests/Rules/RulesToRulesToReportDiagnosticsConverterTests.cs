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
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.Plugin.Rules;

namespace SonarLint.OmniSharp.Plugin.UnitTests.Rules
{
    [TestClass]
    public class RulesToRulesToReportDiagnosticsConverterTests
    {
        [TestMethod]
        public void Convert_NoRules_EmptyResult()
        {
            var testSubject = CreateTestSubject();

            var result = testSubject.Convert(Array.Empty<RuleDefinition>());

            result.Should().BeEmpty();
        }
        
        [TestMethod]
        public void Convert_EnabledAndDisabledRules_DiagnosticSeverityIsWarningAndSuppressed()
        {
            const ReportDiagnostic enabledRuleSeverity = ReportDiagnostic.Warn;
            const ReportDiagnostic disabledRuleSeverity = ReportDiagnostic.Suppress;
            
            var testSubject = CreateTestSubject();

            var ruleDefinitions = new[]
            {
                CreateRuleDefinition("rule1", true), 
                CreateRuleDefinition("rule2", false),
                CreateRuleDefinition("rule3", true),
                CreateRuleDefinition("rule4", false)
            };

            var result = testSubject.Convert(ruleDefinitions);

            result.Should().NotBeEmpty();
            result.Count.Should().Be(4);

            result["rule1"].Should().Be(enabledRuleSeverity);
            result["rule2"].Should().Be(disabledRuleSeverity);
            result["rule3"].Should().Be(enabledRuleSeverity);
            result["rule4"].Should().Be(disabledRuleSeverity);
        }
        
        private RuleDefinition CreateRuleDefinition(string ruleId, bool isEnabled) => new RuleDefinition {RuleId = ruleId, IsEnabled = isEnabled};

        private RulesToRulesToReportDiagnosticsConverter CreateTestSubject() => new RulesToRulesToReportDiagnosticsConverter();
    }
}