/*
 * SonarOmnisharp
 * Copyright (C) 2021-2024 SonarSource SA
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

using Newtonsoft.Json;
using OmniSharp.Models;

namespace SonarLint.OmniSharp.DotNet.Services.DiagnosticWorker.AdditionalLocations
{
    internal interface ICodeLocation
    {
        string FileName { get; set; }
        int Line { get; set; }
        int Column { get; set; }
        int EndLine { get; set; }
        int EndColumn { get; set; }
        string Text { get; set; }
    }

    /// <summary>
    /// Based on <see cref="QuickFix"/>
    /// </summary>
    internal sealed class CodeLocation : ICodeLocation
    {
        public string FileName { get; set; }

        [JsonConverter(typeof(ZeroBasedIndexConverter))]
        public int Line { get; set; }

        [JsonConverter(typeof(ZeroBasedIndexConverter))]
        public int Column { get; set; }

        [JsonConverter(typeof(ZeroBasedIndexConverter))]
        public int EndLine { get; set; }

        [JsonConverter(typeof(ZeroBasedIndexConverter))]
        public int EndColumn { get; set; }

        public string Text { get; set; }
    }
}
