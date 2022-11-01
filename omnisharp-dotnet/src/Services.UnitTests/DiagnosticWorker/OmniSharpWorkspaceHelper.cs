﻿/*
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

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.Extensions.Logging;
using Moq;
using OmniSharp.FileWatching;
using OmniSharp.Services;
using OmniSharp;
using Microsoft.CodeAnalysis.Text;
using System.Threading;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker
{
    internal static class OmniSharpWorkspaceHelper
    {
        public static OmniSharpWorkspace CreateOmnisharpWorkspaceWithDocument(string documentFileName, string documentContent)
        {
            var projectInfo = CreateProjectInfo();
            var textLoader = new InMemoryTextLoader();
            var documentInfo = CreateDocumentInfo(projectInfo, textLoader, documentFileName);
            textLoader.AddText(documentInfo.Id, documentContent);

            var workspace = CreateOmniSharpWorkspace();
            workspace.AddProject(projectInfo);
            workspace.AddDocument(documentInfo);

            return workspace;
        }

        public static OmniSharpWorkspace CreateOmniSharpWorkspace() =>
            new(
                new HostServicesAggregator(Enumerable.Empty<IHostServicesProvider>(), Mock.Of<ILoggerFactory>()),
                Mock.Of<ILoggerFactory>(),
                Mock.Of<IFileSystemWatcher>());

        private static ProjectInfo CreateProjectInfo() =>
            ProjectInfo.Create(
                    id: ProjectId.CreateNewId(),
                    version: VersionStamp.Create(),
                    name: "SonarLintTest",
                    assemblyName: "AssemblyName",
                    language: LanguageNames.CSharp,
                    filePath: "dummy.csproj",
                    metadataReferences: new[] { MetadataReference.CreateFromFile(typeof(object).Assembly.Location) },
                    analyzerReferences: Enumerable.Empty<AnalyzerReference>())
                .WithDefaultNamespace("SonarLintTest");

        private static DocumentInfo CreateDocumentInfo(ProjectInfo projectInfo, TextLoader textLoader, string fileName) =>
            DocumentInfo.Create(
                DocumentId.CreateNewId(projectInfo.Id),
                name: fileName,
                loader: textLoader,
                filePath: fileName);

        private class InMemoryTextLoader : TextLoader
        {
            private readonly Dictionary<DocumentId, string> documentsText = new();

            public void AddText(DocumentId documentInfoId, string someText)
            {
                documentsText.Add(documentInfoId, someText);
            }

            public override async Task<TextAndVersion> LoadTextAndVersionAsync(Workspace workspace, DocumentId documentId, CancellationToken cancellationToken)
            {
                var text = documentsText[documentId];
                return TextAndVersion.Create(SourceText.From(text), VersionStamp.Default);
            }
        }
    }
}
