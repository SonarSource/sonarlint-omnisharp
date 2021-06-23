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

using System.Collections.Generic;
using Newtonsoft.Json;
using OmniSharp.Models;

namespace SonarLint.OmniSharp.Plugin.DiagnosticWorker.DiagnosticLocation
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

        private bool Equals(CodeLocation other)
        {
            return FileName == other.FileName
                   && Line == other.Line
                   && Column == other.Column
                   && EndLine == other.EndLine
                   && EndColumn == other.EndColumn
                   && Text == other.Text;
        }

        public override bool Equals(object obj)
        {
            if (ReferenceEquals(null, obj)) return false;
            if (ReferenceEquals(this, obj)) return true;
            if (obj.GetType() != this.GetType()) return false;
            return Equals((CodeLocation) obj);
        }

        public override int GetHashCode()
        {
            unchecked
            {
                var hashCode = (FileName != null ? FileName.GetHashCode() : 0);
                hashCode = (hashCode * 397) ^ Line;
                hashCode = (hashCode * 397) ^ Column;
                hashCode = (hashCode * 397) ^ EndLine;
                hashCode = (hashCode * 397) ^ EqualityComparer<string>.Default.GetHashCode(Text);
                return hashCode;
            }
        }
    }
}
