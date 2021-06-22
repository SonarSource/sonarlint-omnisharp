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

using System.Collections.Immutable;
using System.ComponentModel.Composition;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using OmniSharp.Helpers;
using OmniSharp.Mef;
using OmniSharp.Models;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;

namespace SonarLint.OmniSharp.Plugin.Services
{
    [OmniSharpEndpoint(SonarLintCodeCheckService.ServiceEndpoint, typeof(SonarLintCodeCheckRequest), typeof(QuickFixResponse))]
    internal class SonarLintCodeCheckRequest : Request
    {
    }

    [OmniSharpHandler(ServiceEndpoint, LanguageNames.CSharp)]
    internal class SonarLintCodeCheckService : IRequestHandler<SonarLintCodeCheckRequest, QuickFixResponse>
    {
        internal const string ServiceEndpoint = "/sonarlint/codecheck";

        private readonly ISonarLintDiagnosticWorker diagnosticWorker;

        [ImportingConstructor]
        public SonarLintCodeCheckService(ISonarLintDiagnosticWorker diagnosticWorker)
        {
            this.diagnosticWorker = diagnosticWorker;
        }

        public async Task<QuickFixResponse> Handle(SonarLintCodeCheckRequest request)
        {
            if (string.IsNullOrEmpty(request.FileName))
            {
                var allDiagnostics = await diagnosticWorker.GetAllDiagnosticsAsync();

                return GetResponseFromDiagnostics(allDiagnostics, fileName: null);
            }

            var diagnostics = await diagnosticWorker.GetDiagnostics(ImmutableArray.Create(request.FileName));

            return GetResponseFromDiagnostics(diagnostics, request.FileName);
        }

        private static QuickFixResponse GetResponseFromDiagnostics(ImmutableArray<DocumentDiagnostics> diagnostics, string fileName)
        {
            var diagnosticLocations = diagnostics
                .Where(x => string.IsNullOrEmpty(fileName) || x.DocumentPath == fileName)
                .DistinctDiagnosticLocationsByProject()
                .Where(x => x.FileName != null)
                .ToList();

            return new QuickFixResponse(diagnosticLocations);
        }
    }
}
