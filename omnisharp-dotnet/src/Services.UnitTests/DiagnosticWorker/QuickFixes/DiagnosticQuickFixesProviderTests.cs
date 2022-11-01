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

using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.Extensions.Logging;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp;
using OmniSharp.Options;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.OmniSharpWorkspaceHelper;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.QuickFixes
{
    [TestClass]
    public class DiagnosticQuickFixesProviderTests
    {
        [TestMethod]
        public async Task Handle_ThrowsNotSupportedException()
        {
            var testSubject = CreateTestSubject();

            Func<Task> act = async () => await testSubject.Handle(null);

            await act.Should().ThrowExactlyAsync<NotSupportedException>();
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DocumentDoesNotExist_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmniSharpWorkspace();
            var diagnosticCodeActionsProvider = new Mock<IDiagnosticCodeActionsProvider>();

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "some file");

            result.Should().BeEmpty();

            diagnosticCodeActionsProvider.Invocations.Count.Should().Be(0);
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasNoCodeActions_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument("test.cs", "content");
            var document = workspace.GetDocument("test.cs");

            var diagnosticCodeActionsProvider = new Mock<IDiagnosticCodeActionsProvider>();
            diagnosticCodeActionsProvider
                .Setup(x => x.GetCodeActions(diagnostic, document))
                .ReturnsAsync(new List<CodeAction>());

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "test.cs");

            result.Should().BeEmpty();

            diagnosticCodeActionsProvider.Verify(x=> x.GetCodeActions(diagnostic, document), Times.Once);
            diagnosticCodeActionsProvider.VerifyNoOtherCalls();
        }

        private DiagnosticQuickFixesProvider CreateTestSubject(
            OmniSharpWorkspace workspace = null,
            IDiagnosticCodeActionsProvider diagnosticCodeActionsProvider = null
        )
        {
            workspace ??= CreateOmniSharpWorkspace();

            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            var codeActionProviders = new[] {codeActionProvider.Object};

            return new DiagnosticQuickFixesProvider(workspace,
                codeActionProviders,
                Mock.Of<ILoggerFactory>(),
                new OmniSharpOptions(),
                diagnosticCodeActionsProvider);
        }
    }
}
