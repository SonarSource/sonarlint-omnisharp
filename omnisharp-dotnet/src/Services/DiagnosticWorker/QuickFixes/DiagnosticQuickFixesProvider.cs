/*
 * SonarOmnisharp
 * Copyright (C) 2021-2024 SonarSource SA
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
using System.Composition;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.Extensions.Logging;
using OmniSharp;
using OmniSharp.Models;
using OmniSharp.Options;
using OmniSharp.Roslyn.CSharp.Services.Refactoring.V2;
using SonarLint.OmniSharp.DotNet.Services.Services;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes
{
    public interface IDiagnosticQuickFixesProvider
    {
        /// <summary>
        /// Calculates quick fixes for the given diagnostic in the given document
        /// </summary>
        Task<IQuickFix[]> GetDiagnosticQuickFixes(Diagnostic diagnostic, string filePath);
    }

    [Export(typeof(IDiagnosticQuickFixesProvider)), Shared]
    internal class DiagnosticQuickFixesProvider : BaseCodeActionService<IRequest, IAggregateResponse>, IDiagnosticQuickFixesProvider
    {
        /// <summary>
        /// To be able to mock the behaviour of the "GetFileChangesAsync" method in the base class we needed to add this delegate and pass mock method in tests
        /// </summary>
        internal delegate Task<(Solution Solution, IEnumerable<FileOperationResponse> FileChanges)>
            GetFileChangesAsyncFunc(Solution newSolution, Solution oldSolution, string directory, bool wantTextChanges,
                bool wantsAllCodeActionOperations);

        private readonly ISonarLintDiagnosticCodeActionsProvider diagnosticCodeActionsProvider;
        private readonly OmniSharpWorkspace workspace;
        private readonly GetFileChangesAsyncFunc getFileChangesAsyncFunc;

        [ImportingConstructor]
        public DiagnosticQuickFixesProvider(
            OmniSharpWorkspace workspace,
            [ImportMany] IEnumerable<ISonarAnalyzerCodeActionProvider> codeActionProviders,
            ILoggerFactory loggerFactory,
            OmniSharpOptions options,
            ISonarLintDiagnosticCodeActionsProvider diagnosticCodeActionsProvider) : this(
            workspace,
            codeActionProviders,
            loggerFactory,
            options,
            diagnosticCodeActionsProvider,
            null
        )
        {
        }

        internal DiagnosticQuickFixesProvider(
            OmniSharpWorkspace workspace,
            [ImportMany] IEnumerable<ISonarAnalyzerCodeActionProvider> codeActionProviders,
            ILoggerFactory loggerFactory,
            OmniSharpOptions options,
            ISonarLintDiagnosticCodeActionsProvider diagnosticCodeActionsProvider,
            GetFileChangesAsyncFunc getFileChangesAsyncFunc)
            : base(workspace,
                codeActionProviders,
                loggerFactory.CreateLogger<SonarLintCodeCheckService>(),
                null,
                new CachingCodeFixProviderForProjects(loggerFactory, workspace, codeActionProviders),
                options)
        {
            this.workspace = workspace;
            this.diagnosticCodeActionsProvider = diagnosticCodeActionsProvider;
            this.getFileChangesAsyncFunc = getFileChangesAsyncFunc ?? GetFileChangesAsync;
        }

        /// <summary>
        /// We are inheriting from <see cref="BaseCodeActionService{TRequest,TResponse}"/> so that
        /// we could use <see cref="BaseCodeActionService{TRequest,TResponse}.GetFileChangesAsync"/>.
        /// We don't actually need an endpoint.
        /// </summary>
        public override Task<IAggregateResponse> Handle(IRequest request)
        {
            throw new NotSupportedException("This is a fake endpoint");
        }

        /// <summary>
        /// Based on <see cref="RunCodeActionService.Handle"/>
        /// </summary>
        public async Task<IQuickFix[]> GetDiagnosticQuickFixes(Diagnostic diagnostic, string filePath)
        {
            var document = workspace.GetDocument(filePath);

            if (document == null)
            {
                return Array.Empty<IQuickFix>();
            }

            var codeFixActions = await diagnosticCodeActionsProvider.GetCodeActions(diagnostic, document);
            var quickFixes = new List<IQuickFix>();

            foreach (var action in codeFixActions)
            {
                var fixes = new List<IFix>();
                var directory = Path.GetDirectoryName(filePath);
                var operations = await action.GetOperationsAsync(CancellationToken.None);

                Debug.Assert(operations.Length <= 1, "Expecting quick fixes to contain one or zero operations.");

                if (operations.Length > 1)
                {
                    continue;
                }

                var applyChangesOperations = operations.OfType<ApplyChangesOperation>().SingleOrDefault();
                
                if (applyChangesOperations == null)
                {
                    continue;
                }

                var fileChangesResult = await getFileChangesAsyncFunc(applyChangesOperations.ChangedSolution, workspace.CurrentSolution, directory, true, false);

                Debug.Assert(fileChangesResult.FileChanges.All(c => c is ModifiedFileResponse));

                var fileFixes = fileChangesResult.FileChanges.Select(c => ToFix((ModifiedFileResponse) c));
                fixes.AddRange(fileFixes);

                quickFixes.Add(new QuickFix(action.Title, fixes));
            }

            return quickFixes.ToArray();
        }

        private static Fix ToFix(ModifiedFileResponse modifiedFileResponse)
        {
            return new Fix(modifiedFileResponse.FileName, modifiedFileResponse.Changes.Select(ToEdit).ToArray());

            Edit ToEdit(LinePositionSpanTextChange textChange)
            {
                return new Edit(newText: textChange.NewText,
                    startLine: textChange.StartLine + 1,
                    endLine: textChange.EndLine + 1,
                    startColumn: textChange.StartColumn + 1,
                    endColumn: textChange.EndColumn + 1);
            }
        }
    }
}
