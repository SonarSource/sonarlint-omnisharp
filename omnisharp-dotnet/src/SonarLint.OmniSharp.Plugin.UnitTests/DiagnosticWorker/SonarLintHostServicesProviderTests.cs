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

using System.Reflection;
using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp.Services;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.VisualStudio.Integration.UnitTests;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintHostServicesProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            MefTestHelpers.CheckTypeCanBeImported<SonarLintHostServicesProvider, ISonarLintHostServicesProvider>(null, new []
            {
                MefTestHelpers.CreateExport<IAssemblyLoader>(Mock.Of<IAssemblyLoader>())
            });
        }

        [TestMethod]
        public void Assemblies_LoadsSonarDotnetAssemblies()
        {
            var dummyAssembly = Assembly.GetExecutingAssembly();
            
            var assemblyLoader = new Mock<IAssemblyLoader>();
            assemblyLoader
                .Setup(x => x.LoadFrom(It.IsAny<string>(), false))
                .Returns(dummyAssembly);

            var expectedAssemblies = new[]
            {
                "SonarAnalyzer.dll",
                "SonarAnalyzer.CSharp.dll",
                "SonarAnalyzer.CFG.dll",
                "Google.Protobuf.dll"
            };
            
            var testSubject = CreateTestSubject(assemblyLoader.Object);
            testSubject.Assemblies.Length.Should().Be(expectedAssemblies.Length);
            testSubject.Assemblies.Should().AllBeEquivalentTo(dummyAssembly);

            foreach (var expectedAssembly in expectedAssemblies)
            {
                assemblyLoader.Verify(x =>
                        x.LoadFrom(It.Is((string path) => path.EndsWith(expectedAssembly)), false),
                    Times.Once);
            }
            
            assemblyLoader.Invocations.Count.Should().Be(expectedAssemblies.Length);
            assemblyLoader.VerifyNoOtherCalls();
        }

        private SonarLintHostServicesProvider CreateTestSubject(IAssemblyLoader assemblyLoader) => new SonarLintHostServicesProvider(assemblyLoader);
    }
}
