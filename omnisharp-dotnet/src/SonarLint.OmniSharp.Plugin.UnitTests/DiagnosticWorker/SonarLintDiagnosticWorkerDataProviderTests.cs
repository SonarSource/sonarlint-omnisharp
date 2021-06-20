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

using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.OmniSharp.Plugin.Rules;
using SonarLint.VisualStudio.Integration.UnitTests;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintDiagnosticWorkerDataProviderTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            MefTestHelpers.CheckTypeCanBeImported<SonarLintDiagnosticWorkerDataProvider, ISonarLintDiagnosticWorkerDataProvider>(null, new []
            {
                MefTestHelpers.CreateExport<IRuleDefinitionsRepository>(Mock.Of<IRuleDefinitionsRepository>()),
                MefTestHelpers.CreateExport<ISonarAnalyzerCodeActionProvider>(Mock.Of<ISonarAnalyzerCodeActionProvider>())
            });
        }
    }
}