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
using System.Collections.Immutable;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.CodeAnalysis.CodeFixes;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.OmniSharpWorkspaceHelper;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker.QuickFixes
{
    [TestClass]
    public class SonarLintDiagnosticCodeActionsProviderTests
    {
        [TestMethod]
        public async Task GetCodeActions_NoCodeActionProviders_EmptyList()
        {
            var codeActionProviders = Array.Empty<ISonarAnalyzerCodeActionProvider>();

            var result = await GetCodeActions(codeActionProviders);

            result.Should().BeEmpty();
        }

        [TestMethod]
        public async Task GetCodeActions_NoCodeFixProviders_EmptyList()
        {
            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            codeActionProvider
                .Setup(x => x.CodeFixProviders)
                .Returns(new CodeFixProvider[] { }.ToImmutableArray);

            var result = await GetCodeActions(codeActionProvider.Object);

            result.Should().BeEmpty();
        }

        [TestMethod]
        public async Task GetCodeActions_NoApplicableCodeFixProviders_EmptyList()
        {
            var codeFixProvider = new Mock<CodeFixProvider>();
            codeFixProvider
                .Setup(x => x.FixableDiagnosticIds)
                .Returns(new[] {"some other diagnostic id"}.ToImmutableArray);

            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            codeActionProvider
                .Setup(x => x.CodeFixProviders)
                .Returns(new[] {codeFixProvider.Object}.ToImmutableArray);

            var result = await GetCodeActions(codeActionProvider.Object);

            result.Should().BeEmpty();

            codeFixProvider.Verify(x=> x.RegisterCodeFixesAsync(It.IsAny<CodeFixContext>()), Times.Never);
        }

        [TestMethod]
        public async Task GetCodeActions_HasApplicableCodeFixProvider_ActionsRegistered()
        {
            var applicableDiagnosticId = WellKnownDescriptor.Id;

            var codeFixProvider = new Mock<CodeFixProvider>();
            codeFixProvider
                .Setup(x => x.FixableDiagnosticIds)
                .Returns(new[] {applicableDiagnosticId}.ToImmutableArray);

            CodeFixContext passedContext = default;
            codeFixProvider
                .Setup(x => x.RegisterCodeFixesAsync(It.IsAny<CodeFixContext>()))
                .Callback((CodeFixContext context) => passedContext = context) 
                .Returns(Task.CompletedTask);

            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            codeActionProvider
                .Setup(x => x.CodeFixProviders)
                .Returns(new[] {codeFixProvider.Object}.ToImmutableArray);

            var result = await GetCodeActions(codeActionProvider.Object);

            var action1 = Mock.Of<CodeAction>();
            var action2 = Mock.Of<CodeAction>();

            passedContext.RegisterCodeFix(action1, CreateDiagnostic());
            passedContext.RegisterCodeFix(action2, CreateDiagnostic());

            result.Count.Should().Be(2);
            result[0].Should().Be(action1);
            result[1].Should().Be(action2);
        }

        private async Task<List<CodeAction>> GetCodeActions(params ISonarAnalyzerCodeActionProvider[] actionProviders)
        {
            var testSubject = new SonarLintDiagnosticCodeActionsProvider(actionProviders);

            var diagnostic = CreateDiagnostic();
            var document = CreateDocument();

            var result = await testSubject.GetCodeActions(diagnostic, document);

            return result;
        }

        private Document CreateDocument()
        {
            var workspace = CreateOmnisharpWorkspaceWithDocument("test.cs", "");
            return workspace.GetDocument("test.cs");
        }
    }
}
