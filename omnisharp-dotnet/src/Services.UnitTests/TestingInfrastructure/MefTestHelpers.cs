/*
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

using FluentAssertions;
using Moq;
using System;
using System.Collections.Generic;
using System.Composition.Hosting;
using System.Composition.Hosting.Core;
using System.Linq;

namespace SonarLint.OmniSharp.DotNet.Services.UnitTests.TestingInfrastructure
{
    internal static class MefTestHelpers
    {
        public static ExportInfo CreateExport<T>() where T : class => CreateExport<T>(Mock.Of<T>());
        public static ExportInfo CreateExport<T>(object instance) => new(typeof(T), instance);

        /// <summary>
        /// Checks that the expected implementing type was imported.
        /// </summary>
        /// <remarks>This method can be used if the import being tested doesn't specify an explicit contract name.</remarks>
        public static TImportType CheckTypeCanBeImported<TTypeToCheck, TImportType>(
            params ExportInfo[] requiredExports)
            where TTypeToCheck : class
            where TImportType : class
        {
            requiredExports ??= Array.Empty<ExportInfo>();
            var typeToCheck = typeof(TTypeToCheck);
            var importType = typeof(TImportType);

            CheckCompositionFailsIfAnyExportIsMissing(typeToCheck, importType, requiredExports);

            // Now check for a successful import
            var importedObject = TryCompose(typeToCheck, importType, requiredExports);
            importedObject.Should().NotBeNull();
            importedObject.Should().BeAssignableTo<TImportType>();
            return (TImportType)importedObject;
        }

        private static void CheckCompositionFailsIfAnyExportIsMissing(Type typeToCheck, Type importContractType, ExportInfo[] requiredExports)
        {
            for (int i = 0; i < requiredExports.Length; i++)
            {
                // Try importing when not all of the required exports are available -> exception
                var exportToRemove = requiredExports[i];

                Console.WriteLine($"Attempting composition without required export: {exportToRemove.ExportedType.FullName}...");
                var partialExports = new List<ExportInfo>(requiredExports);
                partialExports.Remove(exportToRemove);
                Action act = () => TryCompose(typeToCheck, importContractType, partialExports.ToArray());
                act.Should().Throw<Exception>();
                Console.WriteLine("... composition failed as expected");
            }
        }

        private static object TryCompose(Type typeToCheck, Type importContractType, ExportInfo[] requiredExports)
        {
            var myExportProvider = new DummyExportProvider(requiredExports);

            var config = new ContainerConfiguration()
                .WithPart(typeToCheck)
                .WithProvider(myExportProvider);

            using var container = config.CreateContainer();
            return container.GetExport(importContractType);
        }

        /// <summary>
        /// Describes a single export that we want to be available in the composition
        /// </summary>
        internal class ExportInfo
        {
            public Type ExportedType { get; }
            public object Instance { get; }

            public ExportInfo(Type type, object instance)
            {
                ExportedType = type;
                Instance = instance;
            }
        }

        /// <summary>
        /// Plugs in to the MEF infrastucture to provide exports when asked
        /// </summary>
        private class DummyExportProvider : ExportDescriptorProvider
        {
            private readonly ExportInfo[] exportInfos;

            public DummyExportProvider(params ExportInfo[] exportInfos)
            {
                this.exportInfos = exportInfos;
            }

            public override IEnumerable<ExportDescriptorPromise> GetExportDescriptors(CompositionContract contract, DependencyAccessor descriptorAccessor)
            {
                // If it is a contract we support, return a promise that can create it
                var export = exportInfos.FirstOrDefault(x => x.ExportedType == contract.ContractType);

                if (export == null)
                {
                    // Not an export we recognise
                    return NoExportDescriptors;
                }

                return new[] { new ExportDescriptorPromise(contract, "test origin", false, NoDependencies, _ => new DummyExportDescriptor(export)) };
            }
        }

        private class DummyExportDescriptor : ExportDescriptor
        {
            private readonly ExportInfo exportInfo;

            public DummyExportDescriptor(ExportInfo exportInfo)
            {
                this.exportInfo = exportInfo;
            }

            public override CompositeActivator Activator => (context, operation) => exportInfo.Instance;

            public override IDictionary<string, object> Metadata => throw new NotSupportedException();
        }
    }
}
