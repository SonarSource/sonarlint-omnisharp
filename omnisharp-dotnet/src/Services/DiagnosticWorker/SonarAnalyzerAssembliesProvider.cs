/*
 * SonarOmnisharp
 * Copyright (C) 2021-2025 SonarSource Sàrl
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

using OmniSharp.Services;
using System;
using System.Collections.Immutable;
using System.Composition;
using System.IO;
using System.Reflection;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker
{
    /// <summary>
    /// Provide sonar-dotnet analyzer assemblies
    /// </summary>
    internal interface ISonarAnalyzerAssembliesProvider
    {
        ImmutableArray<Assembly> Assemblies { get; }
    }

    [Export(typeof(ISonarAnalyzerAssembliesProvider)), Shared]
    internal class SonarAnalyzerAssembliesProvider : ISonarAnalyzerAssembliesProvider
    {
        /// <summary>
        /// It is the responsibility of the java plugin to place the analyzer assemblies in the "analyzers" sub directory.
        /// </summary>
        internal static string AnalyzersDirectory { get; } =
            Path.Combine(Path.GetDirectoryName(typeof(SonarAnalyzerAssembliesProvider).Assembly.Location), "analyzers");

        private readonly IAssemblyLoader loader;
        private readonly Func<string, string[]> getFilesInDirectory;
        private ImmutableArray<Assembly> loadedAssemblies;

        public ImmutableArray<Assembly> Assemblies
        {
            get
            {
                if (loadedAssemblies == null)
                {
                    loadedAssemblies = LoadAssemblies();
                }

                return loadedAssemblies;
            }
        }

        [ImportingConstructor]
        public SonarAnalyzerAssembliesProvider(IAssemblyLoader loader)
            : this(loader, Directory.GetFiles)
        {
        }

        internal SonarAnalyzerAssembliesProvider(IAssemblyLoader loader, Func<string, string[]> getFilesInDirectory)
        {
            this.loader = loader;
            this.getFilesInDirectory = getFilesInDirectory;
        }

        private ImmutableArray<Assembly> LoadAssemblies()
        {
            var builder = ImmutableArray.CreateBuilder<Assembly>();

            foreach (var filePath in getFilesInDirectory(AnalyzersDirectory))
            {
                var analyzerAssembly = loader.LoadFrom(filePath);

                if (analyzerAssembly == null)
                {
                    var message = string.Format(Resources.DiagWorker_Error_UnloadableAssembly, filePath);
                    throw new InvalidOperationException(message);
                }

                builder.Add(analyzerAssembly);
            }

            if (builder.Count == 0)
            {
                var message = string.Format(Resources.DiagWorker_Error_NoAnalyzerAssemblies, AnalyzersDirectory);
                throw new InvalidOperationException(message);
            }

            return builder.ToImmutable();
        }
    }
}
