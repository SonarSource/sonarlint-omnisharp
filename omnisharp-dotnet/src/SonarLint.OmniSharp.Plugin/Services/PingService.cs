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
using OmniSharp.Mef;
using OmniSharp.Models;
using System.Threading.Tasks;

// Adds a "ping_sonarlint" endpoint for testing/debugging

namespace SonarLint.OmniSharp.Plugin.Services
{
    [OmniSharpEndpoint(PingService.ServiceEndpoint, typeof(PingRequest), typeof(PingResponse))]
    public class PingRequest : Request
    {
    }

    public class PingResponse
    {
        public string Message { get; set; }
    }

    [OmniSharpHandler(ServiceEndpoint, LanguageNames.CSharp)]
    public class PingService : IRequestHandler<PingRequest, PingResponse>
    {
        internal const string ServiceEndpoint = "/sonarlint/ping";

        public Task<PingResponse> Handle(PingRequest request)
        {
            var response = new PingResponse { Message = $"SonarLint OmniSharp extension is loaded {System.DateTime.Now}" };
            return Task.FromResult(response);
        }
    }
}
