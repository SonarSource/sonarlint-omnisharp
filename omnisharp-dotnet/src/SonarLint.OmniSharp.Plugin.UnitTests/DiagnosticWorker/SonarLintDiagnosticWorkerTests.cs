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

using System.Linq;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Moq;
using OmniSharp;
using OmniSharp.Eventing;
using OmniSharp.FileWatching;
using OmniSharp.Options;
using OmniSharp.Roslyn;
using OmniSharp.Services;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker;
using SonarLint.OmniSharp.Plugin.Rules;
using SonarLint.VisualStudio.Integration.UnitTests;

namespace SonarLint.OmniSharp.Plugin.UnitTests.DiagnosticWorker
{
    [TestClass]
    public class SonarLintDiagnosticWorkerTests
    {
        [TestMethod]
        public void MefCtor_CheckIsExported()
        {
            MefTestHelpers.CheckTypeCanBeImported<SonarLintDiagnosticWorker, ISonarLintDiagnosticWorker>(null, new []
            {
                MefTestHelpers.CreateExport<IRuleDefinitionsRepository>(Mock.Of<IRuleDefinitionsRepository>()),
                MefTestHelpers.CreateExport<OmniSharpWorkspace>(CreateOmniSharpWorkspace()),
                MefTestHelpers.CreateExport<ISonarLintCodeActionProvider>(Mock.Of<ISonarLintCodeActionProvider>()),
                MefTestHelpers.CreateExport<ILoggerFactory>(Mock.Of<ILoggerFactory>()),
                MefTestHelpers.CreateExport<DiagnosticEventForwarder>(new DiagnosticEventForwarder(Mock.Of<IEventEmitter>())),
                MefTestHelpers.CreateExport<IOptionsMonitor<OmniSharpOptions>>(CreateOptionsMonitor())
            });
        }

        private OmniSharpWorkspace CreateOmniSharpWorkspace() =>
            new OmniSharpWorkspace(
                new HostServicesAggregator(Enumerable.Empty<IHostServicesProvider>(), Mock.Of<ILoggerFactory>()),
                Mock.Of<ILoggerFactory>(),
                Mock.Of<IFileSystemWatcher>());

        private static IOptionsMonitor<OmniSharpOptions> CreateOptionsMonitor()
        {
            var optionsMonitor = new Mock<IOptionsMonitor<OmniSharpOptions>>();
            optionsMonitor.Setup(x => x.CurrentValue).Returns(new OmniSharpOptions());
            
            return optionsMonitor.Object;
        }
        
        private SonarLintDiagnosticWorker CreateTestSubject() =>
            new SonarLintDiagnosticWorker(Mock.Of<IRuleDefinitionsRepository>(),
                CreateOmniSharpWorkspace(),
                Mock.Of<ISonarLintCodeActionProvider>(),
                Mock.Of<ILoggerFactory>(),
                new DiagnosticEventForwarder(Mock.Of<IEventEmitter>()),
                CreateOptionsMonitor());
    }
}