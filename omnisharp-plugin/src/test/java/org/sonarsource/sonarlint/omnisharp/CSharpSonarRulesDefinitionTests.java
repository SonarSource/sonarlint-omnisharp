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

import org.junit.jupiter.api.Test;
import org.sonar.api.SonarRuntime;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.debt.DebtRemediationFunction;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinition.Context;
import org.sonar.api.server.rule.RulesDefinition.Rule;
import org.sonar.api.utils.Version;

import static org.assertj.core.api.Assertions.assertThat;

class CSharpSonarRulesDefinitionTests {
  private static final String SECURITY_HOTSPOT_RULE_KEY = "S5766";
  private static final String VULNERABILITY_RULE_KEY = "S4426";

  private static final SonarRuntime SONARLINT_RUNTIME = SonarRuntimeImpl.forSonarLint(Version.create(9, 3));

  @Test
  void test() {
    Context context = new Context();
    assertThat(context.repositories()).isEmpty();

    CSharpSonarRulesDefinition csharpRulesDefinition = new CSharpSonarRulesDefinition(SONARLINT_RUNTIME);
    csharpRulesDefinition.define(context);

    assertThat(context.repositories()).hasSize(1);
    assertThat(context.repository("csharpsquid").rules()).isNotEmpty();

    Rule s100 = context.repository("csharpsquid").rule("S100");
    assertThat(s100.debtRemediationFunction().type()).isEqualTo(DebtRemediationFunction.Type.CONSTANT_ISSUE);
    assertThat(s100.debtRemediationFunction().baseEffort()).isEqualTo("5min");
    assertThat(s100.type()).isEqualTo(RuleType.CODE_SMELL);
  }

  @Test
  void test_security_hotspot() {
    CSharpSonarRulesDefinition definition = new CSharpSonarRulesDefinition(SONARLINT_RUNTIME);
    RulesDefinition.Context context = new RulesDefinition.Context();
    definition.define(context);
    RulesDefinition.Repository repository = context.repository("csharpsquid");

    RulesDefinition.Rule hardcodedCredentialsRule = repository.rule(SECURITY_HOTSPOT_RULE_KEY);
    assertThat(hardcodedCredentialsRule.type()).isEqualTo(RuleType.SECURITY_HOTSPOT);
    assertThat(hardcodedCredentialsRule.activatedByDefault()).isTrue();
  }

  @Test
  void test_security_hotspot_has_correct_type_and_security_standards() {
    CSharpSonarRulesDefinition definition = new CSharpSonarRulesDefinition(SONARLINT_RUNTIME);
    RulesDefinition.Context context = new RulesDefinition.Context();
    definition.define(context);
    RulesDefinition.Repository repository = context.repository("csharpsquid");

    RulesDefinition.Rule rule = repository.rule(SECURITY_HOTSPOT_RULE_KEY);
    assertThat(rule.type()).isEqualTo(RuleType.SECURITY_HOTSPOT);
    assertThat(rule.securityStandards()).containsExactlyInAnyOrder("cwe:502", "owaspTop10:a8", "owaspTop10-2021:a8");
  }

  @Test
  void test_security_standards_with_vulnerability() {
    CSharpSonarRulesDefinition definition = new CSharpSonarRulesDefinition(SONARLINT_RUNTIME);
    RulesDefinition.Context context = new RulesDefinition.Context();
    definition.define(context);
    RulesDefinition.Repository repository = context.repository("csharpsquid");

    RulesDefinition.Rule rule = repository.rule(VULNERABILITY_RULE_KEY);
    assertThat(rule.type()).isEqualTo(RuleType.VULNERABILITY);
    assertThat(rule.securityStandards()).containsExactlyInAnyOrder("cwe:326", "owaspTop10:a3", "owaspTop10:a6", "owaspTop10-2021:a2");
  }

  @Test
  void test_security_standards_before_9_3() {
    SonarRuntime sonarRuntime = SonarRuntimeImpl.forSonarLint(Version.create(9, 2));
    RulesDefinition.Context context = new RulesDefinition.Context();
    new CSharpSonarRulesDefinition(sonarRuntime).define(context);
    RulesDefinition.Repository repository = context.repository("csharpsquid");

    RulesDefinition.Rule rule = repository.rule(VULNERABILITY_RULE_KEY);
    assertThat(rule.securityStandards()).containsExactlyInAnyOrder("cwe:326", "owaspTop10:a3", "owaspTop10:a6");
  }

  @Test
  void test_all_rules_have_status_set() {
    CSharpSonarRulesDefinition definition = new CSharpSonarRulesDefinition(SONARLINT_RUNTIME);
    RulesDefinition.Context context = new RulesDefinition.Context();
    definition.define(context);
    RulesDefinition.Repository repository = context.repository("csharpsquid");

    for (RulesDefinition.Rule rule : repository.rules()) {
      assertThat(rule.status()).isNotNull();
    }
  }
}
