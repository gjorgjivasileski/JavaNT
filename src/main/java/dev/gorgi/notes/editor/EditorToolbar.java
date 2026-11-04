package dev.gorgi.notes.editor;

import java.util.function.Consumer;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Compact formatting controls and the editor's view menu. */
final class EditorToolbar extends HBox {
  private final ToggleGroup modes = new ToggleGroup();
  private final Button image;

  EditorToolbar(
      Consumer<String> format,
      Runnable checklist,
      Consumer<String> changeMode,
      Spinner<Integer> fontSize,
      CheckBox completion) {
    getStyleClass().add("format-toolbar");
    setId("editor-toolbar");
    var tools = new FlowPane(2, 3);
    tools.setMinWidth(0);
    tools.getStyleClass().add("format-tools");
    HBox.setHgrow(tools, Priority.ALWAYS);
    image =
        tool(
            "Image",
            "image",
            "Insert image · Ctrl+Shift+I",
            "M2 3H14V13H2ZM2 11L6 7L10 11L12 9L14 11M11 5H11.01",
            () -> {});
    tools
        .getChildren()
        .addAll(
            tool(
                "B",
                "bold",
                "Bold · Ctrl+B",
                "M4 2.5H8A2.75 2.75 0 0 1 8 8H4ZM4 8H8.5A2.75 2.75 0 0 1 8.5 13.5H4Z",
                () -> format.accept("bold")),
            tool(
                "I",
                "italic",
                "Italic · Ctrl+I",
                "M7 2.5H13M3 13.5H9M10 2.5L6 13.5",
                () -> format.accept("italic")),
            tool(
                "S",
                "strikethrough",
                "Strikethrough",
                "M12 4C11 1 4 1 4 5C4 6 5 7 7 7M9 9C14 9 13 14 8 14C5 14 4 13 4 12M2 8H14",
                () -> format.accept("strikeThrough")),
            divider(),
            tool(
                "H",
                "heading",
                "Heading",
                "M3 3V13M11 3V13M3 8H11M14 10V14",
                () -> format.accept("heading")),
            tool(
                "↗",
                "link",
                "Link",
                "M6 10L10 6M5 9L3 11A2.8 2.8 0 0 0 7 15L10 12M11 7L13 5A2.8 2.8 0 0 0 9 1L6 4",
                () -> format.accept("link")),
            image,
            divider(),
            tool(
                "\uD83D\uDCCB",
                "bullets",
                "Bullet list",
                "M2 4H2.01M2 8H2.01M2 12H2.01M6 4H14M6 8H14M6 12H14",
                () -> format.accept("insertUnorderedList")),
            tool(
                "1.",
                "numbered-list",
                "Numbered list",
                "M1 2H2V6M1 6H3M1 10C1 8 4 8 3 10L1 13H4M7 4H14M7 11H14",
                () -> format.accept("insertOrderedList")),
            tool(
                "☐",
                "checklist",
                "Add or remove task from this row",
                "M2 3H12V13H2ZM5 7L7 9L14 2",
                checklist));
    image.setDisable(true);
    var fontLabel = new Label("Text size");
    fontLabel.setLabelFor(fontSize);
    var fontControl = new HBox(4, fontLabel, fontSize);
    fontControl.getStyleClass().add("editor-font-control");
    tools.getChildren().add(fontControl);
    if (completion != null) {
      tools.getChildren().add(completion);
    }
    var more = new MenuButton("…", icon("M3 8H3.01M8 8H8.01M13 8H13.01"));
    more.setId("editor-more");
    more.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    more.setAccessibleText("More formatting and editor views");
    more.setTooltip(new Tooltip("More formatting and editor views"));
    more.setFocusTraversable(true);
    more.getStyleClass().add("editor-more");
    more.getItems()
        .addAll(
            item("Quote block", "quote", () -> format.accept("quote")),
            item("Code block", "code-block", () -> format.accept("code")),
            item("Flowchart", "flowchart", () -> format.accept("flowchart")),
            new SeparatorMenuItem(),
            view("Text", "Read", changeMode),
            view("Markdown", "Write", changeMode),
            view("Split view", "Split", changeMode));
    getChildren().addAll(tools, more);
    selectMode("Read");
  }

  void setOnInsertImage(Runnable action) {
    image.setDisable(action == null);
    image.setOnAction(
        event -> {
          if (action != null) {
            action.run();
          }
        });
  }

  void selectMode(String mode) {
    modes.getToggles().stream()
        .filter(toggle -> mode.equals(toggle.getUserData()))
        .findFirst()
        .ifPresent(modes::selectToggle);
  }

  private RadioMenuItem view(String label, String mode, Consumer<String> action) {
    var item = new RadioMenuItem(label);
    item.setId("editor-view-" + mode.toLowerCase(java.util.Locale.ROOT));
    item.setUserData(mode);
    item.setToggleGroup(modes);
    item.setOnAction(event -> action.accept(mode));
    return item;
  }

  private static MenuItem item(String label, String id, Runnable action) {
    var item = new MenuItem(label);
    item.setId("format-" + id);
    item.setOnAction(event -> action.run());
    return item;
  }

  private static Button tool(String label, String id, String hint, String path, Runnable action) {
    var button = new Button(label, icon(path));
    button.setId("format-" + id);
    button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    button.setTooltip(new Tooltip(hint));
    button.setAccessibleText(hint);
    button.setFocusTraversable(false);
    button.setOnAction(event -> action.run());
    button.getStyleClass().add("format-button");
    return button;
  }

  private static Node divider() {
    var divider = new Separator(Orientation.VERTICAL);
    divider.getStyleClass().add("format-divider");
    return divider;
  }

  private static Node icon(String path) {
    var shape = new SVGPath();
    shape.setContent(path);
    shape.getStyleClass().add("editor-tool-icon");
    var box = new StackPane(shape);
    box.setMinSize(16, 16);
    box.setPrefSize(16, 16);
    box.setMaxSize(16, 16);
    box.setMouseTransparent(true);
    return box;
  }
}
