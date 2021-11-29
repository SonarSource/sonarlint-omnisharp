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
using System.Collections.Immutable;
using System.Linq;
using Microsoft.CodeAnalysis;

namespace SonarLint.OmniSharp.DotNet.Services.Rules
{
    internal interface IRulesToReportDiagnosticsConverter
    {
        Dictionary<string, ReportDiagnostic> Convert(ImmutableHashSet<string> activeRules, ImmutableHashSet<string> allRules);
    }

    internal class RulesToReportDiagnosticsConverter : IRulesToReportDiagnosticsConverter
    {
        // the severity is handled on the java side; we're using 'warn' just to make sure that the rule is run. 
        internal const ReportDiagnostic EnabledRuleSeverity = ReportDiagnostic.Warn;
        internal const ReportDiagnostic DisabledRuleSeverity = ReportDiagnostic.Suppress;
        
        public Dictionary<string, ReportDiagnostic> Convert(ImmutableHashSet<string> activeRules, ImmutableHashSet<string> allRules)
        {
            if (allRules.IsEmpty)
            {
                throw new ArgumentException("No analyzer rules", nameof(allRules));
            }

            var unrecognizedActiveRules = activeRules.Except(allRules).OrderBy(x=> x, StringComparer.OrdinalIgnoreCase).ToArray(); 
            
            if (unrecognizedActiveRules.Any())
            {
                // TODO - log rules
//                throw new ArgumentException($@"Unrecognized active rules: {string.Join(",", unrecognizedActiveRules)}", nameof(activeRules));
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
