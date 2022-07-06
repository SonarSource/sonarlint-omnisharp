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
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Composition;
using System.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.Extensions.Logging;
using OmniSharp.Roslyn.CSharp.Workers.Diagnostics;

namespace SonarLint.OmniSharp.DotNet.Services.Rules
{
    internal interface IRulesToReportDiagnosticsConverter
    {
        Dictionary<string, ReportDiagnostic> Convert(ImmutableHashSet<string> activeRules, ImmutableHashSet<string> allRules);
    }

    [Export(typeof(IRulesToReportDiagnosticsConverter))]
    internal class RulesToReportDiagnosticsConverter : IRulesToReportDiagnosticsConverter
    {
        private readonly ILogger _logger;

        // the severity is handled on the java side; we're using 'warn' just to make sure that the rule is run. 
        internal const ReportDiagnostic EnabledRuleSeverity = ReportDiagnostic.Warn;
        internal const ReportDiagnostic DisabledRuleSeverity = ReportDiagnostic.Suppress;

        [ImportingConstructor]
        public RulesToReportDiagnosticsConverter(ILoggerFactory loggerFactory)
        {
            // Note: creating a logger using the same category as the main diagnostic worker type/
            // If we use a differenty category the output is not logged and does not appear in the
            // SonarLint pane in Rider.
            _logger = loggerFactory.CreateLogger<CopiedCSharpDiagnosticWorkerWithAnalyzers>();
        }

        public Dictionary<string, ReportDiagnostic> Convert(ImmutableHashSet<string> activeRules, ImmutableHashSet<string> allRules)
        {
            if (allRules.IsEmpty)
            {
                throw new ArgumentException("No analyzer rules", nameof(allRules));
            }

            var unrecognizedActiveRules = activeRules.Except(allRules).OrderBy(x=> x, StringComparer.OrdinalIgnoreCase).ToArray(); 
            
            if (unrecognizedActiveRules.Any())
            {
                _logger.LogInformation($@"Unrecognized active rules: {string.Join(", ", unrecognizedActiveRules)}. These might be new rules that exist on the server but not in SonarLint.");
            }

            var diagnosticOptions = allRules
                .ToDictionary(ruleId => ruleId,
                    ruleId => activeRules.Contains(ruleId)
                        ? EnabledRuleSeverity
                        : DisabledRuleSeverity);

            return diagnosticOptions;
        }
    }
}
