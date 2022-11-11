/*
 * omnisharp-roslyn
 * Copyright (c) .NET Foundation and Contributors All Rights Reserved
 * https://github.com/OmniSharp/omnisharp-roslyn/blob/master/license.md
 */

using Microsoft.CodeAnalysis;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Threading.Tasks;
using SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.QuickFixes;

namespace OmniSharp.Helpers
{
    /// <summary>
    /// Copied from OmniSharp.Helpers.DiagnosticExtensions and modified to support secondary locations and quick fixes
    /// https://github.com/OmniSharp/omnisharp-roslyn/blob/master/src/OmniSharp.Roslyn.CSharp/Helpers/DiagnosticExtensions.cs
    /// </summary>
    internal static class DiagnosticExtensions
    {
        private static readonly ImmutableHashSet<string> _tagFilter =
            ImmutableHashSet.Create("Unnecessary", "Deprecated");

        internal static SonarLintDiagnosticLocation ToDiagnosticLocation(this Diagnostic diagnostic, IQuickFix[] quickFixes)
        {
            var span = diagnostic.Location.GetMappedLineSpan();

            return new SonarLintDiagnosticLocation
            {
                FileName = span.Path,
                Line = span.StartLinePosition.Line,
                Column = span.StartLinePosition.Character,
                EndLine = span.EndLinePosition.Line,
                EndColumn = span.EndLinePosition.Character,
                Text = diagnostic.GetMessage(),
                LogLevel = diagnostic.Severity.ToString(),
                Tags = diagnostic
                    .Descriptor.CustomTags
                    .Where(x => _tagFilter.Contains(x))
                    .ToArray(),
                Id = diagnostic.Id,
                AdditionalLocations = diagnostic.ToAdditionalLocations(),
                QuickFixes = quickFixes
            };
        }

        internal static async Task<IEnumerable<SonarLintDiagnosticLocation>> DistinctDiagnosticLocationsByProject(this IEnumerable<DocumentDiagnostics> documentDiagnostic, IDiagnosticQuickFixesProvider quickFixesProvider)
        {
            var diagnostics = documentDiagnostic
                .SelectMany(x => x.Diagnostics, (parent, child) => (document: parent, diagnostic: child))
                .ToList();

            var diagnosticQuickFixes = new Dictionary<Diagnostic, IQuickFix[]>();

            foreach (var (document, diagnostic) in diagnostics)
            {
                var quickFixes = await quickFixesProvider.GetDiagnosticQuickFixes(diagnostic, document.DocumentPath);
                diagnosticQuickFixes[diagnostic] = quickFixes;
            }

            return diagnostics
                .Select(x => new
                {
                    location = x.diagnostic.ToDiagnosticLocation(diagnosticQuickFixes[x.diagnostic]),
                    project = x.document.ProjectName
                })
                .GroupBy(x => x.location)
                .Select(x =>
                {
                    var location = x.First().location;
                    location.Projects = x.Select(a => a.project).ToList();
                    return location;
                });
        }
    }
}
