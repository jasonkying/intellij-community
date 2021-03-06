/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionManager;
import com.intellij.codeInsight.intention.impl.IntentionHintComponent;
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler;
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.codeInspection.actions.CleanupAllIntention;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.IntentionFilterOwner;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.PairProcessor;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ShowIntentionsPass extends TextEditorHighlightingPass {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.ShowIntentionsPass");
  private final Editor myEditor;

  private final PsiFile myFile;
  private final int myPassIdToShowIntentionsFor;
  private final IntentionsInfo myIntentionsInfo = new IntentionsInfo();
  private volatile boolean myShowBulb;
  private volatile boolean myHasToRecreate;

  @NotNull
  public static List<HighlightInfo.IntentionActionDescriptor> getAvailableActions(@NotNull final Editor editor,
                                                                                  @NotNull final PsiFile file,
                                                                                  final int passId) {
    final int offset = ((EditorEx)editor).getExpectedCaretOffset();
    final Project project = file.getProject();

    final List<HighlightInfo.IntentionActionDescriptor> result = new ArrayList<HighlightInfo.IntentionActionDescriptor>();
    DaemonCodeAnalyzerImpl.processHighlightsNearOffset(editor.getDocument(), project, HighlightSeverity.INFORMATION, offset, true, new Processor<HighlightInfo>() {
      @Override
      public boolean process(HighlightInfo info) {
        addAvailableActionsForGroups(info, editor, file, result, passId, offset);
        return true;
      }
    });
    return result;
  }

  private static void addAvailableActionsForGroups(@NotNull HighlightInfo info,
                                                   @NotNull Editor editor,
                                                   @NotNull PsiFile file,
                                                   @NotNull List<HighlightInfo.IntentionActionDescriptor> outList,
                                                   int group,
                                                   int offset) {
    if (info.quickFixActionMarkers == null) return;
    if (group != -1 && group != info.getGroup()) return;
    Editor injectedEditor = null;
    PsiFile injectedFile = null;
    for (Pair<HighlightInfo.IntentionActionDescriptor, RangeMarker> pair : info.quickFixActionMarkers) {
      HighlightInfo.IntentionActionDescriptor actionInGroup = pair.first;
      RangeMarker range = pair.second;
      if (!range.isValid()) continue;
      int start = range.getStartOffset();
      int end = range.getEndOffset();
      final Project project = file.getProject();
      if (start > offset || offset > end) {
        continue;
      }
      Editor editorToUse;
      PsiFile fileToUse;
      if (info.isFromInjection()) {
        if (injectedEditor == null) {
          injectedFile = InjectedLanguageUtil.findInjectedPsiNoCommit(file, offset);
          injectedEditor = InjectedLanguageUtil.getInjectedEditorForInjectedFile(editor, injectedFile);
        }
        editorToUse = injectedEditor;
        fileToUse = injectedFile;
      }
      else {
        editorToUse = editor;
        fileToUse = file;
      }
      if (actionInGroup.getAction().isAvailable(project, editorToUse, fileToUse)) {
        outList.add(actionInGroup);
      }
    }
  }

  public static class IntentionsInfo {
    public final List<HighlightInfo.IntentionActionDescriptor> intentionsToShow = ContainerUtil.createLockFreeCopyOnWriteList();
    public final List<HighlightInfo.IntentionActionDescriptor> errorFixesToShow = ContainerUtil.createLockFreeCopyOnWriteList();
    public final List<HighlightInfo.IntentionActionDescriptor> inspectionFixesToShow = ContainerUtil.createLockFreeCopyOnWriteList();
    public final List<HighlightInfo.IntentionActionDescriptor> guttersToShow = ContainerUtil.createLockFreeCopyOnWriteList();

    private void filterActions(@NotNull IntentionFilterOwner.IntentionActionsFilter actionsFilter) {
      filter(intentionsToShow, actionsFilter);
      filter(errorFixesToShow, actionsFilter);
      filter(inspectionFixesToShow, actionsFilter);
      filter(guttersToShow, actionsFilter);
    }

    private static void filter(@NotNull List<HighlightInfo.IntentionActionDescriptor> descriptors,
                               @NotNull IntentionFilterOwner.IntentionActionsFilter actionsFilter) {
      for (Iterator<HighlightInfo.IntentionActionDescriptor> it = descriptors.iterator(); it.hasNext();) {
          HighlightInfo.IntentionActionDescriptor actionDescriptor = it.next();
          if (!actionsFilter.isAvailable(actionDescriptor.getAction())) it.remove();
        }
    }

    public boolean isEmpty() {
      return intentionsToShow.isEmpty() && errorFixesToShow.isEmpty() && inspectionFixesToShow.isEmpty() && guttersToShow.isEmpty();
    }

    @NonNls
    @Override
    public String toString() {
      return
        "Errors: " + errorFixesToShow + "; " +
        "Inspection fixes: " + inspectionFixesToShow + "; " +
        "Intentions: " + intentionsToShow + "; " +
        "Gutters: " + guttersToShow;
    }
  }

  ShowIntentionsPass(@NotNull Project project, @NotNull Editor editor, int passId) {
    super(project, editor.getDocument(), false);
    myPassIdToShowIntentionsFor = passId;
    ApplicationManager.getApplication().assertIsDispatchThread();

    myEditor = editor;

    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);

    myFile = documentManager.getPsiFile(myEditor.getDocument());
    assert myFile != null : FileDocumentManager.getInstance().getFile(myEditor.getDocument());
  }

  @Override
  public void doCollectInformation(@NotNull ProgressIndicator progress) {
    if (!ApplicationManager.getApplication().isUnitTestMode() && !myEditor.getContentComponent().hasFocus()) return;
    TemplateState state = TemplateManagerImpl.getTemplateState(myEditor);
    if (state != null && !state.isFinished()) return;
    DaemonCodeAnalyzerImpl codeAnalyzer = (DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(myProject);
    getIntentionActionsToShow();
    updateActions(codeAnalyzer);
  }

  @Override
  public void doApplyInformationToEditor() {
    ApplicationManager.getApplication().assertIsDispatchThread();

    if (!ApplicationManager.getApplication().isUnitTestMode() && !myEditor.getContentComponent().hasFocus()) return;

    // do not show intentions if caret is outside visible area
    LogicalPosition caretPos = myEditor.getCaretModel().getLogicalPosition();
    Rectangle visibleArea = myEditor.getScrollingModel().getVisibleArea();
    Point xy = myEditor.logicalPositionToXY(caretPos);
    if (!visibleArea.contains(xy)) return;

    TemplateState state = TemplateManagerImpl.getTemplateState(myEditor);
    if (myShowBulb && (state == null || state.isFinished()) && !HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(false)) {
      DaemonCodeAnalyzerImpl codeAnalyzer = (DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(myProject);
      codeAnalyzer.setLastIntentionHint(myProject, myFile, myEditor, myIntentionsInfo, myHasToRecreate);
    }
  }

  private void getIntentionActionsToShow() {
    getActionsToShow(myEditor, myFile, myIntentionsInfo, myPassIdToShowIntentionsFor);
    if (myFile instanceof IntentionFilterOwner) {
      final IntentionFilterOwner.IntentionActionsFilter actionsFilter = ((IntentionFilterOwner)myFile).getIntentionActionsFilter();
      if (actionsFilter == null) return;
      if (actionsFilter != IntentionFilterOwner.IntentionActionsFilter.EVERYTHING_AVAILABLE) {
        myIntentionsInfo.filterActions(actionsFilter);
      }
    }

    if (myIntentionsInfo.isEmpty()) {
      return;
    }
    myShowBulb = !myIntentionsInfo.guttersToShow.isEmpty() ||
      ContainerUtil.exists(ContainerUtil.concat(myIntentionsInfo.errorFixesToShow, myIntentionsInfo.inspectionFixesToShow,myIntentionsInfo.intentionsToShow), new Condition<HighlightInfo.IntentionActionDescriptor>() {
        @Override
        public boolean value(HighlightInfo.IntentionActionDescriptor descriptor) {
          return IntentionManagerSettings.getInstance().isShowLightBulb(descriptor.getAction());
        }
      });
  }

  private static boolean appendCleanupCode(@NotNull List<HighlightInfo.IntentionActionDescriptor> actionDescriptors, @NotNull PsiFile file) {
    for (HighlightInfo.IntentionActionDescriptor descriptor : actionDescriptors) {
      if (descriptor.canCleanup(file)) {
        final ArrayList<IntentionAction> options = new ArrayList<IntentionAction>();
        options.add(EditCleanupProfileIntentionAction.INSTANCE);
        options.add(CleanupOnScopeIntention.INSTANCE);
        actionDescriptors.add(new HighlightInfo.IntentionActionDescriptor(CleanupAllIntention.INSTANCE, options, "Code Cleanup Options"));
        return true;
      }
    }
    return false;
  }

  private void updateActions(@NotNull DaemonCodeAnalyzerImpl codeAnalyzer) {
    IntentionHintComponent hintComponent = codeAnalyzer.getLastIntentionHint();
    if (!myShowBulb || hintComponent == null) {
      return;
    }
    IntentionHintComponent.PopupUpdateResult result = hintComponent.updateActions(myIntentionsInfo);
    if (result == IntentionHintComponent.PopupUpdateResult.HIDE_AND_RECREATE) {
      // reshow all
    }
    else if (result == IntentionHintComponent.PopupUpdateResult.CHANGED_INVISIBLE) {
      myHasToRecreate = true;
    }
    else {
      myShowBulb = false;  // nothing to apply
    }
  }

  public static void getActionsToShow(@NotNull final Editor hostEditor,
                                      @NotNull final PsiFile hostFile,
                                      @NotNull final IntentionsInfo intentions,
                                      int passIdToShowIntentionsFor) {
    final PsiElement psiElement = hostFile.findElementAt(hostEditor.getCaretModel().getOffset());
    LOG.assertTrue(psiElement == null || psiElement.isValid(), psiElement);

    int offset = hostEditor.getCaretModel().getOffset();
    Project project = hostFile.getProject();

    List<HighlightInfo.IntentionActionDescriptor> fixes = getAvailableActions(hostEditor, hostFile, passIdToShowIntentionsFor);
    final DaemonCodeAnalyzer codeAnalyzer = DaemonCodeAnalyzer.getInstance(project);
    final Document hostDocument = hostEditor.getDocument();
    HighlightInfo infoAtCursor = ((DaemonCodeAnalyzerImpl)codeAnalyzer).findHighlightByOffset(hostDocument, offset, true);
    if (infoAtCursor == null) {
      intentions.errorFixesToShow.addAll(fixes);
    }
    else {
      final boolean isError = infoAtCursor.getSeverity() == HighlightSeverity.ERROR;
      for (HighlightInfo.IntentionActionDescriptor fix : fixes) {
        if (fix.isError() && isError) {
          intentions.errorFixesToShow.add(fix);
        }
        else {
          intentions.inspectionFixesToShow.add(fix);
        }
      }
    }

    for (final IntentionAction action : IntentionManager.getInstance().getAvailableIntentionActions()) {
      Pair<PsiFile, Editor> place =
        ShowIntentionActionsHandler.chooseBetweenHostAndInjected(hostFile, hostEditor, new PairProcessor<PsiFile, Editor>() {
          @Override
          public boolean process(PsiFile psiFile, Editor editor) {
            return ShowIntentionActionsHandler.availableFor(psiFile, editor, action);
          }
        });

      if (place != null) {
        List<IntentionAction> enableDisableIntentionAction = new ArrayList<IntentionAction>();
        enableDisableIntentionAction.add(new IntentionHintComponent.EnableDisableIntentionAction(action));
        enableDisableIntentionAction.add(new IntentionHintComponent.EditIntentionSettingsAction(action));
        HighlightInfo.IntentionActionDescriptor descriptor = new HighlightInfo.IntentionActionDescriptor(action, enableDisableIntentionAction, null);
        if (!fixes.contains(descriptor)) {
          intentions.intentionsToShow.add(descriptor);
        }
      }
    }

    final int line = hostDocument.getLineNumber(offset);
    MarkupModelEx model = (MarkupModelEx)DocumentMarkupModel.forDocument(hostDocument, project, true);
    model.processRangeHighlightersOverlappingWith(hostDocument.getLineStartOffset(line),
                                                  hostDocument.getLineEndOffset(line), new Processor<RangeHighlighterEx>() {
        @Override
        public boolean process(RangeHighlighterEx highlighter) {
          GutterIntentionAction.addActions(highlighter, (EditorEx)hostEditor, intentions.guttersToShow);
          return true;
        }
      });

    boolean cleanup = appendCleanupCode(intentions.inspectionFixesToShow, hostFile);
    if (!cleanup) {
      appendCleanupCode(intentions.errorFixesToShow, hostFile);
    }
  }
}

