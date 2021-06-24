﻿/*
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
using System.Linq;
using SonarLint.VisualStudio.Core.CSharpVB;

namespace SonarLint.OmniSharp.DotNet.Services.Rules.SonarLintXml
{
    internal interface IRulesToSonarLintConfigurationConverter
    {
        SonarLintConfiguration Convert(IEnumerable<RuleDefinition> rules);
    }

    internal class RulesToSonarLintConfigurationConverter : IRulesToSonarLintConfigurationConverter
    {
        public  SonarLintConfiguration Convert(IEnumerable<RuleDefinition> rules)
        {
            var sonarLintRules = rules.Select(rule => new SonarLintRule
            {
                Key = rule.RuleId,
                Parameters = rule.Parameters?.Select(param =>
                        new SonarLintKeyValuePair
                        {
                            Key = param.Key,
                            Value = param.Value
                        })
                    .ToList()
            }).ToList();

            var sonarLintConfiguration = new SonarLintConfiguration
            {
                Settings = null,
                Rules = sonarLintRules
            };

            return sonarLintConfiguration;
        }
    }
}
