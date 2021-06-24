/*
 * omnisharp-roslyn
 * Copyright (c) .NET Foundation and Contributors All Rights Reserved
 * https://github.com/OmniSharp/omnisharp-roslyn/blob/master/license.md
 */

using Microsoft.CodeAnalysis;
using OmniSharp.Models.Diagnostics;
using OmniSharp.Roslyn.CSharp.Services.Diagnostics;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using SonarLint.OmniSharp.Plugin.DiagnosticWorker.AdditionalLocations;

namespace OmniSharp.Helpers
{
    /// <summary>
    /// Copied from OmniSharp.Helpers.DiagnosticExtensions and modified to support secondary locations
    /// https://github.com/OmniSharp/omnisharp-roslyn/blob/master/src/OmniSharp.Roslyn.CSharp/Helpers/DiagnosticExtensions.cs
    /// </summary>
    internal static class DiagnosticExtensions
    {
        private static readonly ImmutableHashSet<string> _tagFilter =
            ImmutableHashSet.Create("Unnecessary", "Deprecated");

        internal static SonarLintDiagnosticLocation ToDiagnosticLocation(this Diagnostic diagnostic)
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
                AdditionalLocations = diagnostic.ToAdditionalLocations()
            };
        }

        internal static IEnumerable<SonarLintDiagnosticLocation> DistinctDiagnosticLocationsByProject(this IEnumerable<DocumentDiagnostics> documentDiagnostic)
        {
            return documentDiagnostic
                .SelectMany(x => x.Diagnostics, (parent, child) => (projectName: parent.ProjectName, diagnostic: child))
                .Select(x => new
                {
                    location = x.diagnostic.ToDiagnosticLocation(),
                    project = x.projectName
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