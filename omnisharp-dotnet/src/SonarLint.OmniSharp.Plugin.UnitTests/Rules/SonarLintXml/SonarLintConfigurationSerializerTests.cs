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
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.Plugin.Rules.SonarLintXml;
using SonarLint.VisualStudio.Core.CSharpVB;

namespace SonarLint.OmniSharp.Plugin.UnitTests.Rules.SonarLintXml
{
    [TestClass]
    public class SonarLintConfigurationSerializerTests
    {
        [TestMethod]
        public void Serialize_NoRules_ConfigurationSerialized()
        {
            var testSubject = CreateTestSubject();

            var result = testSubject.Serialize(new SonarLintConfiguration());

            result.Should().BeEquivalentTo(@"﻿<?xml version=""1.0"" encoding=""utf-8""?>
<AnalysisInput xmlns:xsi=""http://www.w3.org/2001/XMLSchema-instance"" xmlns:xsd=""http://www.w3.org/2001/XMLSchema"">
  <Settings />
  <Rules />
</AnalysisInput>");
        }
        
        [TestMethod]
        public void Serialize_HasRules_ConfigurationSerialized()
        {
            var testSubject = CreateTestSubject();

            var configuration = new SonarLintConfiguration
            {
                Rules = new List<SonarLintRule>
                {
                    new SonarLintRule {Key = "rule1"},
                    new SonarLintRule
                    {
                        Key = "rule2", Parameters = new List<SonarLintKeyValuePair>
                        {
                            new SonarLintKeyValuePair {Key = "param1", Value = "value1"}
                        }
                    },
                    new SonarLintRule
                    {
                        Key = "rule3", Parameters = new List<SonarLintKeyValuePair>
                        {
                            new SonarLintKeyValuePair {Key = "param2", Value = "value2"},
                            new SonarLintKeyValuePair {Key = "param3", Value = "value3"}
                        }
                    }
                }
            };
            
            var result = testSubject.Serialize(configuration);

            result.Should().BeEquivalentTo(@"﻿<?xml version=""1.0"" encoding=""utf-8""?>
<AnalysisInput xmlns:xsi=""http://www.w3.org/2001/XMLSchema-instance"" xmlns:xsd=""http://www.w3.org/2001/XMLSchema"">
  <Settings />
  <Rules>
    <Rule>
      <Key>rule1</Key>
      <Parameters />
    </Rule>
    <Rule>
      <Key>rule2</Key>
      <Parameters>
        <Parameter>
          <Key>param1</Key>
          <Value>value1</Value>
        </Parameter>
      </Parameters>
    </Rule>
    <Rule>
      <Key>rule3</Key>
      <Parameters>
        <Parameter>
          <Key>param2</Key>
          <Value>value2</Value>
        </Parameter>
        <Parameter>
          <Key>param3</Key>
          <Value>value3</Value>
        </Parameter>
      </Parameters>
    </Rule>
  </Rules>
</AnalysisInput>");
        }
        
        private SonarLintConfigurationSerializer CreateTestSubject() => new SonarLintConfigurationSerializer();
    }
}
