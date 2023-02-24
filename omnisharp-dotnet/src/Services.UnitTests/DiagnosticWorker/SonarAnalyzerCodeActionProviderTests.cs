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

using FluentAssertions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CodeFixes;
using Microsoft.CodeAnalysis.CodeRefactorings;
using Microsoft.CodeAnalysis.Diagnostics;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker;
using System.Collections.Immutable;
using System.Reflection;
using System.Threading.Tasks;
using static SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure.MefTestHelpers;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarAnalyzerCodeActionProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            CheckTypeCanBeImported<SonarAnalyzerCodeActionProvider, ISonarAnalyzerCodeActionProvider>(
                CreateExport<ISonarAnalyzerAssembliesProvider>(SetupAssembliesProvider().Object));
        }

        [TestMethod]
        public void ProviderName_ReturnsSonarLint()
        {
            var testSubject = CreateTestSubject();

            testSubject.ProviderName.Should().Be("SonarLint");
        }

        [TestMethod]
        public void Assemblies_ReturnsGivenAssemblies()
        {
            var testAssemblies = new[]
            {
                Assembly.GetAssembly(typeof(SonarAnalyzerCodeActionProvider)),
                Assembly.GetAssembly(GetType()),
                Assembly.GetAssembly(typeof(ImmutableDictionary))
            };

            var testSubject = CreateTestSubject(testAssemblies);

            testSubject.Assemblies.Should().BeEquivalentTo(testAssemblies);
        }

        [TestMethod]
        public void CodeDiagnosticAnalyzerProviders_LoadsFromGivenAssemblies()
        {
            var testSubject = CreateTestSubject(GetType().Assembly);

            // There are other test analyzers in the project, so we can't compare the exact expected number and items
            testSubject.CodeDiagnosticAnalyzerProviders.Should().Contain(x => x is DummyAnalyzer1);
            testSubject.CodeDiagnosticAnalyzerProviders.Should().Contain(x => x is DummyAnalyzer2);
        }

        [TestMethod]
        public void CodeRefactoringProviders_LoadsFromGivenAssemblies()
        {
            var testSubject = CreateTestSubject(GetType().Assembly);

            testSubject.CodeRefactoringProviders.Length.Should().Be(2);
            testSubject.CodeRefactoringProviders[0].Should().BeOfType<DummyCodeRefactoringProvider1>();
            testSubject.CodeRefactoringProviders[1].Should().BeOfType<DummyCodeRefactoringProvider2>();
        }

        [TestMethod]
        public void CodeFixProviders_LoadsFromGivenAssemblies()
        {
            var testSubject = CreateTestSubject(GetType().Assembly);

            testSubject.CodeFixProviders.Length.Should().Be(2);
            testSubject.CodeFixProviders[0].Should().BeOfType<DummyCodeFixProvider1>();
            testSubject.CodeFixProviders[1].Should().BeOfType<DummyCodeFixProvider2>();
        }

        private static SonarAnalyzerCodeActionProvider CreateTestSubject(params Assembly[] assemblies)
        {
            var hostServicesProvider = SetupAssembliesProvider(assemblies);

            return new SonarAnalyzerCodeActionProvider(hostServicesProvider.Object);
        }

        private static Mock<ISonarAnalyzerAssembliesProvider> SetupAssembliesProvider(params Assembly[] assemblies)
        {
            var sonarLintHostServicesProvider = new Mock<ISonarAnalyzerAssembliesProvider>();
            sonarLintHostServicesProvider.Setup(x => x.Assemblies).Returns(assemblies.ToImmutableArray());

            return sonarLintHostServicesProvider;
        }

        #region Helper Classes

        private class DummyAnalyzer1 : DiagnosticAnalyzer
        {
            public override void Initialize(AnalysisContext context)
            {
            }

            public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics { get; }
        }

        private class DummyAnalyzer2 : DummyAnalyzer1
        {
        }

        private class DummyCodeRefactoringProvider1 : CodeRefactoringProvider
        {
            public override Task ComputeRefactoringsAsync(CodeRefactoringContext context)
            {
                return Task.CompletedTask;
            }
        }

        private class DummyCodeRefactoringProvider2 : DummyCodeRefactoringProvider1
        {
        }

        private class DummyCodeFixProvider1 : CodeFixProvider
        {
            public override Task RegisterCodeFixesAsync(CodeFixContext context)
            {
                return Task.CompletedTask;
            }

            public override ImmutableArray<string> FixableDiagnosticIds { get; }
        }

        private class DummyCodeFixProvider2 : DummyCodeFixProvider1
        {
        }

        #endregion
    }
}
