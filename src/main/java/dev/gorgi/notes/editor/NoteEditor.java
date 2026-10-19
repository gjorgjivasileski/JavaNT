package dev.gorgi.notes.editor;

import dev.gorgi.notes.markdown.CheckboxEdit;
import dev.gorgi.notes.markdown.ImageMarkup;
import dev.gorgi.notes.markdown.Markdown;
import dev.gorgi.notes.markdown.RenderedMarkdown;
import dev.gorgi.notes.markdown.TaskLinks;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.theme.Theme;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import javafx.util.StringConverter;
import netscape.javascript.JSObject;
import org.w3c.dom.Element;
import org.w3c.dom.events.EventTarget;
import org.w3c.dom.events.MouseEvent;
import org.w3c.dom.html.HTMLInputElement;

public final class NoteEditor extends VBox {
  private Workspace.Note note;
  private Workspace.Todo task;
  private Theme theme;
  private final CheckBox taskDone = new CheckBox("Done");
  private final ImageMarkdownArea source = new ImageMarkdownArea();
  private final Spinner<Integer> fontSize = new Spinner<>();
  private final WebView preview = new WebView();
  private final PreviewImages images = new PreviewImages(preview, this::markPreviewDirty);
  private String pendingImageSelection;
  private final SplitPane split = new SplitPane();
  private final VBox sourcePane;
  private final VBox previewPane;
  private final Label count = new Label();
  private final ToggleGroup modes = new ToggleGroup();
  private final PauseTransition previewDelay = new PauseTransition(Duration.millis(220));
  private final PauseTransition commitDelay = new PauseTransition(Duration.millis(180));
  private final Executor executor;
  private long renderVersion;
  private boolean disposed;
  private int editorFontSize = (int) Workspace.DEFAULT_EDITOR_FONT_SIZE;
  private double textScale = 1;
  private boolean updatingFontSize;
  private DoubleConsumer onEditorFontSizeChanged = size -> {};
  private String mode = "Split";
  private boolean previewDirty;
  private boolean updatingFromPreview;
  private boolean updatingTask;
  private String renderedSource = "";
  private final Consumer<Workspace.Note> onChange;
  private final Consumer<Workspace.Todo> onTaskChange;
  private final Consumer<TaskLinks.Change> onTask;
  private final Predicate<String> removeLinkedTask;

  public NoteEditor(
      Workspace.Note note,
      List<Workspace.Collection> collections,
      Theme theme,
      Executor executor,
      Consumer<Workspace.Note> onChange,
      Consumer<String> openLink) {
    this(note, collections, theme, executor, onChange, openLink, task -> {}, id -> true);
  }

  public NoteEditor(
      Workspace.Note note,
      List<Workspace.Collection> collections,
      Theme theme,
      Executor executor,
      Consumer<Workspace.Note> onChange,
      Consumer<String> openLink,
      Consumer<TaskLinks.Change> onTask,
      Predicate<String> removeLinkedTask) {
    this(
        note,
        null,
        collections,
        theme,
        executor,
        onChange,
        null,
        openLink,
        onTask,
        removeLinkedTask);
  }

  public static NoteEditor forTask(
      Workspace.Todo task,
      Theme theme,
      Executor executor,
      Consumer<Workspace.Todo> onChange,
      Consumer<String> openLink,
      Consumer<TaskLinks.Change> onTask,
      Predicate<String> removeLinkedTask) {
    return new NoteEditor(
        new Workspace.Note(task.id(), task.text(), "", task.markdown()),
        task,
        List.of(),
        theme,
        executor,
        note -> {},
        onChange,
        openLink,
        onTask,
        removeLinkedTask);
  }

