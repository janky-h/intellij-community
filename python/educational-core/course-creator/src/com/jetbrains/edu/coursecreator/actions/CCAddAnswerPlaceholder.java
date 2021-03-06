package com.jetbrains.edu.coursecreator.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.intellij.util.DocumentUtil;
import com.jetbrains.edu.coursecreator.ui.CCCreateAnswerPlaceholderDialog;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.core.EduAnswerPlaceholderPainter;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholder;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CCAddAnswerPlaceholder extends CCAnswerPlaceholderAction {

  public CCAddAnswerPlaceholder() {
    super("Add/Delete Answer Placeholder", "Add/Delete answer placeholder");
  }


  private static boolean arePlaceholdersIntersect(@NotNull final TaskFile taskFile, int start, int end) {
    List<AnswerPlaceholder> answerPlaceholders = taskFile.getAnswerPlaceholders();
    for (AnswerPlaceholder existingAnswerPlaceholder : answerPlaceholders) {
      int twStart = existingAnswerPlaceholder.getOffset();
      int twEnd = existingAnswerPlaceholder.getPossibleAnswerLength() + twStart;
      if ((start >= twStart && start < twEnd) || (end > twStart && end <= twEnd) ||
          (twStart >= start && twStart < end) || (twEnd > start && twEnd <= end)) {
        return true;
      }
    }
    return false;
  }

  private static void addPlaceholder(@NotNull CCState state) {
    Editor editor = state.getEditor();
    Project project = state.getProject();
    PsiFile file = state.getFile();

    final Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    if (document == null) {
      return;
    }
    FileDocumentManager.getInstance().saveDocument(document);
    final SelectionModel model = editor.getSelectionModel();
    final int offset = model.hasSelection() ? model.getSelectionStart() : editor.getCaretModel().getOffset();
    final AnswerPlaceholder answerPlaceholder = new AnswerPlaceholder();

    answerPlaceholder.setOffset(offset);
    answerPlaceholder.setUseLength(false);

    String defaultPlaceholderText = "type here";
    answerPlaceholder.setPossibleAnswer(model.hasSelection() ? model.getSelectedText() : defaultPlaceholderText);

    CCCreateAnswerPlaceholderDialog dlg = new CCCreateAnswerPlaceholderDialog(project, answerPlaceholder);
    dlg.show();
    if (dlg.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
      return;
    }

    if (!model.hasSelection()) {
      DocumentUtil.writeInRunUndoTransparentAction(() -> document.insertString(offset, defaultPlaceholderText));
    }

    TaskFile taskFile = state.getTaskFile();
    int index = taskFile.getAnswerPlaceholders().size() + 1;
    answerPlaceholder.setIndex(index);
    taskFile.addAnswerPlaceholder(answerPlaceholder);
    answerPlaceholder.setTaskFile(taskFile);
    taskFile.sortAnswerPlaceholders();

    answerPlaceholder.setPossibleAnswer(model.hasSelection() ? model.getSelectedText() : defaultPlaceholderText);
    EduAnswerPlaceholderPainter.drawAnswerPlaceholder(editor, answerPlaceholder, JBColor.BLUE);
    EduAnswerPlaceholderPainter.createGuardedBlocks(editor, answerPlaceholder);
  }

  @Override
  protected void performAnswerPlaceholderAction(@NotNull CCState state) {
    if (canAddPlaceholder(state)) {
      addPlaceholder(state);
      return;
    }
    if (canDeletePlaceholder(state)) {
      deletePlaceholder(state);
    }
  }

  private static void deletePlaceholder(@NotNull CCState state) {
    Project project = state.getProject();
    PsiFile psiFile = state.getFile();
    final Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
    if (document == null) return;
    TaskFile taskFile = state.getTaskFile();
    AnswerPlaceholder answerPlaceholder = state.getAnswerPlaceholder();
    final List<AnswerPlaceholder> answerPlaceholders = taskFile.getAnswerPlaceholders();
    if (answerPlaceholders.contains(answerPlaceholder)) {
      answerPlaceholders.remove(answerPlaceholder);
      final Editor editor = state.getEditor();
      editor.getMarkupModel().removeAllHighlighters();
      StudyUtils.drawAllWindows(editor, taskFile);
      EduAnswerPlaceholderPainter.createGuardedBlocks(editor, taskFile);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    final Presentation presentation = event.getPresentation();
    presentation.setEnabledAndVisible(false);

    CCState state = getState(event);
    if (state == null) {
      return;
    }

    presentation.setVisible(true);
    if (canAddPlaceholder(state) || canDeletePlaceholder(state)) {
      presentation.setEnabled(true);
      presentation.setText((state.getAnswerPlaceholder() == null ? "Add " : "Delete ") + EduNames.ANSWER_PLACEHOLDER);
    }
  }


  private static boolean canAddPlaceholder(@NotNull CCState state) {
    Editor editor = state.getEditor();
    SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasSelection()) {
      int start = selectionModel.getSelectionStart();
      int end = selectionModel.getSelectionEnd();
      return !arePlaceholdersIntersect(state.getTaskFile(), start, end);
    }
    int offset = editor.getCaretModel().getOffset();
    return state.getTaskFile().getAnswerPlaceholder(offset) == null;
  }

  private static boolean canDeletePlaceholder(@NotNull CCState state) {
    if (state.getEditor().getSelectionModel().hasSelection()) {
      return false;
    }
    return state.getAnswerPlaceholder() != null;
  }
}