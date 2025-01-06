/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource SA
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
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeActions;
using Microsoft.Extensions.Logging;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp;
using OmniSharp.Models;
using OmniSharp.Options;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;
using SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure;
using static System.String;
using static SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes.DiagnosticQuickFixesProvider;
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
            var workspace = CreateOmnisharpWorkspaceWithDocument("some file", "");
            var diagnosticCodeActionsProvider = new Mock<ISonarLintDiagnosticCodeActionsProvider>();

            var mockFunction = new Mock<GetFileChangesAsyncFunc>();

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, mockFunction.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "some other file");

            result.Should().BeEmpty();

            diagnosticCodeActionsProvider.Invocations.Count.Should().Be(0);
            mockFunction.Invocations.Count.Should().Be(0);
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasNoCodeActions_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument("test.cs", "content");
            var document = workspace.GetDocument("test.cs");

            var diagnosticCodeActionsProvider = new Mock<ISonarLintDiagnosticCodeActionsProvider>();
            diagnosticCodeActionsProvider
                .Setup(x => x.GetCodeActions(diagnostic, document))
                .ReturnsAsync(new List<CodeAction>());

            var mockFunction = new Mock<GetFileChangesAsyncFunc>();

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, mockFunction.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "test.cs");

            result.Should().BeEmpty();

            diagnosticCodeActionsProvider.Verify(x=> x.GetCodeActions(diagnostic, document), Times.Once);
            diagnosticCodeActionsProvider.VerifyNoOtherCalls();
            mockFunction.Invocations.Count.Should().Be(0);
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasOneCodeAction_WithMultipleOperations_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument("test.cs", "content");
            var document = workspace.GetDocument("test.cs");

            var operation1 = new OpenDocumentOperation(document.Id);
            var operation2 = new ApplyChangesOperation(workspace.CurrentSolution);
            var codeAction = CreateCodeAction("open message", operation1, operation2);
            var diagnosticCodeActionsProvider = CreateDiagnosticCodeActionsProvider(diagnostic, document, codeAction);
            var mockFunction = new Mock<GetFileChangesAsyncFunc>();

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, mockFunction.Object);

            using (new AssertIgnoreScope())
            {
                var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "test.cs");

                result.Should().BeEmpty();
            }

            diagnosticCodeActionsProvider.Verify(x => x.GetCodeActions(diagnostic, document), Times.Once);
            diagnosticCodeActionsProvider.VerifyNoOtherCalls();
            mockFunction.Invocations.Count.Should().Be(0);
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasOneCodeAction_NoApplyChangesOperation_EmptyList()
        {
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument("test.cs", "content");
            var document = workspace.GetDocument("test.cs");

            var operation = new OpenDocumentOperation(document.Id);
            var codeAction = CreateCodeAction("open message", operation);
            var diagnosticCodeActionsProvider = CreateDiagnosticCodeActionsProvider(diagnostic, document, codeAction);
            var mockFunction = new Mock<GetFileChangesAsyncFunc>();

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, mockFunction.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, "test.cs");

            result.Should().BeEmpty();

            diagnosticCodeActionsProvider.Verify(x => x.GetCodeActions(diagnostic, document), Times.Once);
            diagnosticCodeActionsProvider.VerifyNoOtherCalls();
            mockFunction.Invocations.Count.Should().Be(0);
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasOneCodeAction_ApplyChangesOperation_ReturnsQuickFixes()
        {
            const string filePath = "./test.cs";
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument(filePath, "content");
            var document = workspace.GetDocument(filePath);

            var applyChangesOperation = new ApplyChangesOperation(workspace.CurrentSolution);
            var codeAction = CreateCodeAction("fix message", applyChangesOperation);
            var diagnosticCodeActionsProvider = CreateDiagnosticCodeActionsProvider(diagnostic, document,  codeAction);

            var textChange = CreateTextChange(newText: "New Text", startColumn: 1, endColumn: 2, startLine: 3, endLine: 4);
            var fileResponse = CreateFileResponse(filePath, textChange);

            var getFileChangesFunc = new Mock<GetFileChangesAsyncFunc>();
            SetupGetFileChangesFunc(getFileChangesFunc, workspace.CurrentSolution, fileResponse);

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, getFileChangesFunc.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, filePath);

            result.Length.Should().Be(1);
            result[0].Message.Should().Be("fix message");
            result[0].Fixes.Count.Should().Be(1);
            result[0].Fixes[0].FileName.Should().Be(filePath);
            result[0].Fixes[0].Edits.Count.Should().Be(1);

            result[0].Fixes[0].Edits[0].NewText.Should().Be("New Text");
            result[0].Fixes[0].Edits[0].StartColumn.Should().Be(2);
            result[0].Fixes[0].Edits[0].EndColumn.Should().Be(3);
            result[0].Fixes[0].Edits[0].StartLine.Should().Be(4);
            result[0].Fixes[0].Edits[0].EndLine.Should().Be(5);
            
            getFileChangesFunc.Verify(x=> x(workspace.CurrentSolution, workspace.CurrentSolution, ".", true, false), Times.Once);
            getFileChangesFunc.VerifyNoOtherCalls();
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasOneCodeAction_ApplyChangesOperationWithMultipleEdits_ReturnsQuickFixes()
        {
            const string filePath = "./test.cs";
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument(filePath, "content");
            var document = workspace.GetDocument(filePath);

            var operation = new ApplyChangesOperation(workspace.CurrentSolution);
            var codeAction = CreateCodeAction("fix message", operation);
            var diagnosticCodeActionsProvider = CreateDiagnosticCodeActionsProvider(diagnostic, document, codeAction);

            var textChange1 = CreateTextChange(newText: "New Text 1", startColumn: 1, endColumn: 2, startLine: 3, endLine: 4);
            var textChange2 = CreateTextChange(newText: "New Text 2", startColumn: 5, endColumn: 6, startLine: 7, endLine: 8);
            var fileResponse = CreateFileResponse(filePath, textChange1, textChange2);

            var getFileChangesFunc = new Mock<GetFileChangesAsyncFunc>();
            SetupGetFileChangesFunc(getFileChangesFunc, workspace.CurrentSolution, fileResponse);

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, getFileChangesFunc.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, filePath);

            result.Length.Should().Be(1);
            result[0].Message.Should().Be("fix message");
            result[0].Fixes.Count.Should().Be(1);
            result[0].Fixes[0].FileName.Should().Be(filePath);
            result[0].Fixes[0].Edits.Count.Should().Be(2);

            result[0].Fixes[0].Edits[0].NewText.Should().Be("New Text 1");
            result[0].Fixes[0].Edits[0].StartColumn.Should().Be(2);
            result[0].Fixes[0].Edits[0].EndColumn.Should().Be(3);
            result[0].Fixes[0].Edits[0].StartLine.Should().Be(4);
            result[0].Fixes[0].Edits[0].EndLine.Should().Be(5);

            result[0].Fixes[0].Edits[1].NewText.Should().Be("New Text 2");
            result[0].Fixes[0].Edits[1].StartColumn.Should().Be(6);
            result[0].Fixes[0].Edits[1].EndColumn.Should().Be(7);
            result[0].Fixes[0].Edits[1].StartLine.Should().Be(8);
            result[0].Fixes[0].Edits[1].EndLine.Should().Be(9);

            getFileChangesFunc.Verify(x => x(workspace.CurrentSolution, workspace.CurrentSolution, ".", true, false), Times.Once);
            getFileChangesFunc.VerifyNoOtherCalls();
        }

        [TestMethod]
        public async Task GetDiagnosticQuickFixes_DiagnosticHasMultipleCodeActions_ReturnsQuickFixes()
        {
            const string filePath = "./test.cs";
            var diagnostic = CreateDiagnostic();
            var workspace = CreateOmnisharpWorkspaceWithDocument(filePath, "content");
            var document = workspace.GetDocument(filePath);
            var getFileChangesFunc = new Mock<GetFileChangesAsyncFunc>();

            var solution1 = CreateSolution();
            var applyChangesOperation1 = new ApplyChangesOperation(solution1);
            var codeAction1 = CreateCodeAction("fix message 1", applyChangesOperation1);
            var textChange1 = CreateTextChange(newText: "New Text 1", startColumn: 10, endColumn: 11, startLine: 12, endLine: 13);
            var fileResponse1 = CreateFileResponse(filePath, textChange1);

            SetupGetFileChangesFunc(getFileChangesFunc, solution1, fileResponse1);

            var solution2 = CreateSolution();
            var applyChangesOperation2 = new ApplyChangesOperation(solution2);
            var codeAction2 = CreateCodeAction("fix message 2", applyChangesOperation2);
            var textChange2 = CreateTextChange(newText: "New Text 2", startColumn: 20, endColumn: 21, startLine: 22, endLine: 23);
            var fileResponse2 = CreateFileResponse(filePath, textChange2);

            SetupGetFileChangesFunc(getFileChangesFunc, solution2, fileResponse2);

            var diagnosticCodeActionsProvider = CreateDiagnosticCodeActionsProvider(diagnostic, document, codeAction1, codeAction2);

            var testSubject = CreateTestSubject(workspace, diagnosticCodeActionsProvider.Object, getFileChangesFunc.Object);

            var result = await testSubject.GetDiagnosticQuickFixes(diagnostic, filePath);

            result.Length.Should().Be(2);

            result[0].Message.Should().Be("fix message 1");
            result[0].Fixes.Count.Should().Be(1);
            result[0].Fixes[0].FileName.Should().Be(filePath);
            result[0].Fixes[0].Edits.Count.Should().Be(1);
            result[0].Fixes[0].Edits[0].NewText.Should().Be("New Text 1");
            result[0].Fixes[0].Edits[0].StartColumn.Should().Be(11);
            result[0].Fixes[0].Edits[0].EndColumn.Should().Be(12);
            result[0].Fixes[0].Edits[0].StartLine.Should().Be(13);
            result[0].Fixes[0].Edits[0].EndLine.Should().Be(14);
            getFileChangesFunc.Verify(x => x(solution1, workspace.CurrentSolution, ".", true, false), Times.Once);

            result[1].Message.Should().Be("fix message 2");
            result[1].Fixes.Count.Should().Be(1);
            result[1].Fixes[0].FileName.Should().Be(filePath);
            result[1].Fixes[0].Edits.Count.Should().Be(1);
            result[1].Fixes[0].Edits[0].NewText.Should().Be("New Text 2");
            result[1].Fixes[0].Edits[0].StartColumn.Should().Be(21);
            result[1].Fixes[0].Edits[0].EndColumn.Should().Be(22);
            result[1].Fixes[0].Edits[0].StartLine.Should().Be(23);
            result[1].Fixes[0].Edits[0].EndLine.Should().Be(24);
            getFileChangesFunc.Verify(x => x(solution2, workspace.CurrentSolution, ".", true, false), Times.Once);

            getFileChangesFunc.VerifyNoOtherCalls();
        }

        private DiagnosticQuickFixesProvider CreateTestSubject(
            OmniSharpWorkspace workspace = null,
            ISonarLintDiagnosticCodeActionsProvider diagnosticCodeActionsProvider = null,
            GetFileChangesAsyncFunc mockFunction = null
        )
        {
            workspace ??= CreateOmniSharpWorkspace();

            var codeActionProvider = new Mock<ISonarAnalyzerCodeActionProvider>();
            var codeActionProviders = new[] {codeActionProvider.Object};

            return new DiagnosticQuickFixesProvider(workspace,
                codeActionProviders,
                Mock.Of<ILoggerFactory>(),
                new OmniSharpOptions(),
                diagnosticCodeActionsProvider,
                mockFunction);
        }

        private LinePositionSpanTextChange CreateTextChange(string newText, int startColumn, int endColumn,
            int startLine, int endLine)
        {
            var textChange = new LinePositionSpanTextChange
            {
                NewText = newText,
                StartColumn = startColumn,
                EndColumn = endColumn,
                StartLine = startLine,
                EndLine = endLine
            };
            return textChange;
        }

        private Mock<ISonarLintDiagnosticCodeActionsProvider> CreateDiagnosticCodeActionsProvider(
            Diagnostic diagnostic, Document document,
            params CodeAction[] codeActions)
        {
            var diagnosticCodeActionsProvider = new Mock<ISonarLintDiagnosticCodeActionsProvider>();
            diagnosticCodeActionsProvider.Setup(dap => dap.GetCodeActions(diagnostic, document))
                .ReturnsAsync(codeActions.ToList());

            return diagnosticCodeActionsProvider;
        }

        private TestableCodeAction CreateCodeAction(string title, params CodeActionOperation[] operations)
        {
            return new TestableCodeAction(title, operations);
        }

        private static ModifiedFileResponse CreateFileResponse(string fileName,
            params LinePositionSpanTextChange[] changes)
        {
            var fileResponse = new ModifiedFileResponse(fileName);
            fileResponse.Changes = changes;
            return fileResponse;
        }

        private void SetupGetFileChangesFunc(Mock<GetFileChangesAsyncFunc> mockFunction,
            Solution solution,
            params FileOperationResponse[] fileResponses)
        {
            mockFunction
                .Setup(x => x(solution, It.IsAny<Solution>(), It.IsAny<string>(), It.IsAny<bool>(),
                    It.IsAny<bool>()))
                .ReturnsAsync((solution, fileResponses));
        }
        
        private Solution CreateSolution()
        {
            //this is a hacky way to create solution. We don't care about its content but just that they're different instances. 
            //We can't mock Solution due to not having a parameterless constructor.
            return CreateOmnisharpWorkspaceWithDocument(Empty, Empty).CurrentSolution;
        }

        private class TestableCodeAction : CodeAction
        {
            private readonly IEnumerable<CodeActionOperation> operations;

            internal TestableCodeAction(string title, IEnumerable<CodeActionOperation> operations)
            {
                Title = title;
                this.operations = operations;
            }

            public override string Title { get; }

            protected override async Task<IEnumerable<CodeActionOperation>> ComputeOperationsAsync(CancellationToken cancellationToken)
            {
                return operations;
            }
        }
    }
}