  private NoteEditor(
      Workspace.Note note,
      Workspace.Todo task,
      List<Workspace.Collection> collections,
      Theme theme,
      Executor executor,
      Consumer<Workspace.Note> onChange,
      Consumer<Workspace.Todo> onTaskChange,
      Consumer<String> openLink,
      Consumer<TaskLinks.Change> onTask,
      Predicate<String> removeLinkedTask) {
    this.note = note;
    this.theme = theme;
    this.executor = executor;
    this.onChange = onChange;
    this.task = task;
    this.onTask = onTask;
    this.onTaskChange = onTaskChange;
    this.removeLinkedTask = removeLinkedTask;
    getStyleClass().add("note-editor");
    if (task != null) {
      getStyleClass().add("task-editor");
    }
    taskDone.setId("task-done");
    taskDone.setAccessibleText("Task complete");
    taskDone.getStyleClass().add("task-completion");
    taskDone.setSelected(task != null && task.done());
    source.setText(note.markdown());
    source.setWrapText(true);
    source.setId("markdown-source");
    source.setPromptText(
        task == null
            ? "Start writing. Markdown is welcome here…"
            : "Add task details. Markdown is welcome here…");
    source.setAccessibleText(task == null ? "Markdown source" : "Task details Markdown");
    source.getStyleClass().add("markdown-source");
    preview.setContextMenuEnabled(false);
    preview.setId("markdown-preview");
    // Only app-generated HTML is loaded. CSP rejects page scripts and external resources.
    // Host DOM commands provide formatting without exposing a Java object to page code.
    preview.getEngine().setJavaScriptEnabled(true);
    preview
        .getEngine()
        .getLoadWorker()
        .stateProperty()
        .addListener(
            (obs, old, state) -> {
              if (!disposed
                  && state == Worker.State.SUCCEEDED
                  && preview.getEngine().getDocument() != null) {
                var document = preview.getEngine().getDocument();
                images.attach(document);
                if (pendingImageSelection != null) {
                  images.select(pendingImageSelection);
                  pendingImageSelection = null;
                }
                ((EventTarget) document)
                    .addEventListener(
                        "input",
                        event -> {
                          if (!(event.getTarget() instanceof HTMLInputElement)) {
                            markPreviewDirty();
                          }
                        },
                        false);
                ((EventTarget) document)
                    .addEventListener(
                        "change",
                        event -> {
                          if (event.getTarget() instanceof HTMLInputElement input
                              && "checkbox".equals(input.getType())) {
                            if (!previewDirty && !renderedSource.equals(source.getText())) {
                              render();
                              return;
                            }
                            flushPendingEdits();
                            int ordinal = checkboxOrdinal((Element) input);
                            if (ordinal < 0) {
                              return;
                            }
                            var change =
                                TaskLinks.toggle(source.getText(), ordinal, input.getChecked());
                            ((Element) input).setAttribute("data-task-id", change.id());
                            setSourceFromPreview(change.markdown());
                            onTask.accept(change);
                          }
                        },
                        false);
                ((EventTarget) preview.getEngine().getDocument())
                    .addEventListener(
                        "click",
                        event -> {
                          var node = (org.w3c.dom.Node) event.getTarget();
                          while (node != null
                              && !(node instanceof Element e
                                  && "a".equalsIgnoreCase(e.getTagName()))) {
                            node = node.getParentNode();
                          }
                          if (node instanceof Element anchor) {
                            event.preventDefault();
                            var href = anchor.getAttribute("href");
                            if (event instanceof MouseEvent mouse
                                && (mouse.getCtrlKey() || mouse.getMetaKey())
                                && Markdown.isExternalLink(href)) {
                              openLink.accept(href);
                            }
                          }
                        },
                        false);
              }
            });
    sourcePane = pane("MARKDOWN", source);
    previewPane = pane("LIVE PREVIEW", preview);
    split.getItems().addAll(sourcePane, previewPane);
    split.setDividerPositions(.5);
    split.getStyleClass().add("editor-split");
    VBox.setVgrow(split, Priority.ALWAYS);
    var bulletList =
        tool(
            "\uD83D\uDCCB",
            "Bullet list",
            () -> format("insertUnorderedList", null, () -> prefix("- ")));
    bulletList.getStyleClass().add("bullet-list-button");
    var toolbar =
        new FlowPane(
            3,
            4,
            tool(
                "B",
                "Bold · Ctrl+B",
                () -> format("bold", null, () -> wrap("**", "**", "bold text"))),
            tool(
                "I",
                "Italic · Ctrl+I",
                () -> format("italic", null, () -> wrap("*", "*", "italic text"))),
            tool("H", "Heading", () -> format("formatBlock", "h2", () -> prefix("## "))),
            bulletList,
            tool("☐", "Add or remove task from this row", this::toggleCheckbox),
            tool("❯", "Quote", () -> format("formatBlock", "blockquote", () -> prefix("> "))),
            tool("</>", "Code", () -> format("formatBlock", "pre", () -> wrap("`", "`", "code"))),
            tool(
                "↗",
                "Link",
                () ->
                    format("createLink", "https://", () -> wrap("[", "](https://)", "link text"))));
    for (var mode : List.of("Write", "Split", "Read")) {
      var button = new ToggleButton(mode);
      button.setToggleGroup(modes);
      button.getStyleClass().add("mode-button");
      button.setOnAction(
          e -> {
            button.setSelected(true);
            setMode(mode);
          });
      if (mode.equals("Split")) {
        button.setSelected(true);
      }
      toolbar.getChildren().add(button);
    }
    configureFontSize();
    var fontLabel = new Label("Text size");
    fontLabel.setLabelFor(fontSize);
    var fontControl = new HBox(4, fontLabel, fontSize);
    fontControl.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    fontControl.getStyleClass().add("editor-font-control");
    toolbar.getChildren().add(fontControl);
    if (task != null) {
      toolbar.getChildren().add(taskDone);
    }
    toolbar.getStyleClass().add("format-toolbar");
    widthProperty().addListener((obs, old, width) -> updateSplitOrientation());
    var hint = new Label("Click preview to edit · Ctrl+click links");
    hint.getStyleClass().add("muted");
    hint.visibleProperty().bind(widthProperty().greaterThan(600));
    hint.managedProperty().bind(hint.visibleProperty());
    var footSpace = new Region();
    HBox.setHgrow(footSpace, Priority.ALWAYS);
    var footer = new HBox(hint, footSpace, count);
    footer.getStyleClass().add("editor-footer");
    getChildren().addAll(toolbar, split, footer);
    taskDone.selectedProperty().addListener((obs, old, checked) -> changed());
    source
        .textProperty()
        .addListener(
            (obs, old, text) -> {
              changed();
              updateCount();
              if (!updatingFromPreview) {
                invalidatePreview();
                previewDelay.playFromStart();
              }
            });
    source
        .focusedProperty()
        .addListener(
            (obs, old, focused) -> {
              if (focused) {
                flushPendingEdits();
              }
            });
    previewDelay.setOnFinished(e -> render());
    commitDelay.setOnFinished(e -> flushPendingEdits());
    source.addEventFilter(
        KeyEvent.KEY_PRESSED,
        e -> {
          if (new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN).match(e)) {
            wrap("**", "**", "bold text");
            e.consume();
          }
          if (new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN).match(e)) {
            wrap("*", "*", "italic text");
            e.consume();
          }
        });
    applyEditorFontSize();
    updateCount();
    render();
  }

  private static VBox pane(String heading, javafx.scene.Node content) {
    var label = new Label(heading);
    label.getStyleClass().add("pane-heading");
    var pane = new VBox(label, content);
    pane.setMinWidth(0);
    VBox.setVgrow(content, Priority.ALWAYS);
    return pane;
  }

  private Button tool(String text, String hint, Runnable action) {
    var button = new Button(text);
    button.setTooltip(new Tooltip(hint));
    button.setAccessibleText(hint);
    button.setFocusTraversable(false);
    button.setOnAction(e -> action.run());
    button.getStyleClass().add("format-button");
    return button;
  }

  private void changed() {
    if (updatingTask) {
      return;
    }
    if (task != null) {
      task =
          new Workspace.Todo(
              task.id(),
              task.text(),
              taskDone.isSelected(),
              task.sourceNoteId(),
              task.sourceItemId(),
              source.markdown());
      onTaskChange.accept(task);
      return;
    }
    note = new Workspace.Note(note.id(), note.title(), note.collectionId(), source.markdown());
    onChange.accept(note);
  }

  public void rename(String title) {
    flushPendingEdits();
    String name = title == null ? "" : title.strip();
    if (name.isEmpty()) {
      name = task == null ? "Untitled note" : "Untitled task";
    }
    if (task != null) {
      if (task.text().equals(name)) {
        return;
      }
      task =
          new Workspace.Todo(
              task.id(),
              name,
              task.done(),
              task.sourceNoteId(),
              task.sourceItemId(),
              source.markdown());
    } else {
      if (note.title().equals(name)) {
        return;
      }
      note = new Workspace.Note(note.id(), name, note.collectionId(), source.markdown());
    }
    changed();
  }

  public void setCollection(String collectionId) {
    if (task != null || collectionId == null || collectionId.isBlank()) {
      return;
    }
    flushPendingEdits();
    if (note.collectionId().equals(collectionId)) {
      return;
    }
    note = new Workspace.Note(note.id(), note.title(), collectionId, source.markdown());
    changed();
  }

  public void setTheme(Theme theme) {
    flushPendingEdits();
    this.theme = theme;
    render();
  }

  public void setTextScale(double scale) {
    textScale = scale;
    applyEditorFontSize();
  }

  private void configureFontSize() {
    var values =
        new SpinnerValueFactory.IntegerSpinnerValueFactory(
            (int) Workspace.MIN_EDITOR_FONT_SIZE,
            (int) Workspace.MAX_EDITOR_FONT_SIZE,
            editorFontSize);
    // A forgiving converter also covers Spinner's own Enter/arrow commit paths.
    values.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(Integer value) {
            return Integer.toString(value == null ? editorFontSize : value);
          }

          @Override
          public Integer fromString(String value) {
            try {
              return normalizedFontSize(Double.parseDouble(value.strip()));
            } catch (NullPointerException | NumberFormatException ignored) {
              return editorFontSize;
            }
          }
        });
    fontSize.setValueFactory(values);
    fontSize.setEditable(true);
    fontSize.setId("editor-font-size");
    fontSize.getStyleClass().add("editor-font-size");
    fontSize.setAccessibleText("Writing and preview text size in pixels");
    fontSize.setTooltip(new Tooltip("Writing and preview text size · 8–40 px"));
    fontSize.setPrefWidth(76);
    fontSize.setMinWidth(70);
    fontSize.getEditor().setOnAction(event -> commitFontSize());
    fontSize
        .getEditor()
        .focusedProperty()
        .addListener(
            (obs, old, focused) -> {
              if (!focused) {
                commitFontSize();
              }
            });
    fontSize
        .valueProperty()
        .addListener(
            (obs, old, value) -> {
              if (value == null || updatingFontSize) {
                return;
              }
              editorFontSize = value;
              applyEditorFontSize();
              onEditorFontSizeChanged.accept(value.doubleValue());
            });
  }

  private void commitFontSize() {
    fontSize.commitValue();
    // Reformat even when invalid input resolves to the already-selected value.
    fontSize.cancelEdit();
  }

  private static int normalizedFontSize(double size) {
    if (!Double.isFinite(size)) {
      return (int) Workspace.DEFAULT_EDITOR_FONT_SIZE;
    }
    return (int)
        Math.round(
            Math.clamp(size, Workspace.MIN_EDITOR_FONT_SIZE, Workspace.MAX_EDITOR_FONT_SIZE));
  }

  public void setEditorFontSize(double size) {
    editorFontSize = normalizedFontSize(size);
    updatingFontSize = true;
    try {
      fontSize.getValueFactory().setValue(editorFontSize);
      fontSize.cancelEdit();
    } finally {
      updatingFontSize = false;
    }
    applyEditorFontSize();
  }

  public void setOnEditorFontSizeChanged(DoubleConsumer listener) {
    onEditorFontSizeChanged = listener == null ? size -> {} : listener;
  }

  private void applyEditorFontSize() {
    source.setStyle("-fx-font-size:" + editorFontSize * textScale + "px;");
    // Theme.webCss() uses a 14px body; font scaling preserves editable DOM and selection.
    preview.setFontScale(editorFontSize / Workspace.DEFAULT_EDITOR_FONT_SIZE * textScale);
  }

  public void setMode(String mode) {
    flushPendingEdits();
    if (this.mode.equals(mode)) {
      return;
    }
    this.mode = mode;
    modes.getToggles().stream()
        .filter(toggle -> ((ToggleButton) toggle).getText().equals(mode))
        .findFirst()
        .ifPresent(modes::selectToggle);
    switch (mode) {
      case "Write" -> split.getItems().setAll(sourcePane);
      case "Read" -> split.getItems().setAll(previewPane);
      default -> {
        updateSplitOrientation();
        split.getItems().setAll(sourcePane, previewPane);
        split.setDividerPositions(.5);
      }
    }
  }

  private void updateSplitOrientation() {
    var orientation =
        getWidth() < 650
            ? javafx.geometry.Orientation.VERTICAL
            : javafx.geometry.Orientation.HORIZONTAL;
    if (split.getOrientation() != orientation) {
      split.setOrientation(orientation);
    }
  }

  private void render() {
    long revision = ++renderVersion;
    String text = source.getText();
    String markdown = source.markdown();
    Theme currentTheme = theme;
    CompletableFuture.supplyAsync(() -> Markdown.render(markdown, currentTheme), executor)
        .whenComplete(
            (html, error) ->
                Platform.runLater(
                    () -> {
                      if (!disposed && revision == renderVersion) {
                        if (error == null) {
                          renderedSource = text;
                          preview.getEngine().loadContent(html);
                        } else {
                          count.setText("Preview unavailable; your source is intact");
                        }
                      }
                    }));
  }

  private void invalidatePreview() {
    renderVersion++;
    images.clear();
    var document = preview.getEngine().getDocument();
    if (document != null && document.getElementsByTagName("body").getLength() > 0) {
      ((Element) document.getElementsByTagName("body").item(0))
          .setAttribute("contenteditable", "false");
    }
  }

  private int checkboxOrdinal(Element target) {
    var inputs = preview.getEngine().getDocument().getElementsByTagName("input");
    int ordinal = 0;
    for (int i = 0; i < inputs.getLength(); i++) {
      var input = (Element) inputs.item(i);
      if (!"checkbox".equalsIgnoreCase(input.getAttribute("type"))) {
        continue;
      }
      if (input.isSameNode(target)) {
        return ordinal;
      }
      ordinal++;
    }
    return -1;
  }

  private void markPreviewDirty() {
    if (disposed) {
      return;
    }
    previewDirty = true;
    renderVersion++;
    previewDelay.stop();
    commitDelay.playFromStart();
  }

  public void flushPendingEdits() {
    commitDelay.stop();
    if (!previewDirty || preview.getEngine().getDocument() == null) {
      return;
    }
    var markdown = RenderedMarkdown.fromDocument(preview.getEngine().getDocument());
    previewDirty = false;
    setSourceFromPreview(markdown);
  }

  private void setSourceFromPreview(String markdown) {
    updatingFromPreview = true;
    try {
      source.setText(markdown);
      renderedSource = source.getText();
    } finally {
      updatingFromPreview = false;
    }
  }

  public void updateMarkdown(String markdown) {
    flushPendingEdits();
    if (!source.markdown().equals(markdown)) {
      source.setText(markdown);
    }
  }

  /** Inserts an embedded image at the writing caret or the preview's text selection. */
  public void insertImage(EmbeddedImage image) {
    if (disposed) {
      return;
    }
    flushPendingEdits();
    String id = Workspace.id();
    var layout = new ImageMarkup.Layout(id, 16, 16, image.width(), image.height(), 1, false);
    var document = preview.getEngine().getDocument();
    if (!mode.equals("Write") && document != null && renderedSource.equals(source.getText())) {
      var element = document.createElement("img");
      element.setAttribute("src", image.dataUrl());
      element.setAttribute("alt", image.name());
      element.setAttribute("title", image.name());
      element.setAttribute("class", "note-image");
      element.setAttribute("contenteditable", "false");
      element.setAttribute("draggable", "false");
      element.setAttribute("data-image-id", id);
      element.setAttribute("data-image-x", "16");
      element.setAttribute("data-image-y", "16");
      element.setAttribute("data-image-width", Integer.toString(image.width()));
      element.setAttribute("data-image-height", Integer.toString(image.height()));
      element.setAttribute("data-image-z", "1");
      element.setAttribute("data-image-mode", "inline");
      element.setAttribute("style", layout.style());
      var insert =
          (JSObject)
              preview
                  .getEngine()
                  .executeScript(
                      """
                      (function(image) {
                          var selection = window.getSelection(), body = document.body;
                          if (selection.rangeCount && body.contains(selection.anchorNode)) {
                              var anchor = selection.anchorNode.nodeType === 1
                                  ? selection.anchorNode : selection.anchorNode.parentElement;
                              var code = anchor.closest('pre,code');
                              if (code) {
                                  var block = code.closest('pre');
                                  if (block) {
                                      var paragraph = document.createElement('p');
                                      paragraph.appendChild(image);
                                      block.parentNode.insertBefore(paragraph, block.nextSibling);
                                  } else {
                                      code.parentNode.insertBefore(image, code.nextSibling);
                                  }
                                  return;
                              }
                              var range = selection.getRangeAt(0);
                              range.deleteContents(); range.insertNode(image);
                              range.setStartAfter(image); range.collapse(true);
                              selection.removeAllRanges(); selection.addRange(range);
                          } else {
                              var paragraph = document.createElement('p');
                              paragraph.appendChild(image); body.appendChild(paragraph);
                          }
                      })
                      """);
      insert.call("call", null, element);
      images.attach(document);
      images.select(id);
      markPreviewDirty();
      flushPendingEdits();
    } else {
      pendingImageSelection = id;
      int caret = source.getSelection().getStart();
      var insertion = ImageMarkup.insertionPoint(source.getText(), caret);
      String markdown = ImageMarkup.markdown(image.name(), image.dataUrl(), layout);
      if (insertion.beforeCode()) {
        source.insertText(insertion.offset(), markdown + "\n\n");
      } else {
        source.replaceSelection(markdown);
      }
      if (mode.equals("Write")) {
        setMode("Split");
      }
    }
    preview.requestFocus();
  }

  /** Returns whether an image is selected in this editor's preview. */
  public boolean hasSelectedImage() {
    return images.hasSelection();
  }

  /** Returns whether the selected image can float over the document. */
  public boolean isSelectedImageFloating() {
    return images.isFloating();
  }

  /** Places the selected image inline with text or in the floating layer. */
  public void setSelectedImageFloating(boolean floating) {
    showImagePreview();
    images.setFloating(floating);
  }

  /** Raises the selected image above the other document layers. */
  public void bringImageToFront() {
    showImagePreview();
    images.bringToFront();
  }

  /** Lowers the selected image behind the other document layers. */
  public void sendImageToBack() {
    showImagePreview();
    images.sendToBack();
  }

  /** Removes the selected image from the document. */
  public void deleteSelectedImage() {
    showImagePreview();
    images.deleteSelected();
  }

  /** Identifies an image for the application's image-selection menu. */
  public record ImageChoice(String id, String name) {}

  /** Lists managed images in the current preview without exposing its controller. */
  public List<ImageChoice> imageChoices() {
    var document = preview.getEngine().getDocument();
    if (document == null || !renderedSource.equals(source.getText())) {
      return List.of();
    }
    var choices = new java.util.ArrayList<ImageChoice>();
    var elements = document.getElementsByTagName("img");
    for (int i = 0; i < elements.getLength(); i++) {
      var element = (Element) elements.item(i);
      String id = element.getAttribute("data-image-id");
      if (id != null && !id.isBlank()) {
        String name = element.getAttribute("alt");
        choices.add(
            new ImageChoice(id, name == null || name.isBlank() ? "Image " + (i + 1) : name));
      }
    }
    return List.copyOf(choices);
  }

  /** Shows the preview and selects an image by its managed image identifier. */
  public void selectImage(String id) {
    showImagePreview();
    images.select(id);
    preview.requestFocus();
  }

  private void showImagePreview() {
    if (mode.equals("Write")) {
      setMode("Split");
    }
  }

  /**
   * Applies the current task snapshot without generating another edit or moving an unchanged source
   * caret.
   */
  public void updateTask(Workspace.Todo task) {
    if (this.task == null || !this.task.id().equals(task.id())) {
      return;
    }
    updatingTask = true;
    try {
      this.task = task;
      taskDone.setSelected(task.done());
      if (!source.markdown().equals(task.markdown())) {
        source.setText(task.markdown());
      }
    } finally {
      updatingTask = false;
    }
  }

  private void toggleCheckbox() {
    if (mode.equals("Read") || preview.isFocused()) {
      toggleRenderedCheckbox();
      return;
    }
    flushPendingEdits();
    var edit = CheckboxEdit.at(source.getText(), source.getSelection().getStart());
    if (edit.isEmpty()) {
      count.setText("Place the cursor in a text row outside a code block");
      return;
    }
    var change = edit.get().change();
    // Delete the linked task before changing the source, otherwise reconciliation would detach it.
    if (edit.get().removal() && !removeLinkedTask.test(change.id())) {
      return;
    }
    if (!source.getText().equals(change.markdown())) {
      source.replaceText(0, source.getLength(), change.markdown());
    }
    source.positionCaret(edit.get().caret());
    source.requestFocus();
    if (!edit.get().removal()) {
      onTask.accept(change);
    }
  }

  private void toggleRenderedCheckbox() {
    var document = preview.getEngine().getDocument();
    if (document == null) {
      return;
    }
    if (!previewDirty && !renderedSource.equals(source.getText())) {
      render();
      return;
    }
    preview.requestFocus();
    // Keep the selected paragraph's existing nodes, including emphasis, links, and nested lists.
    // The script is constant application code; note text is never interpolated into JavaScript.
    Object result =
        preview
            .getEngine()
            .executeScript(
                """
                (function () {
                    var body = document.body, selection = window.getSelection(), node = selection.anchorNode;
                    if (!node || !body.contains(node)) node = body;
                    if (node === body && body.childNodes.length)
                        node = body.childNodes[Math.min(selection.anchorOffset, body.childNodes.length - 1)];
                    var element = node.nodeType === 1 ? node : node.parentElement;
                    var item = element.closest('li');
                    if (item) {
                        var inputs = item.querySelectorAll('input[type="checkbox"]');
                        for (var i = 0; i < inputs.length; i++)
                            if (inputs[i].closest('li') === item) return {input: inputs[i], removal: true};
                    } else {
                        var block = element.closest('p,div,h1,h2,h3,h4,h5,h6,pre,td,th');
                        var list = document.createElement('ul'); list.className = 'task-list';
                        item = document.createElement('li'); list.appendChild(item);
                        if (block && block !== body) {
                            if (block.tagName === 'TD' || block.tagName === 'TH') {
                                while (block.firstChild) item.appendChild(block.firstChild);
                                block.appendChild(list);
                            } else {
                                block.parentNode.replaceChild(list, block);
                                while (block.firstChild) item.appendChild(block.firstChild);
                            }
                        } else {
                            // An empty editable body, or text entered directly into the body.
                            var first = node;
                            while (first !== body && first.parentNode !== body) first = first.parentNode;
                            if (first !== body && first.parentNode === body) {
                                body.insertBefore(list, first); item.appendChild(first);
                            } else body.appendChild(list);
                        }
                    }
                    if (!item.textContent.trim()) item.appendChild(document.createTextNode('New task'));
                    item.classList.add('task-list-item');
                    var input = document.createElement('input'); input.type = 'checkbox';
                    input.setAttribute('contenteditable', 'false'); input.setAttribute('aria-label', 'Task completion');
                    // Loose Markdown lists wrap their text in a paragraph; insert inside that paragraph.
                    var target = item.firstElementChild && item.firstElementChild.tagName === 'P' ? item.firstElementChild : item;
                    target.insertBefore(document.createTextNode(' '), target.firstChild);
                    target.insertBefore(input, target.firstChild);
                    var range = document.createRange(); range.selectNodeContents(target); range.collapse(false);
                    selection.removeAllRanges(); selection.addRange(range);
                    return {input: input, removal: false};
                })()
                """);
    if (!(result instanceof JSObject action)
        || !(action.getMember("input") instanceof HTMLInputElement input)) {
      return;
    }
    boolean removal = Boolean.TRUE.equals(action.getMember("removal"));
    // Finding an existing checkbox does not mutate the DOM. Cancel can therefore leave it intact.
    if (!removal) {
      markPreviewDirty();
    }
    flushPendingEdits();
    int ordinal = checkboxOrdinal((Element) input);
    var entries = TaskLinks.entries(source.getText());
    if (ordinal < 0 || ordinal >= entries.size()) {
      return;
    }
    if (removal) {
      var change = TaskLinks.removeCheckbox(source.getText(), ordinal);
      if (!removeLinkedTask.test(change.id())) {
        return;
      }
      source.replaceText(0, source.getLength(), change.markdown());
      // Rebuild from source: theme refreshes may have replaced the DOM while the dialog was open.
      previewDelay.stop();
      render();
      preview.requestFocus();
      return;
    }
    var change = TaskLinks.toggle(source.getText(), ordinal, input.getChecked());
    ((Element) input).setAttribute("data-task-id", change.id());
    setSourceFromPreview(change.markdown());
    onTask.accept(change);
  }

  private void format(String command, String value, Runnable sourceAction) {
    if (mode.equals("Read") || preview.isFocused()) {
      executeReadCommand(command, value);
    } else {
      flushPendingEdits();
      sourceAction.run();
    }
  }

  public boolean editRendered(String action) {
    if (!mode.equals("Read") && !preview.isFocused()) {
      return false;
    }
    var command =
        switch (action) {
          case "all" -> "selectAll";
          default -> action;
        };
    executeReadCommand(command, null);
    return true;
  }

  private void executeReadCommand(String command, String value) {
    var document = preview.getEngine().getDocument();
    if (document == null) {
      return;
    }
    preview.requestFocus();
    var dom = (JSObject) document;
    if (command.equals("copy") || command.equals("cut")) {
      var selected = dom.call("getSelection");
      var text = selected == null ? "" : selected.toString();
      if (!text.isEmpty()) {
        var content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
      }
      if (command.equals("cut")) {
        dom.call("execCommand", "delete", false, null);
      }
    } else if (command.equals("paste")) {
      String pasted = Clipboard.getSystemClipboard().getString();
      // Clipboard ownership or native text conversion can fail between a copy and a paste.
      if (pasted == null) {
        return;
      }
      dom.call("execCommand", "insertText", false, pasted);
    } else {
      dom.call("execCommand", command, false, value);
    }
    if (!List.of("copy", "selectAll").contains(command)) {
      markPreviewDirty();
    }
  }

  private void updateCount() {
    var text = source.getText().strip();
    int words = text.isEmpty() ? 0 : text.split("\\s+").length;
    count.setText(
        words
            + (words == 1 ? " word" : " words")
            + "   ·   "
            + source.getText().length()
            + " characters");
  }

  public void wrap(String before, String after, String placeholder) {
    int start = source.getSelection().getStart();
    String selection = source.getSelectedText();
    if (selection.isEmpty()) {
      selection = placeholder;
    }
    source.replaceSelection(before + selection + after);
    source.selectRange(start + before.length(), start + before.length() + selection.length());
    source.requestFocus();
  }

  public void prefix(String prefix) {
    int start =
        source.getText().lastIndexOf('\n', Math.max(-1, source.getSelection().getStart() - 1)) + 1;
    int end = source.getSelection().getEnd();
    String lines = source.getText().substring(start, end);
    source.replaceText(start, end, prefix + lines.replace("\n", "\n" + prefix));
    source.requestFocus();
  }

  public TextArea source() {
    return source;
  }

  /** Portable Markdown for storage and export; the writing view uses compact references. */
  public String markdown() {
    return source.markdown();
  }

  public void focusEditor() {
    if (mode.equals("Read")) {
      preview.requestFocus();
    } else {
      source.requestFocus();
    }
  }

  public void dispose() {
    flushPendingEdits();
    disposed = true;
    renderVersion++;
    previewDelay.stop();
    commitDelay.stop();
    images.dispose();
    preview.getEngine().load(null);
  }
}
