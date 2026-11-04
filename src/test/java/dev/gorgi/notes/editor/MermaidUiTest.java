package dev.gorgi.notes.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.storage.WorkspaceStore;
import dev.gorgi.notes.theme.Theme;
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
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real WebKit checks for bundled Mermaid, without a network connection or personal notebook. */
@EnabledIfSystemProperty(named = "notes.mermaidUiTest", matches = "true")
class MermaidUiTest {
  @TempDir Path directory;
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
  void rendersOfflineAndPreservesSourceWhenTextIsEditedSavedAndReopened() throws Exception {
    String source = "Before\n\n```mermaid\n" + PreviewDiagrams.STARTER + "```\n\nAfter\n";
    var note = new AtomicReference<>(new Workspace.Note("note", "Flowchart", "collection", source));
    var editor =
        fx(
            () ->
                show(
                    new NoteEditor(
                        note.get(), List.of(), Theme.fallback(), rendering, note::set, url -> {})));
    await(
        editor,
        "document.querySelector('pre.mermaid-block[data-diagram-state=ready] svg') !== null");
    Thread.sleep(150);
    fx(
        () -> {
          ToolbarUiTest.saveScreenshot(
              editor.snapshot(null, null), Path.of("target/flowchart-text.png"));
          assertEquals(source, editor.markdown());
          assertEquals(
              "none",
              js(
                  editor,
                  "getComputedStyle(document.querySelector('pre.mermaid-block > code')).display"));
          js(
              editor,
              "document.querySelector('p').textContent='Edited before';document.dispatchEvent(new"
                  + " Event('input',{bubbles:true}));");
          editor.flushPendingEdits();
          assertTrue(note.get().markdown().contains(PreviewDiagrams.STARTER));
          assertTrue(note.get().markdown().startsWith("Edited before"));
          assertFalse(note.get().markdown().contains("<svg"));
          assertFalse(note.get().markdown().contains("Edit diagram"));
          return null;
        });
    try (var store = new WorkspaceStore(directory)) {
      store.save(
          new Workspace(
              List.of(note.get()),
              List.of(new Workspace.Collection("collection", "Collection")),
              List.of(),
              List.of("note"),
              "note",
              false));
      var saved = store.load();
      assertEquals(note.get(), saved.notes().getFirst());
      fx(
          () -> {
            editor.dispose();
            var reopened =
                show(
                    new NoteEditor(
                        saved.notes().getFirst(),
                        saved.collections(),
                        Theme.fallback(),
                        rendering,
                        note::set,
                        url -> {}));
            reopened.setMode("Split");
            return null;
          });
    }
    await(editors.getLast(), "document.querySelector('.diagram-output svg') !== null");
  }

  @Test
  void malformedSourceShowsAnErrorAndInlineEditsRecoverWithoutLosingTheCode() throws Exception {
    String source = "```mermaid\nflowchart TD\nA[Broken\n```\n";
    var note = new AtomicReference<>(new Workspace.Note("note", "Invalid", "collection", source));
    var editor =
        fx(
            () ->
                show(
                    new NoteEditor(
                        note.get(), List.of(), Theme.fallback(), rendering, note::set, url -> {})));
    await(editor, "document.querySelector('[data-diagram-state=error]') !== null");
    fx(
        () -> {
          assertEquals(source, editor.markdown());
          assertTrue(
              js(editor, "document.querySelector('.diagram-status').textContent")
                  .toString()
                  .contains("Could not render"));
          js(
              editor,
              "document.querySelector('.diagram-toolbar button').click();var"
                  + " t=document.querySelector('.diagram-source-editor');t.value='flowchart LR\\n"
                  + "A[Fixed] --> B[Done]\\n"
                  + "';t.dispatchEvent(new Event('input',{bubbles:true}));");
          // Saving/closing need not wait for the asynchronous SVG render or its debounce timer.
          editor.flushPendingEdits();
          assertTrue(note.get().markdown().contains("flowchart LR\nA[Fixed] --> B[Done]"));
          return null;
        });
    await(editor, "document.querySelector('[data-diagram-state=ready] svg') !== null");
    fx(
        () -> {
          assertTrue(
              js(editor, "document.querySelector('.diagram-output').textContent")
                  .toString()
                  .contains("Fixed"));
          js(editor, "document.querySelectorAll('.diagram-toolbar button')[1].click()");
          editor.flushPendingEdits();
          assertFalse(editor.markdown().contains("```mermaid"));
          return null;
        });
  }

