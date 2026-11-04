package dev.gorgi.notes.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.markdown.TaskLinks;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.theme.Theme;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in checks through real JavaFX/WebKit controls, using no personal notebook. */
@EnabledIfSystemProperty(named = "notes.toolbarUiTest", matches = "true")
class ToolbarUiTest {
  private static final String IMAGE =
      "data:image/png;base64,"
          + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=";
  private final List<NoteEditor> editors = new ArrayList<>();
  private ExecutorService rendering;
  private Stage stage;

  @BeforeAll
  static void startToolkit() throws Exception {
    var ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          ready.countDown();
        });
    assertTrue(ready.await(10, TimeUnit.SECONDS));
  }

  @BeforeEach
  void openWindow() throws Exception {
    rendering = Executors.newSingleThreadExecutor();
    stage = fx(Stage::new);
  }

  @AfterEach
  void closeWindow() throws Exception {
    fx(
        () -> {
          editors.forEach(NoteEditor::dispose);
          stage.hide();
          return null;
        });
    rendering.shutdownNow();
  }

  @AfterAll
  static void stopToolkit() {
    Platform.exit();
  }

  @Test
  void defaultsToTextAndCommitsPendingEditsWhenSwitchingViews() throws Exception {
    var note =
        new AtomicReference<>(
            new Workspace.Note("note", "Test", "collection", "First paragraph\n"));
    var editor =
        fx(
            () ->
                show(
                    new NoteEditor(
                        note.get(), List.of(), Theme.fallback(), rendering, note::set, url -> {})));
    awaitText(editor, "First paragraph");
    fx(
        () -> {
          assertEquals(
              List.of("Text", "Markdown", "Split view"),
              more(editor).getItems().stream()
                  .filter(RadioMenuItem.class::isInstance)
                  .map(MenuItem::getText)
                  .toList());
          assertTrue(((RadioMenuItem) menu(editor, "Text")).isSelected());
          assertEquals(1, split(editor).getItems().size());
          assertNotNull(editor.lookup("#markdown-preview"));
          assertNull(editor.lookup("#markdown-source"));
          assertTrue(editor.lookupAll(".mode-button").isEmpty());
          selectParagraph(editor);
          preview(editor)
              .getEngine()
              .executeScript(
                  "document.execCommand('insertText',false,'Changed in text"
                      + " view');document.dispatchEvent(new Event('input',{bubbles:true}));");
          menu(editor, "Markdown").fire();
          assertEquals("Changed in text view\n", editor.source().getText());
          assertEquals(editor.markdown(), note.get().markdown());
          assertNull(editor.lookup("#markdown-preview"));
          editor.source().setText("Changed in Markdown\n");
          menu(editor, "Split view").fire();
          assertEquals(2, split(editor).getItems().size());
          assertTrue(((RadioMenuItem) menu(editor, "Split view")).isSelected());
          return null;
        });
    awaitText(editor, "Changed in Markdown");
    fx(
        () -> {
          menu(editor, "Text").fire();
          assertNotNull(editor.lookup("#markdown-preview"));
          assertNull(editor.lookup("#markdown-source"));
          assertEquals("Changed in Markdown\n", note.get().markdown());
          assertThrows(IllegalArgumentException.class, () -> editor.setMode("invalid"));
          assertTrue(((RadioMenuItem) menu(editor, "Text")).isSelected());
          return null;
        });
  }

  @Test
  void formatsTheActiveSurfaceAndCreatesTasksFromText() throws Exception {
    var linked = new AtomicReference<TaskLinks.Change>();
    var editor =
        fx(
            () ->
                show(
                    new NoteEditor(
                        new Workspace.Note("note", "Test", "collection", "Format this paragraph\n"),
                        List.of(),
                        Theme.fallback(),
                        rendering,
                        note -> {},
                        url -> {},
                        linked::set,
                        id -> true)));
    awaitText(editor, "Format this paragraph");
    fx(
        () -> {
          selectParagraph(editor);
          button(editor, "bold").fire();
          menu(editor, "Markdown").fire();
          assertTrue(editor.markdown().contains("**Format this paragraph**"));
          editor.source().setText("Strike this text\n");
          editor.source().selectRange(0, "Strike this text".length());
          button(editor, "strikethrough").fire();
          assertTrue(editor.markdown().contains("~~Strike this text~~"));
          editor.source().setText("Quote this paragraph\n");
          menu(editor, "Text").fire();
          menu(editor, "Split view").fire();
          return null;
        });
    awaitText(editor, "Quote this paragraph");
    fx(
        () -> {
          selectParagraph(editor);
          assertTrue(more(editor).isFocusTraversable());
          more(editor).requestFocus();
          more(editor).show();
          assertTrue(more(editor).isShowing());
          menu(editor, "Quote block").fire();
          more(editor).hide();
          editor.flushPendingEdits();
          assertTrue(
              editor.markdown().contains("> Quote this paragraph"),
              "The overflow action formats the selected preview paragraph");
          menu(editor, "Markdown").fire();
          editor.source().setText("Task from text view\n");
          menu(editor, "Text").fire();
          return null;
        });
    awaitText(editor, "Task from text view");
    fx(
        () -> {
          selectParagraph(editor);
          button(editor, "checklist").fire();
          assertNotNull(linked.get());
          assertEquals("Task from text view", linked.get().text());
          assertTrue(editor.markdown().contains("<!-- task:" + linked.get().id() + " -->"));
          return null;
        });
  }

  @Test
  void taskDetailsImagesAndControlsWorkInNarrowAndThemedEditors() throws Exception {
    var task = new AtomicReference<>(new Workspace.Todo("task", "Task", false));
    var editor =
        fx(
            () ->
                show(
                    NoteEditor.forTask(
                        task.get(),
                        Theme.fallback(),
                        rendering,
                        task::set,
                        url -> {},
                        change -> {},
                        id -> true)));
    awaitText(editor, "");
    fx(
        () -> {
          assertTrue(((RadioMenuItem) menu(editor, "Text")).isSelected());
          preview(editor).requestFocus();
          preview(editor)
              .getEngine()
              .executeScript(
                  "document.body.focus();document.execCommand('insertText',false,'Task"
                      + " details');document.dispatchEvent(new Event('input',{bubbles:true}));");
          menu(editor, "Markdown").fire();
          assertTrue(task.get().markdown().contains("Task details"));
          ((CheckBox) editor.lookup("#task-done")).fire();
          assertTrue(task.get().done());
          menu(editor, "Text").fire();
          editor.setOnInsertImage(
              () -> editor.insertImage(new EmbeddedImage("test.png", IMAGE, 16, 16)));
          button(editor, "image").fire();
          assertTrue(editor.markdown().contains(IMAGE));
          return null;
        });
    awaitText(editor, "Task details");
    fx(
        () -> {
          assertTrue(
              preview(editor).getEngine().getDocument().getElementsByTagName("img").getLength()
                  > 0);
          @SuppressWarnings("unchecked")
          var size = (Spinner<Integer>) editor.lookup("#editor-font-size");
          size.getValueFactory().setValue(20);
          assertEquals(
              20 / Workspace.DEFAULT_EDITOR_FONT_SIZE, preview(editor).getFontScale(), .001);
          assertTrue(editor.source().getStyle().contains("20.0px"));
          for (double width : List.of(320.0, 900.0)) {
            editor.setMaxWidth(width);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertTrue(editor.getWidth() <= width + 1);
            for (var control : editor.lookupAll(".format-button")) {
              var bounds = editor.sceneToLocal(control.localToScene(control.getBoundsInLocal()));
              assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= editor.getWidth() + 1);
            }
            var menuBounds =
                editor.sceneToLocal(more(editor).localToScene(more(editor).getBoundsInLocal()));
            assertTrue(menuBounds.getMinX() >= 0 && menuBounds.getMaxX() <= editor.getWidth() + 1);
            more(editor).show();
            assertTrue(
                more(editor).isShowing(), "The three-dot menu stays usable at narrow widths");
            more(editor).hide();
          }
          saveScreenshot(editor.snapshot(null, null), Path.of("target/toolbar-light.png"));
          var dark =
              new Theme(
                  "#0e1113",
                  "#181c1f",
                  "#30373d",
                  "#d7dadc",
                  "#829098",
                  "#81a2be",
                  "#2a3236",
                  "SansSerif");
          stage.getScene().getRoot().setStyle(dark.fxStyle());
          editor.setTheme(dark);
          stage.getScene().getRoot().applyCss();
          stage.getScene().getRoot().layout();
          return null;
        });
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!fx(
        () ->
            Boolean.TRUE.equals(
                preview(editor)
                    .getEngine()
                    .executeScript(
                        "document.body &&"
                            + " getComputedStyle(document.body).backgroundColor==='rgb(14,"
                            + " 17, 19)'")))) {
      if (System.nanoTime() > until) {
        fail("The text view did not adopt the dark theme");
      }
      Thread.sleep(50);
    }
    fx(
        () -> {
          saveScreenshot(editor.snapshot(null, null), Path.of("target/toolbar-dark.png"));
          more(editor).show();
          var popup =
              javafx.stage.Window.getWindows().stream()
                  .filter(window -> window != stage && window.isShowing())
                  .filter(window -> window.getScene().lookup(".context-menu") != null)
                  .findFirst()
                  .orElseThrow();
          saveScreenshot(popup.getScene().snapshot(null), Path.of("target/toolbar-menu.png"));
          more(editor).hide();
          return null;
        });
  }

  private NoteEditor show(NoteEditor editor) {
    editors.add(editor);
    var root = new StackPane(editor);
    root.getStyleClass().add("app");
    root.setStyle(Theme.fallback().fxStyle());
    var scene = new Scene(root, 980, 560);
    scene.getStylesheets().add(NoteEditor.class.getResource("../app.css").toExternalForm());
    stage.setScene(scene);
    stage.show();
    return editor;
  }

  private static WebView preview(NoteEditor editor) {
    return (WebView) editor.lookup("#markdown-preview");
  }

  private static SplitPane split(NoteEditor editor) {
    return (SplitPane) editor.lookup(".editor-split");
  }

  private static MenuButton more(NoteEditor editor) {
    return (MenuButton) editor.lookup("#editor-more");
  }

  private static MenuItem menu(NoteEditor editor, String name) {
    return more(editor).getItems().stream()
        .filter(item -> name.equals(item.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static Button button(NoteEditor editor, String id) {
    return (Button) editor.lookup("#format-" + id);
  }

  private static void selectParagraph(NoteEditor editor) {
    preview(editor).requestFocus();
    preview(editor)
        .getEngine()
        .executeScript(
            "var range=document.createRange();range.selectNodeContents(document.querySelector('p'));var"
                + " selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);");
  }

  private static void awaitText(NoteEditor editor, String text) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!fx(
        () -> {
          var view = preview(editor);
          return view != null
              && view.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED
              && view.getEngine().getDocument() != null
              && view.getEngine()
                  .getDocument()
                  .getElementsByTagName("body")
                  .item(0)
                  .getTextContent()
                  .contains(text);
        })) {
      if (System.nanoTime() > until) {
        fail("The text editor did not render: " + text);
      }
      Thread.sleep(50);
    }
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var future = new FutureTask<>(action);
    Platform.runLater(future);
    return future.get(15, TimeUnit.SECONDS);
  }

  private static void saveScreenshot(Image image, Path path) throws IOException {
    int width = (int) image.getWidth();
    int height = (int) image.getHeight();
    var data = new ByteArrayOutputStream();
    try (var compressed = new DeflaterOutputStream(data)) {
      for (int y = 0; y < height; y++) {
        compressed.write(0);
        for (int x = 0; x < width; x++) {
          int color = image.getPixelReader().getArgb(x, y);
          compressed.write(color >> 16 & 255);
          compressed.write(color >> 8 & 255);
          compressed.write(color & 255);
          compressed.write(color >> 24 & 255);
        }
      }
    }
    try (var out = new DataOutputStream(Files.newOutputStream(path))) {
      out.writeLong(0x89504e470d0a1a0aL);
      var bytes = new ByteArrayOutputStream();
      var header = new DataOutputStream(bytes);
      header.writeInt(width);
      header.writeInt(height);
      header.write(new byte[] {8, 6, 0, 0, 0});
      chunk(out, "IHDR", bytes.toByteArray());
      chunk(out, "IDAT", data.toByteArray());
      chunk(out, "IEND", new byte[0]);
    }
  }

  private static void chunk(DataOutputStream out, String name, byte[] data) throws IOException {
    byte[] type = name.getBytes(StandardCharsets.US_ASCII);
    var crc = new CRC32();
    crc.update(type);
    crc.update(data);
    out.writeInt(data.length);
    out.write(type);
    out.write(data);
    out.writeInt((int) crc.getValue());
  }
}
