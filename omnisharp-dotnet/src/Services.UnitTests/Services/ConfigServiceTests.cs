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
using Moq;
using Newtonsoft.Json;
using OmniSharp.Mef;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using SonarLint.OmniSharp.DotNet.Services.Services;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.Services
{
    [TestClass]
    public class ConfigServiceTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported <ConfigService, IRequestHandler>(
                     CreateExport<IRuleDefinitionsRepository>());
        }

        [TestMethod]
        public async Task Handle_RulesRepoIsUpdated()
        {
            var repo = new Mock<IRuleDefinitionsRepository>();
            var suppliedRules = new[] { new RuleDefinition { RuleId = "1" } };
            var request = new ConfigRequest { Rules = suppliedRules };

            var testSubject = new ConfigService(repo.Object);

            await testSubject.Handle(request);

            repo.VerifySet(x => x.RuleDefinitions = suppliedRules );
            repo.VerifyNoOtherCalls();
        }

        [TestMethod]
        public void ConfigRequest_Deserialization()
        {
            const string data = @"{
  'rules': [
    {
      'ruleId': '123',
      'isEnabled': true,
      'params': {
        'key': 'value',
        'key2': 'value2'
      }
    },
    {
      'ruleId': 'no params',
      'isEnabled': false,
      'params': null
    }
  ]
}";

            var request = JsonConvert.DeserializeObject<ConfigRequest>(data);

            request.Rules.Length.Should().Be(2);

            request.Rules[0].RuleId.Should().Be("123");
            request.Rules[0].IsEnabled.Should().Be(true);
            request.Rules[0].Parameters.Count.Should().Be(2);
            request.Rules[0].Parameters.Should().BeEquivalentTo(
                new Dictionary<string, string>{ { "key", "value" }, { "key2","value2" } }
                );

            request.Rules[1].RuleId.Should().Be("no params");
            request.Rules[1].IsEnabled.Should().Be(false);
            request.Rules[1].Parameters.Should().BeNull();
        }
    }
}
