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

using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using SonarLint.OmniSharp.Plugin.Services;
using System.Threading.Tasks;

namespace SonarLint.OmniSharp.Plugin.UnitTests.Services
{
    [TestClass]
    public class PingServiceTests
    {
        [TestMethod, Ignore] // see below
        public void MefCtor_CheckIsExported()
        {
            // For some reason our normal MEF test helper doesn't work with this type.
            // To illustrate the problem, the TypeCatalog should discover one "Part" on the
            // type (which is what happens with e.g. ISonarAnalyzerCodeActionProvider in this assembly).
            // It doesn't discover any.
            // The only obvious difference with PingSonarLintExtensionService is that it is using a 
            // custom Export attribute type, rather than just "[Export(...)]"
            // However, the new service *is* discovered when running in OmniSharp.
            // var typeCatalog = new TypeCatalog(typeof(PingService));
            // var compositionElement = (ICompositionElement)typeCatalog;
            // compositionElement.DisplayName.Should().NotContain("Empty");

            // MefTestHelpers.CheckTypeCanBeImported<PingService, IRequestHandler>(null, null);
        }

        [TestMethod]
        public async Task TestHandle()
        {
            var testSubject = new PingService();
            var response = await testSubject.Handle(new PingRequest());
            response.Message.Should().NotBeNullOrEmpty();
        }
    }
}
