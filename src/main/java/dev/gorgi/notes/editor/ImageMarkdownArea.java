package dev.gorgi.notes.editor;

import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/** Edits compact image references while exposing portable Markdown to the rest of the app. */
final class ImageMarkdownArea extends TextArea {
  private final ImageReferences references = new ImageReferences();

  ImageMarkdownArea() {
    setTextFormatter(
        new TextFormatter<String>(
            change -> {
              if (!change.isContentChange()) {
                return change;
              }
              String incoming = change.getControlNewText();
              var compact = references.compact(incoming);
              if (!compact.text().equals(incoming)) {
                int anchor = compact.position(change.getAnchor());
                int caret = compact.position(change.getCaretPosition());
                change.setRange(0, change.getControlText().length());
                change.setText(compact.text());
                change.selectRange(anchor, caret);
              }
              return change;
            }));
  }

  String markdown() {
    return references.expand(getText());
  }

  @Override
  public void copy() {
    if (getSelectedText().isEmpty()) {
      return;
    }
    var content = new ClipboardContent();
    // Native context menus, shortcuts, and the app's Edit menu all use this method.
    // TextInputControl.cut() also calls copy(), so images can move between documents/apps.
    content.putString(references.expand(getSelectedText()));
    Clipboard.getSystemClipboard().setContent(content);
  }
}
