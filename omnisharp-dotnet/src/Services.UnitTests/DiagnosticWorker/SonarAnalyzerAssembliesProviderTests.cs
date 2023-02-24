/*
 * SonarOmnisharp
 * Copyright (C) 2021-2023 SonarSource SA
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
using System.Reflection;
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp.Services;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarAnalyzerAssembliesProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<SonarAnalyzerAssembliesProvider, ISonarAnalyzerAssembliesProvider>(
                CreateExport<IAssemblyLoader>());
        }

        [TestMethod]
        public void Assemblies_AssembliesLoadedFromAnalyzersDirectory()
        {
            var filesInAnalyzerDirectory = new[]
            {
                "analyzer1.dll",
                "analyzer2.dll",
                "analyzer3.dll"
            };

            var filesProvider = CreateFilesProvider(filesInAnalyzerDirectory);

            var dummyAssembly = Assembly.GetExecutingAssembly();
            var assemblyLoader = CreateAssemblyLoader(dummyAssembly);

            var testSubject = CreateTestSubject(assemblyLoader.Object, filesProvider.Object);

            testSubject.Assemblies.Length.Should().Be(filesInAnalyzerDirectory.Length);
            testSubject.Assemblies.Should().AllBeEquivalentTo(dummyAssembly);

            filesProvider.Verify(x=> x(It.IsAny<string>()), Times.Once());

            foreach (var analyzerFilePath in filesInAnalyzerDirectory)
            {
                assemblyLoader.Verify(x => x.LoadFrom(analyzerFilePath, false), Times.Once);
            }
        }

        [TestMethod]
        public void Assemblies_UnloadableAssembly_Throws()
        {
            var filesProvider = CreateFilesProvider("any.dll");
            var assemblyLoader = CreateAssemblyLoader(null);

            var testSubject = CreateTestSubject(assemblyLoader.Object, filesProvider.Object);

            Action act = () => _ = testSubject.Assemblies;

            act.Should().ThrowExactly<InvalidOperationException>()
                .And.Message.Contains("any.dll").Should().BeTrue();
        }

        [TestMethod]
        public void Assemblies_NoAssemblies_Throws()
        {
            var filesProvider = CreateFilesProvider();

            var testSubject = CreateTestSubject(Mock.Of<IAssemblyLoader>(), filesProvider.Object);

            Action act = () => _ = testSubject.Assemblies;

            act.Should().ThrowExactly<InvalidOperationException>()
                .And.Message.Contains(SonarAnalyzerAssembliesProvider.AnalyzersDirectory).Should().BeTrue();
        }

        private static SonarAnalyzerAssembliesProvider CreateTestSubject(IAssemblyLoader assemblyLoader,
            Func<string, string[]> getFiles) => new SonarAnalyzerAssembliesProvider(assemblyLoader, getFiles);

        private static Mock<Func<string, string[]>> CreateFilesProvider(params string[] filesToReturn)
        {
            var filesProvider = new Mock<Func<string, string[]>>();
            filesProvider
                .Setup(x => x(SonarAnalyzerAssembliesProvider.AnalyzersDirectory))
                .Returns(filesToReturn);

            return filesProvider;
        }

        private static Mock<IAssemblyLoader> CreateAssemblyLoader(Assembly assemblyToReturn)
        {
            var assemblyLoader = new Mock<IAssemblyLoader>();
            assemblyLoader
                .Setup(x => x.LoadFrom(It.IsAny<string>(), false))
                .Returns(assemblyToReturn);
            return assemblyLoader;
        }
    }
}
