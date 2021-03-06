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
package org.sonarsource.sonarlint.omnisharp;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.sonar.api.SonarRuntime;
import org.sonar.api.rules.RuleType;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinitionXmlLoader;
import org.sonar.api.utils.Version;
import org.sonarsource.analyzer.commons.BuiltInQualityProfileJsonLoader;
import org.sonarsource.dotnet.shared.plugins.AbstractRulesDefinition;

import static org.sonarsource.sonarlint.omnisharp.OmnisharpPlugin.LANGUAGE_KEY;
import static org.sonarsource.sonarlint.omnisharp.OmnisharpPlugin.PLUGIN_KEY;
import static org.sonarsource.sonarlint.omnisharp.OmnisharpPlugin.REPOSITORY_KEY;
import static org.sonarsource.sonarlint.omnisharp.OmnisharpPlugin.REPOSITORY_NAME;

@ScannerSide
public class CSharpSonarRulesDefinition implements RulesDefinition {
  private static final Gson GSON = new Gson();
  private static final String RULES_XML = "/org/sonar/plugins/csharp/rules.xml";

  private final boolean isOwaspByVersionSupported;

  private static String getSonarWayJsonPath() {
    return "org/sonar/plugins/" + PLUGIN_KEY + "/Sonar_way_profile.json";
  }

  public CSharpSonarRulesDefinition(SonarRuntime sonarRuntime) {
    this.isOwaspByVersionSupported = sonarRuntime.getApiVersion().isGreaterThanOrEqual(Version.create(9, 3));
  }

  @Override
  public void define(Context context) {
    NewRepository repository = context
      .createRepository(REPOSITORY_KEY, LANGUAGE_KEY)
      .setName(REPOSITORY_NAME);

    RulesDefinitionXmlLoader loader = new RulesDefinitionXmlLoader();
    withRulesXml(reader -> loader.load(repository, reader));

    setupHotspotRules(repository.rules());
    activeDefaultRules(repository.rules());

    repository.done();
  }

  public static void withRulesXml(Consumer<InputStreamReader> consumer) {
    try (InputStreamReader reader = new InputStreamReader(CSharpSonarRulesDefinition.class.getResourceAsStream(RULES_XML), StandardCharsets.UTF_8)) {
      consumer.accept(reader);
    } catch (IOException e) {
      throw new IllegalStateException("Error reading rules.xml", e);
    }
  }

  private static String getRuleJson(String ruleKey) {
    return "/org/sonar/plugins/csharp/" + ruleKey + "_c#.json";
  }

  private void setupHotspotRules(Collection<NewRule> rules) {
    Map<NewRule, RuleMetadata> allRuleMetadata = rules.stream()
      .collect(Collectors.toMap(rule -> rule, rule -> readRuleMetadata(rule.key())));

    Set<NewRule> hotspotRules = getHotspotRules(allRuleMetadata);

    allRuleMetadata.forEach(this::updateSecurityStandards);
    hotspotRules.forEach(rule -> rule.setType(RuleType.SECURITY_HOTSPOT));
  }

  private static void activeDefaultRules(Collection<NewRule> rules) {
    Set<String> activeKeys = BuiltInQualityProfileJsonLoader.loadActiveKeysFromJsonProfile(getSonarWayJsonPath());
    rules.forEach(rule -> rule.setActivatedByDefault(activeKeys.contains(rule.key())));
  }

  private static Set<NewRule> getHotspotRules(Map<NewRule, RuleMetadata> allRuleMetadata) {
    return allRuleMetadata.entrySet()
      .stream()
      .filter(entry -> entry.getValue().isSecurityHotspot())
      .map(Map.Entry::getKey)
      .collect(Collectors.toSet());
  }

  private void updateSecurityStandards(NewRule rule, RuleMetadata ruleMetadata) {
    for (String s : ruleMetadata.securityStandards.owasp2017) {
      rule.addOwaspTop10(RulesDefinition.OwaspTop10.valueOf(s));
    }
    if (isOwaspByVersionSupported) {
      for (String s : ruleMetadata.securityStandards.owasp2021) {
        rule.addOwaspTop10(RulesDefinition.OwaspTop10Version.Y2021, RulesDefinition.OwaspTop10.valueOf(s));
      }
    }
    rule.addCwe(ruleMetadata.securityStandards.cwe);
  }

  private static RuleMetadata readRuleMetadata(String ruleKey) {
    String resourcePath = getRuleJson(ruleKey);
    try (InputStream stream = AbstractRulesDefinition.class.getResourceAsStream(resourcePath)) {
      return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), RuleMetadata.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read: " + resourcePath, e);
    }
  }

  private static class RuleMetadata {
    private static final String SECURITY_HOTSPOT = "SECURITY_HOTSPOT";

    String sqKey;
    String type;
    SecurityStandards securityStandards = new SecurityStandards();

    boolean isSecurityHotspot() {
      return SECURITY_HOTSPOT.equals(type);
    }
  }

  private static class SecurityStandards {
    @SerializedName("CWE")
    int[] cwe = {};

    @SerializedName("OWASP Top 10 2021")
    String[] owasp2021 = {};

    @SerializedName("OWASP")
    String[] owasp2017 = {};
  }
}
