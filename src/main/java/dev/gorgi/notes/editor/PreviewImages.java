package dev.gorgi.notes.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.web.WebView;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

/** Image selection and editing in the locally generated, editable Markdown preview. */
final class PreviewImages {
  private static final String SCRIPT = readScript();

  private final WebView view;
  private final Runnable changed;
  private final ReadOnlyBooleanWrapper hasSelection = new ReadOnlyBooleanWrapper();
  private final ContextMenu menu = new ContextMenu();
  private EventListener changeListener;
  private EventListener selectionListener;
  private final EventHandler<ContextMenuEvent> contextListener = this::showContextMenu;
  private final ChangeListener<Number> layoutListener = (observable, oldValue, value) -> refresh();
  private Document document;
  private JSObject controller;

  PreviewImages(WebView view, Runnable changed) {
    this.view = Objects.requireNonNull(view);
    this.changed = Objects.requireNonNull(changed);
    var front = new MenuItem("Bring to front");
    front.setOnAction(event -> bringToFront());
    var back = new MenuItem("Send to back");
    back.setOnAction(event -> sendToBack());
    var delete = new MenuItem("Delete image");
    delete.setOnAction(event -> deleteSelected());
    var modes = new ToggleGroup();
    var inline = new RadioMenuItem("Inline with text");
    inline.setToggleGroup(modes);
    inline.setOnAction(event -> setFloating(false));
    var floating = new RadioMenuItem("Floating");
    floating.setToggleGroup(modes);
    floating.setOnAction(event -> setFloating(true));
    menu.setOnShowing(event -> modes.selectToggle(isFloating() ? floating : inline));
    menu.getItems().addAll(inline, floating, new SeparatorMenuItem(), front, back, delete);
    view.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextListener);
    view.fontScaleProperty().addListener(layoutListener);
    view.widthProperty().addListener(layoutListener);
    view.heightProperty().addListener(layoutListener);
  }

  /** Installs only trusted, packaged code; no Java object is exposed to the document. */
  void attach(Document document) {
    clear();
    if (document == null) {
      return;
    }
    this.document = document;
    controller = (JSObject) view.getEngine().executeScript(SCRIPT);
    // WebKit's native DOM-listener peers have a single registration lifetime.
    // Retain these wrappers for this document only; reusing a detached wrapper can use a stale
    // peer.
    changeListener =
        new EventListener() {
          @Override
          public void handleEvent(Event event) {
            imageChanged();
          }
        };
    selectionListener =
        new EventListener() {
          @Override
          public void handleEvent(Event event) {
            updateSelection();
          }
        };
    ((EventTarget) document).addEventListener("note-image-change", changeListener, false);
    ((EventTarget) document).addEventListener("note-image-selection", selectionListener, false);
    controller.call("normalizeIds");
    updateSelection();
  }

  boolean hasSelection() {
    return hasSelection.get();
  }

  ReadOnlyBooleanProperty hasSelectionProperty() {
    return hasSelection.getReadOnlyProperty();
  }

  void select(String id) {
    if (controller != null) {
      controller.call("select", id);
    }
  }

  void bringToFront() {
    command("bringToFront");
  }

  void sendToBack() {
    command("sendToBack");
  }

  void deleteSelected() {
    command("deleteSelected");
  }

  boolean isFloating() {
    return controller != null && Boolean.TRUE.equals(controller.call("isFloating"));
  }

  void setFloating(boolean floating) {
    if (controller != null) {
      controller.call("setFloating", floating);
    }
  }

  /** Detaches the old document before a source edit, navigation, or replacement render. */
  void clear() {
    menu.hide();
    if (document != null) {
      ((EventTarget) document).removeEventListener("note-image-change", changeListener, false);
      ((EventTarget) document)
          .removeEventListener("note-image-selection", selectionListener, false);
    }
    if (controller != null) {
      try {
        controller.call("dispose");
      } catch (JSException ignored) {
        // A completed WebEngine navigation may already have discarded the old script context.
      }
    }
    controller = null;
    document = null;
    changeListener = null;
    selectionListener = null;
    hasSelection.set(false);
  }

  void dispose() {
    clear();
    view.removeEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, contextListener);
    view.fontScaleProperty().removeListener(layoutListener);
    view.widthProperty().removeListener(layoutListener);
    view.heightProperty().removeListener(layoutListener);
  }

  private void refresh() {
    command("refresh");
  }

  private void command(String name) {
    if (controller != null) {
      controller.call(name);
    }
  }

  private void updateSelection() {
    // DOM callbacks run inside WebKit. Reentering JSObject.call from them is unsafe.
    var bodies = document == null ? null : document.getElementsByTagName("body");
    String id =
        bodies == null || bodies.getLength() == 0
            ? null
            : ((Element) bodies.item(0)).getAttribute("data-selected-image-id");
    hasSelection.set(controller != null && id != null && !id.isEmpty());
  }

  private void imageChanged() {
    updateSelection();
    changed.run();
  }

  private void showContextMenu(ContextMenuEvent event) {
    if (controller == null) {
      return;
    }
    if (!event.isKeyboardTrigger()) {
      var point = view.screenToLocal(event.getScreenX(), event.getScreenY());
      if (!Boolean.TRUE.equals(controller.call("selectAt", point.getX(), point.getY()))) {
        return;
      }
    }
    if (!hasSelection()) {
      return;
    }
    if (view.getScene() != null) {
      menu.setStyle(view.getScene().getRoot().getStyle());
    }
    menu.show(view, event.getScreenX(), event.getScreenY());
    event.consume();
  }

  private static String readScript() {
    try (var input = PreviewImages.class.getResourceAsStream("images.js")) {
      if (input == null) {
        throw new IllegalStateException("Missing image preview controls");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not load image preview controls", exception);
    }
  }
}
