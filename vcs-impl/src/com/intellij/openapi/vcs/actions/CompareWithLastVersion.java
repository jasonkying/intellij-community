package com.intellij.openapi.vcs.actions;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vfs.VirtualFile;

public class CompareWithLastVersion extends AbstractShowDiffAction{
  @Override
  protected ProjectLevelVcsManagerImpl.MyBackgroundableActions getKey() {
    return ProjectLevelVcsManagerImpl.MyBackgroundableActions.COMPARE_WITH_LATEST;
  }

  @Override
  protected DiffActionExecutor getExecutor(final DiffProvider diffProvider, final VirtualFile selectedFile, final Project project) {
    return new DiffActionExecutor.DeletionAwareExecutor(diffProvider, selectedFile, project, getKey());
  }
}