  @Test
  void toolbarInsertsEditableChartsInTextMarkdownAndTaskDetails() throws Exception {
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
    await(editor, "document.body !== null");
    fx(
        () -> {
          assertEquals("undefined", js(editor, "typeof mermaid"));
          flowchart(editor);
          assertTrue(task.get().markdown().contains(PreviewDiagrams.STARTER));
          assertEquals(
              false, js(editor, "document.querySelector('.diagram-source-editor').hidden"));
          assertTrue(editor.editRendered("all"));
          assertTrue(editor.editRendered("copy"));
          assertEquals(PreviewDiagrams.STARTER, Clipboard.getSystemClipboard().getString());
          return null;
        });
    await(editor, "document.querySelector('[data-diagram-state=ready] svg') !== null");
    fx(
        () -> {
          editor.setMode("Write");
          editor.source().setText("Existing task details\n");
          editor.source().positionCaret(editor.source().getLength());
          flowchart(editor);
          assertTrue(task.get().markdown().startsWith("Existing task details"));
          assertEquals(PreviewDiagrams.STARTER, editor.source().getSelectedText());
          editor.setMode("Read");
          return null;
        });
    await(editor, "document.querySelector('[data-diagram-state=ready] svg') !== null");
  }

  @Test
  void strictRenderingIgnoresUnsafeDirectivesAndTracksThemeChanges() throws Exception {
    String source =
        """
        ```mermaid
        %%{init: {"securityLevel":"loose","htmlLabels":true}}%%
        flowchart LR
          A[Start] --> B[Done]
        click A call chartCallback()
        ```
        """;
    var editor =
        fx(
            () ->
                show(
                    new NoteEditor(
                        new Workspace.Note("note", "Strict", "collection", source),
                        List.of(),
                        Theme.fallback(),
                        rendering,
                        note -> {},
                        url -> {})));
    await(editor, "document.querySelector('[data-diagram-state=ready] svg') !== null");
    fx(
        () -> {
          assertEquals(
              0,
              ((Number)
                      js(
                          editor,
                          "document.querySelectorAll('.diagram-output foreignObject,"
                              + " .diagram-output a').length"))
                  .intValue());
          assertEquals("strict", js(editor, "mermaid.mermaidAPI.getConfig().securityLevel"));
          assertEquals(source, editor.markdown());
          var dark =
              new Theme(
                  "#10151a",
                  "#20272e",
                  "#505862",
                  "#eeeeee",
                  "#9babb9",
                  "#80b0ff",
                  "#303944",
                  "SansSerif");
          stage.getScene().getRoot().setStyle(dark.fxStyle());
          editor.setTheme(dark);
          return null;
        });
    await(
        editor,
        "document.querySelector('[data-diagram-state=ready] svg') !== null &&"
            + " getComputedStyle(document.body).backgroundColor==='rgb(16, 21, 26)'");
    fx(
        () -> {
          assertEquals(source, editor.markdown());
          assertEquals(false, js(editor, "mermaid.mermaidAPI.getConfig().flowchart.htmlLabels"));
          assertEquals(
              "rgb(32, 39, 46)",
              js(
                  editor,
                  "getComputedStyle(document.querySelector('.diagram-output .node rect')).fill"),
              "The SVG node must follow the current app theme");
          editor.setMaxWidth(320);
          stage.getScene().getRoot().layout();
          return null;
        });
    await(editor, "document.documentElement.clientWidth <= 320");
    Thread.sleep(150);
    fx(
        () -> {
          assertEquals(
              true,
              js(
                  editor,
                  "document.querySelector('.diagram-output svg').getBoundingClientRect().width <="
                      + " document.querySelector('pre.mermaid-block').getBoundingClientRect().width"));
          ToolbarUiTest.saveScreenshot(
              editor.snapshot(null, null), Path.of("target/flowchart-dark.png"));
          return null;
        });
  }

  private NoteEditor show(NoteEditor editor) {
    editors.add(editor);
    var root = new StackPane(editor);
    root.getStyleClass().add("app");
    root.setStyle(Theme.fallback().fxStyle());
    var scene = new Scene(root, 1000, 650);
    scene.getStylesheets().add(NoteEditor.class.getResource("../app.css").toExternalForm());
    stage.setScene(scene);
    stage.show();
    return editor;
  }

  private static void flowchart(NoteEditor editor) {
    ((MenuButton) editor.lookup("#editor-more"))
        .getItems().stream()
            .filter(item -> "Flowchart".equals(item.getText()))
            .findFirst()
            .orElseThrow()
            .fire();
  }

  private static Object js(NoteEditor editor, String source) {
    return ((WebView) editor.lookup("#markdown-preview")).getEngine().executeScript(source);
  }

  private static void await(NoteEditor editor, String condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!fx(
        () -> {
          var view = (WebView) editor.lookup("#markdown-preview");
          return view != null
              && view.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED
              && Boolean.TRUE.equals(view.getEngine().executeScript(condition));
        })) {
      if (System.nanoTime() > until) {
        fail(
            "Mermaid did not reach the expected state: "
                + fx(
                    () ->
                        js(
                            editor,
                            "document.body ? document.body.innerHTML.slice(0,4000) : 'no body'")));
      }
      Thread.sleep(50);
    }
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var future = new FutureTask<>(action);
    Platform.runLater(future);
    return future.get(20, TimeUnit.SECONDS);
  }
}
