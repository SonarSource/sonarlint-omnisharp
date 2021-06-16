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

using System.Collections.Immutable;
using System.ComponentModel.Composition;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using OmniSharp.Services;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker
{
    internal interface ISonarLintHostServicesProvider : IHostServicesProvider
    {
    }
    
    [Export(typeof(ISonarLintHostServicesProvider))]
    [PartCreationPolicy(CreationPolicy.Shared)]
    internal class SonarLintHostServicesProvider : ISonarLintHostServicesProvider
    {
        public ImmutableArray<Assembly> Assemblies { get; }

        [ImportingConstructor]
        public SonarLintHostServicesProvider(IAssemblyLoader loader)
        {
            var builder = ImmutableArray.CreateBuilder<Assembly>();
            var analyzersDirectory = Path.Combine(Path.GetDirectoryName(GetType().Assembly.Location), "analyzers");

            foreach (var filePath in Directory.GetFiles(analyzersDirectory))
            {
                var fullPath = Path.Combine(analyzersDirectory, filePath);
                Debug.Assert(File.Exists(fullPath), $"Analyzer assembly could not be found: {fullPath}");
                builder.Add(loader.LoadFrom(fullPath));
            }

            Assemblies = builder.ToImmutable();
        }
    }
}
