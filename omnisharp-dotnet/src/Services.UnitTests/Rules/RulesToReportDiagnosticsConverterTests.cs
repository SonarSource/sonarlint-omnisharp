/*
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

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using FluentAssertions;
using Microsoft.Extensions.Logging;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Rules
{
    [TestClass]
    public class RulesToReportDiagnosticsConverterTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<RulesToReportDiagnosticsConverter, IRulesToReportDiagnosticsConverter>(
                CreateExport<ILoggerFactory>());
        }

        [TestMethod]
        public void Convert_NoAnalyzerRules_ArgumentException()
        {
            var existingAnalyzerRules = ImmutableHashSet<string>.Empty;
            var activeRules = new[] {"rule1"}.ToImmutableHashSet();

            var testSubject = CreateTestSubject();
            Action act = () => testSubject.Convert(activeRules, existingAnalyzerRules);

            act.Should().Throw<ArgumentException>().And.ParamName.Should().Be("allRules");
        }

        [TestMethod]
        public void Convert_NoActiveRules_AllAnalyzerRulesAreSuppressed()
        {
            var existingAnalyzerRules = new[] {"rule1", "rule2"}.ToImmutableHashSet();
            var activeRules = ImmutableHashSet<string>.Empty;

            var testSubject = CreateTestSubject();
            var result = testSubject.Convert(activeRules, existingAnalyzerRules);

            result.Should().NotBeEmpty();
            result.Count.Should().Be(2);

            result["rule1"].Should().Be(RulesToReportDiagnosticsConverter.DisabledRuleSeverity);
            result["rule2"].Should().Be(RulesToReportDiagnosticsConverter.DisabledRuleSeverity);
        }

        [TestMethod] // See https://jira.sonarsource.com/browse/SLI-637
        public void Convert_UnrecognizedActiveRules_AreIgnored()
        {
            var logger = new TestLogger();
            var existingAnalyzerRules = new[] {"knownRule_active", "knownRule_inactive"}.ToImmutableHashSet();
            var activeRules = new[]
            {
                "unknown1",
                "unknown2",
                "knownRule_active"
            }.ToImmutableHashSet();

            var testSubject = CreateTestSubject(logger);
            var result = testSubject.Convert(activeRules, existingAnalyzerRules);

            // Unrecognized rules should be logged then ignored.
            // Scenario: user is connected to SonarCloud which is using a newer version of the C# analyzer
            // which has new rules that are not in the embedded version.
            result.Count.Should().Be(2);
            result["knownRule_active"].Should().Be(RulesToReportDiagnosticsConverter.EnabledRuleSeverity);
            result["knownRule_inactive"].Should().Be(RulesToReportDiagnosticsConverter.DisabledRuleSeverity);

            logger.Messages.Any(x => x.Contains("unknown1") && x.Contains("unknown2")).Should().BeTrue();
        }

        [TestMethod]
        public void Convert_HasValidActiveRules_ActiveRulesAreSetToWarn_OtherRulesAreSuppressed()
        {
            var existingAnalyzerRules = new[] {"rule1", "rule2", "rule3", "rule4"}.ToImmutableHashSet();
            var activeRules = new[]
            {
                "rule1",
                "rule3",
            }.ToImmutableHashSet();

            var testSubject = CreateTestSubject();
            var result = testSubject.Convert(activeRules, existingAnalyzerRules);

            result.Should().NotBeEmpty();
            result.Count.Should().Be(4);

            result["rule1"].Should().Be(RulesToReportDiagnosticsConverter.EnabledRuleSeverity);
            result["rule2"].Should().Be(RulesToReportDiagnosticsConverter.DisabledRuleSeverity);
            result["rule3"].Should().Be(RulesToReportDiagnosticsConverter.EnabledRuleSeverity);
            result["rule4"].Should().Be(RulesToReportDiagnosticsConverter.DisabledRuleSeverity);
        }

        private static RulesToReportDiagnosticsConverter CreateTestSubject(ILogger logger = null)
        {
            logger ??= Mock.Of<ILogger>();

            var factory = new Mock<ILoggerFactory>();
            factory.Setup(x => x.CreateLogger(It.IsAny<string>())).Returns(logger);

            var testSubject = new RulesToReportDiagnosticsConverter(factory.Object);

            return testSubject;
        }

        private class TestLogger : ILogger, IDisposable
        {
            public IList<string> Messages { get; } = new List<string>();
            IDisposable ILogger.BeginScope<TState>(TState state) => this;
            void IDisposable.Dispose() { /* no-op */ }

            bool ILogger.IsEnabled(LogLevel logLevel) => true;
            void ILogger.Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception exception, Func<TState, Exception, string> formatter)
                => Messages.Add(state.ToString());
        }
    }
}
