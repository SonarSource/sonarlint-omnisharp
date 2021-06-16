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
using System.IO;
using System.Threading;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Text;
using SonarLint.OmniSharp.Plugin.Rules.SonarLintXml;

namespace SonarLint.OmniSharp.Plugin.Rules
{
    internal interface IRulesToAdditionalTextConverter
    {
        AdditionalText Convert(IEnumerable<RuleDefinition> rules);
    }

    internal class RulesToAdditionalTextConverter : IRulesToAdditionalTextConverter
    {
        /// <summary>
        /// The file will never be written to disk so the path is irrelevant.
        /// It only needs to be named 'SonarLint.Xml' so the sonar-dotnet analyzers could load it.
        /// </summary>
        private static readonly string DummySonarLintXmlFilePath = Path.Combine(Path.GetTempPath(), "SonarLint.xml");

        private readonly IRulesToSonarLintConfigurationConverter rulesToSonarLintConfigurationConverter;
        private readonly ISonarLintConfigurationSerializer sonarLintConfigurationSerializer;

        public RulesToAdditionalTextConverter()
            : this(new RulesToSonarLintConfigurationConverter(), new SonarLintConfigurationSerializer())
        {
        }

        internal RulesToAdditionalTextConverter(IRulesToSonarLintConfigurationConverter rulesToSonarLintConfigurationConverter,
            ISonarLintConfigurationSerializer sonarLintConfigurationSerializer)
        {
            this.rulesToSonarLintConfigurationConverter = rulesToSonarLintConfigurationConverter;
            this.sonarLintConfigurationSerializer = sonarLintConfigurationSerializer;
        }

        public AdditionalText Convert(IEnumerable<RuleDefinition> rules)
        {
            var sonarLintConfiguration = rulesToSonarLintConfigurationConverter.Convert(rules);
            var sonarLintXmlFileContent = sonarLintConfigurationSerializer.Serialize(sonarLintConfiguration);
            var sonarLintXmlAdditionalText = new AdditionalTextImpl(DummySonarLintXmlFilePath, sonarLintXmlFileContent);

            return sonarLintXmlAdditionalText;
        }

        // There isn't a public implementation of source text so we need to create one
        private class AdditionalTextImpl : AdditionalText
        {
            private readonly SourceText sourceText;

            public AdditionalTextImpl(string path, string content)
            {
                Path = path;
                sourceText = SourceText.From(content);
            }

            public override string Path { get; }

            public override SourceText GetText(CancellationToken cancellationToken = default) => sourceText;
        }
    }
}
