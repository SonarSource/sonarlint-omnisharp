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

using System.Collections.Generic;
using System.Collections.Immutable;
using Microsoft.CodeAnalysis;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker.DiagnosticLocation
{
    public static class SonarLintDiagnosticLocationExtensions
    {
        public static ICodeLocation[] ToAdditionalLocations(this Diagnostic diagnostic)
        {
            var additionalLocations = new List<ICodeLocation>();

            for (var i = 0; i < diagnostic.AdditionalLocations.Count; i++)
            {
                var location = diagnostic.AdditionalLocations[i];
                var text = GetLocationMessage(diagnostic, i);
                var additionalLocation = location.ToAdditionalLocation(text);

                additionalLocations.Add(additionalLocation);
            }

            return additionalLocations.ToArray();
        }

        /// <summary>
        /// Based on sonar-dotnet logic:
        /// https://github.com/SonarSource/sonar-dotnet/blob/master/analyzers/src/SonarAnalyzer.Common/Common/SecondaryLocation.cs#L55
        /// </summary>
        private static string GetLocationMessage(Diagnostic diagnostic, int i) => diagnostic.Properties.GetValueOrDefault(i.ToString());

        private static CodeCodeLocation ToAdditionalLocation(this Location location, string text)
        {
            var span = location.GetMappedLineSpan();

            return new CodeCodeLocation
            {
                FileName = span.Path,
                Line = span.StartLinePosition.Line,
                Column = span.StartLinePosition.Character,
                EndLine = span.EndLinePosition.Line,
                EndColumn = span.EndLinePosition.Character,
                Text = text
            };
        }
    }
}
