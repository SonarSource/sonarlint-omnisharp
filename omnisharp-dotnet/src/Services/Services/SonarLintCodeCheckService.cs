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

using System.Collections.Immutable;
using System.Composition;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using OmniSharp.Mef;
using OmniSharp.Models;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;

namespace SonarLint.OmniSharp.DotNet.Services.Services
{
    [OmniSharpEndpoint(SonarLintCodeCheckService.ServiceEndpoint, typeof(SonarLintCodeCheckRequest), typeof(QuickFixResponse))]
    internal class SonarLintCodeCheckRequest : Request
    {
    }

    /// <summary>
    /// This service is intended to behave as <see cref="CodeCheckService"/>, except with our own <see cref="ISonarLintDiagnosticWorker"/>.
    /// </summary>
    [OmniSharpHandler(ServiceEndpoint, LanguageNames.CSharp)]
    internal class SonarLintCodeCheckService : IRequestHandler<SonarLintCodeCheckRequest, QuickFixResponse>
    {
        internal const string ServiceEndpoint = "/sonarlint/codecheck";

        private readonly ISonarLintDiagnosticWorker diagnosticWorker;
        private readonly IDiagnosticsToCodeLocationsConverter diagnosticsToCodeLocationsConverter;

        [ImportingConstructor]
        public SonarLintCodeCheckService(ISonarLintDiagnosticWorker diagnosticWorker, 
            IDiagnosticsToCodeLocationsConverter diagnosticsToCodeLocationsConverter)
        {
            this.diagnosticWorker = diagnosticWorker;
            this.diagnosticsToCodeLocationsConverter = diagnosticsToCodeLocationsConverter;
        }

        public async Task<QuickFixResponse> Handle(SonarLintCodeCheckRequest request)
        {
            var diagnostics = string.IsNullOrEmpty(request.FileName)
                ? await diagnosticWorker.GetAllDiagnosticsAsync()
                : await diagnosticWorker.GetDiagnostics(ImmutableArray.Create(request.FileName));

            // todo: does it matter if the file name is null or empty?
            var diagnosticLocations = await diagnosticsToCodeLocationsConverter.Convert(diagnostics, request.FileName);

            return new QuickFixResponse(diagnosticLocations);
        }
    }
}
