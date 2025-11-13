/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource Sàrl
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
using System.Composition;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.CodeAnalysis.CodeFixes;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes
{
    internal interface ISonarLintDiagnosticCodeActionsProvider
    {
        /// <summary>
        /// Returns a list of available code fixes for a diagnostic in a given document
        /// </summary>
        Task<List<CodeAction>> GetCodeActions(Diagnostic diagnostic, Document document);
    }

    [Export(typeof(ISonarLintDiagnosticCodeActionsProvider))]
    [Shared]
    internal class SonarLintDiagnosticCodeActionsProvider : ISonarLintDiagnosticCodeActionsProvider
    {
        private readonly CodeFixProvider[] codeFixProviders;

        [ImportingConstructor]
        public SonarLintDiagnosticCodeActionsProvider([ImportMany] IEnumerable<ISonarAnalyzerCodeActionProvider> codeActionProviders)
        {
            codeFixProviders = codeActionProviders.SelectMany(x => x.CodeFixProviders).ToArray();
        }

        /// <summary>
        /// Based on <see cref="BaseCodeActionService{TRequest,TResponse}.AppendFixesAsync"/>
        /// </summary>
        public async Task<List<CodeAction>> GetCodeActions(Diagnostic diagnostic, Document document)
        {
            var applicableFixProviders = codeFixProviders.Where(x => x.FixableDiagnosticIds.Any(id => id == diagnostic.Id));

            var actions = new List<CodeAction>();

            foreach (var codeFixProvider in applicableFixProviders)
            {
                var context = new CodeFixContext(document, diagnostic, (action, _) => actions.Add(action), default);
                await codeFixProvider.RegisterCodeFixesAsync(context);
            }

            return actions;
        }
    }
}
