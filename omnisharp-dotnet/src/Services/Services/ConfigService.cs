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

using Microsoft.CodeAnalysis;
using Newtonsoft.Json;
using OmniSharp;
using OmniSharp.Mef;
using SonarLint.OmniSharp.DotNet.Services.Rules;
using System.Composition;
using System.Threading.Tasks;

namespace SonarLint.OmniSharp.DotNet.Services.Services
{
    [OmniSharpEndpoint(ConfigService.ServiceEndpoint, typeof(ConfigRequest), typeof(object))]
    internal class ConfigRequest : IRequest
    {
        [JsonProperty("activeRules")]
        public RuleDefinition[] ActiveRules { get; set; }
    }

    [OmniSharpHandler(ServiceEndpoint, LanguageNames.CSharp)]
    internal class ConfigService : IRequestHandler<ConfigRequest, object>
    {
        internal const string ServiceEndpoint = "/sonarlint/config";

        private readonly IRuleDefinitionsRepository rulesRepository;

        [ImportingConstructor]
        public ConfigService(IRuleDefinitionsRepository rulesRepository)
        {
            this.rulesRepository = rulesRepository;
        }

        public Task<object> Handle(ConfigRequest request)
        {
            rulesRepository.RuleDefinitions = request.ActiveRules;
            return Task.FromResult((object)true);
        }
    }
}
