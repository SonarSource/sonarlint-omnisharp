/*
 * SonarOmnisharp ITs
 * Copyright (C) SonarSource Sàrl
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
package org.sonarsource.sonarlint.omnisharp.its;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.batch.sensor.issue.fix.NewInputFileEdit;
import org.sonar.api.batch.sensor.issue.fix.NewQuickFix;
import org.sonar.api.batch.sensor.issue.fix.NewTextEdit;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rule.RuleKey;

/**
 * Forwards everything to a real {@link NewIssue} — so the issue still lands in the
 * {@code SensorContextTester} and can be asserted on there — while additionally keeping the quick
 * fixes, which the tester silently discards.
 */
class QuickFixRecordingIssue implements NewIssue {

  private final NewIssue delegate;
  private final List<RecordedQuickFix> quickFixes = new ArrayList<>();
  private RuleKey ruleKey;

  QuickFixRecordingIssue(NewIssue delegate) {
    this.delegate = delegate;
  }

  @Nullable
  RuleKey ruleKey() {
    return ruleKey;
  }

  List<RecordedQuickFix> quickFixes() {
    return quickFixes;
  }

  @Override
  public NewIssue forRule(RuleKey ruleKey) {
    this.ruleKey = ruleKey;
    delegate.forRule(ruleKey);
    return this;
  }

  @Override
  public NewIssue gap(@Nullable Double gap) {
    delegate.gap(gap);
    return this;
  }

  @Override
  public NewIssue overrideSeverity(@Nullable Severity severity) {
    delegate.overrideSeverity(severity);
    return this;
  }

  @Override
  public NewIssue overrideImpact(SoftwareQuality softwareQuality, org.sonar.api.issue.impact.Severity severity) {
    delegate.overrideImpact(softwareQuality, severity);
    return this;
  }

  @Override
  public NewIssue at(NewIssueLocation primaryLocation) {
    delegate.at(primaryLocation);
    return this;
  }

  @Override
  public NewIssue addLocation(NewIssueLocation secondaryLocation) {
    delegate.addLocation(secondaryLocation);
    return this;
  }

  @Override
  public NewIssue setQuickFixAvailable(boolean quickFixAvailable) {
    delegate.setQuickFixAvailable(quickFixAvailable);
    return this;
  }

  @Override
  public NewIssue addFlow(Iterable<NewIssueLocation> flowLocations) {
    delegate.addFlow(flowLocations);
    return this;
  }

  @Override
  public NewIssue addFlow(Iterable<NewIssueLocation> flowLocations, FlowType flowType, @Nullable String description) {
    delegate.addFlow(flowLocations, flowType, description);
    return this;
  }

  @Override
  public NewIssueLocation newLocation() {
    return delegate.newLocation();
  }

  @Override
  public NewIssue setRuleDescriptionContextKey(@Nullable String ruleDescriptionContextKey) {
    delegate.setRuleDescriptionContextKey(ruleDescriptionContextKey);
    return this;
  }

  @Override
  public NewIssue setCodeVariants(@Nullable Iterable<String> codeVariants) {
    delegate.setCodeVariants(codeVariants);
    return this;
  }

  @Override
  public NewIssue addInternalTag(String tag) {
    delegate.addInternalTag(tag);
    return this;
  }

  @Override
  public NewIssue setInternalTags(@Nullable Collection<String> tags) {
    delegate.setInternalTags(tags);
    return this;
  }

  @Override
  public NewIssue addInternalTags(Collection<String> tags) {
    delegate.addInternalTags(tags);
    return this;
  }

  @Override
  public NewQuickFix newQuickFix() {
    return new RecordedQuickFix();
  }

  @Override
  public NewIssue addQuickFix(NewQuickFix newQuickFix) {
    quickFixes.add((RecordedQuickFix) newQuickFix);
    return this;
  }

  @Override
  public void save() {
    delegate.save();
  }

  static final class RecordedQuickFix implements NewQuickFix {
    private String message;
    private final List<RecordedFileEdit> fileEdits = new ArrayList<>();

    @Override
    public NewQuickFix message(String message) {
      this.message = message;
      return this;
    }

    @Override
    public NewInputFileEdit newInputFileEdit() {
      return new RecordedFileEdit();
    }

    @Override
    public NewQuickFix addInputFileEdit(NewInputFileEdit newInputFileEdit) {
      fileEdits.add((RecordedFileEdit) newInputFileEdit);
      return this;
    }

    String message() {
      return message;
    }

    List<RecordedFileEdit> fileEdits() {
      return fileEdits;
    }
  }

  static final class RecordedFileEdit implements NewInputFileEdit {
    private InputFile target;
    private final List<RecordedTextEdit> textEdits = new ArrayList<>();

    @Override
    public NewInputFileEdit on(InputFile inputFile) {
      this.target = inputFile;
      return this;
    }

    @Override
    public NewTextEdit newTextEdit() {
      return new RecordedTextEdit();
    }

    @Override
    public NewInputFileEdit addTextEdit(NewTextEdit newTextEdit) {
      textEdits.add((RecordedTextEdit) newTextEdit);
      return this;
    }

    InputFile target() {
      return target;
    }

    List<RecordedTextEdit> textEdits() {
      return textEdits;
    }
  }

  static final class RecordedTextEdit implements NewTextEdit {
    private TextRange range;
    private String newText;

    @Override
    public NewTextEdit at(TextRange range) {
      this.range = range;
      return this;
    }

    @Override
    public NewTextEdit withNewText(String newText) {
      this.newText = newText;
      return this;
    }

    TextRange range() {
      return range;
    }

    String newText() {
      return newText;
    }
  }
}
