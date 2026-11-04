package dev.gorgi.notes.editor;

import dev.gorgi.notes.theme.Theme;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

/** Renders bundled Mermaid diagrams while retaining their original code for storage and export. */
final class PreviewDiagrams {
  static final String STARTER =
      """
      flowchart TD
          A[Start] --> B{Decision}
          B -->|Yes| C[Done]
          B -->|No| D[Try again]
          D --> B
      """;

  private static final class Scripts {
    private static final String LIBRARY = read("mermaid/mermaid-12.3.0.min.js");
    private static final String CONTROLLER = read("diagrams.js");
  }

  private final WebView view;
  private final Runnable changed;
  private final PauseTransition delay = new PauseTransition(Duration.millis(350));
  private Document document;
  private Theme theme;
  private JSObject controller;
  private EventListener changeListener;

  PreviewDiagrams(WebView view, Runnable changed) {
    this.view = Objects.requireNonNull(view);
    this.changed = Objects.requireNonNull(changed);
    delay.setOnFinished(event -> install());
    view.sceneProperty()
        .addListener(
            (obs, old, scene) -> {
              if (scene != null && document != null && hasDiagrams()) {
                delay.playFromStart();
              }
            });
  }

  void attach(Document document, Theme theme) {
    clear();
    this.document = document;
    this.theme = theme;
    if (document != null && hasDiagrams()) {
      delay.playFromStart();
    }
  }

  boolean insert(String source) {
    if (!install()) {
      return false;
    }
    controller.call("insert", source);
    return true;
  }

  private boolean hasDiagrams() {
    var blocks = document.getElementsByTagName("pre");
    for (int i = 0; i < blocks.getLength(); i++) {
      if ("mermaid-block".equals(((Element) blocks.item(i)).getAttribute("class"))) {
        return true;
      }
    }
    return false;
  }

  private boolean install() {
    if (document == null || document != view.getEngine().getDocument() || view.getScene() == null) {
      return false;
    }
    if (controller != null) {
      return true;
    }
    try {
      // Host evaluation loads trusted local code without permitting scripts or network requests
      // supplied by a note. Strings passed to JSObject.call remain data, never JavaScript source.
      view.getEngine().executeScript(Scripts.LIBRARY);
      controller = (JSObject) view.getEngine().executeScript(Scripts.CONTROLLER);
      changeListener = event -> changed.run();
      ((EventTarget) document).addEventListener("note-diagram-change", changeListener, false);
      controller.call(
          "start",
          theme.background(),
          theme.foreground(),
          theme.panel(),
          theme.accent(),
          theme.border(),
          theme.font());
      return true;
    } catch (RuntimeException | ExceptionInInitializerError error) {
      controller = null;
      var blocks = document.getElementsByTagName("pre");
      for (int i = 0; i < blocks.getLength(); i++) {
        var block = (Element) blocks.item(i);
        if (!"mermaid-block".equals(block.getAttribute("class"))) {
          continue;
        }
        block.setAttribute("data-diagram-state", "error");
        var status = document.createElement("div");
        status.setAttribute("data-diagram-ui", "true");
        status.setTextContent("Diagram renderer unavailable. The Mermaid source is preserved.");
        status.setAttribute("title", String.valueOf(error.getMessage()));
        block.appendChild(status);
      }
      return false;
    }
  }

  void clear() {
    delay.stop();
    if (document != null && changeListener != null) {
      ((EventTarget) document).removeEventListener("note-diagram-change", changeListener, false);
    }
    if (controller != null && document == view.getEngine().getDocument()) {
      try {
        controller.call("dispose");
      } catch (JSException ignored) {
        // WebKit may already have retired this page's JavaScript context during navigation.
      }
    }
    changeListener = null;
    controller = null;
    document = null;
  }

  private static String read(String resource) {
    try (var input = Objects.requireNonNull(PreviewDiagrams.class.getResourceAsStream(resource))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
