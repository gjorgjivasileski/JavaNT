package dev.gorgi.notes.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.markdown.ImageMarkup;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.storage.WorkspaceStore;
import dev.gorgi.notes.theme.Theme;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Focused window check that avoids desktop-dependent Robot tab and dialog gestures. */
@EnabledIfSystemProperty(named = "notes.imageUiTest", matches = "true")
class ImageReferenceUiTest {
  private static final String DATA = "data:image/png;base64,"
      + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=";
  @TempDir Path directory;

  @Test void editsCopiesRendersAndReopensImagesWithCompactSource() throws Exception {
    var ready = new CountDownLatch(1);
    Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    var rendering = Executors.newSingleThreadExecutor();
    var editors = new java.util.ArrayList<NoteEditor>();
    var stage = fx(Stage::new);
    var collection = new Workspace.Collection(Workspace.id(), "Images");
    var layout = new ImageMarkup.Layout(Workspace.id(), 16, 16, 120, 80, 1, false);
    String original = "Before\n\n" + ImageMarkup.markdown("photo.png", DATA, layout) + "\n\nAfter";
    var note = new AtomicReference<>(new Workspace.Note(Workspace.id(), "Test", collection.id(), original));
    var task = new AtomicReference<>(new Workspace.Todo(Workspace.id(), "Task", false));
    try {
      var editor = fx(() -> {
        var value = new NoteEditor(note.get(), List.of(collection), Theme.fallback(), rendering, note::set, url -> {});
        editors.add(value);
        stage.setScene(new Scene(value, 960, 640)); stage.show();
        assertEquals("Before\n\n![photo\\.png](image:1)\n\nAfter", value.source().getText());
        assertEquals(original, value.markdown());
        return value;
      });
      awaitImage(editor);
      fx(() -> {
        editor.setMode("Write");
        var source = editor.source();
        String compact = source.getText();
        int start = compact.indexOf("![");
        int end = compact.indexOf(')', start) + 1;
        source.selectRange(start, end); source.cut();
        assertFalse(note.get().markdown().contains(DATA));
        assertTrue(Clipboard.getSystemClipboard().getString().contains(DATA));
        source.undo();
        assertEquals(original, note.get().markdown());
        source.redo();
        assertFalse(note.get().markdown().contains(DATA));
        source.undo();
        source.positionCaret(source.getLength()); source.paste();
        assertFalse(source.getText().contains("base64,"));
        assertEquals(2, editor.markdown().split("data:image/", -1).length - 1);
        source.undo();
        assertEquals(original, editor.markdown());
        source.positionCaret(3); editor.updateMarkdown(original);
        assertEquals(3, source.getCaretPosition());
        source.appendText("\n\nAdded text.");
        assertTrue(note.get().markdown().contains(DATA));
        assertTrue(note.get().markdown().endsWith("Added text."));
        editor.setMode("Split");
        return null;
      });
      awaitImage(editor);
      fx(() -> {
        editor.selectImage(layout.id());
        assertTrue(editor.hasSelectedImage(), "The refreshed preview can select its image");
        editor.setSelectedImageFloating(true);
        editor.flushPendingEdits();
        assertTrue(note.get().markdown().contains("mode=floating"));
        assertFalse(editor.source().getText().contains("javafxnotes-image:"));
        assertFalse(editor.source().getText().contains("base64,"));
        editor.rename("Renamed"); editor.setCollection(collection.id());
        assertTrue(note.get().markdown().contains(DATA));
        stage.setWidth(480); stage.setHeight(500);
        editor.source().requestFocus();
        return null;
      });
      var taskEditor = fx(() -> {
        var value = NoteEditor.forTask(task.get(), Theme.fallback(), rendering, task::set,
            url -> {}, change -> {}, id -> true);
        editors.add(value);
        stage.getScene().setRoot(value);
        value.setMode("Write"); value.source().paste();
        assertTrue(task.get().markdown().contains(DATA), "Copy across documents includes image bytes");
        assertFalse(value.source().getText().contains("base64,"));
        value.source().positionCaret(3); value.updateTask(task.get());
        assertEquals(3, value.source().getCaretPosition());
        value.setMode("Split");
        return value;
      });
      awaitImage(taskEditor);
      Workspace loaded;
      try (var store = new WorkspaceStore(directory)) {
        store.save(new Workspace(List.of(note.get()), List.of(collection), List.of(task.get()),
            List.of(note.get().id()), note.get().id(), false));
        loaded = store.load();
      }
      assertTrue(loaded.notes().getFirst().markdown().contains(DATA));
      assertTrue(loaded.tasks().getFirst().markdown().contains(DATA));
      var reopened = fx(() -> {
        editor.dispose(); taskEditor.dispose(); editors.clear();
        var value = new NoteEditor(loaded.notes().getFirst(), loaded.collections(), Theme.fallback(),
            rendering, changed -> {}, url -> {});
        editors.add(value); stage.getScene().setRoot(value);
        assertFalse(value.source().getText().contains("base64,"));
        assertTrue(value.source().getText().contains("(image:"));
        assertEquals(loaded.notes().getFirst().markdown(), value.markdown());
        return value;
      });
      awaitImage(reopened);
    } finally {
      fx(() -> { editors.forEach(NoteEditor::dispose); stage.close(); return null; });
      rendering.shutdownNow(); Platform.exit();
    }
  }

  private static void awaitImage(NoteEditor editor) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!fx(() -> {
      var view = (WebView) editor.lookup("#markdown-preview");
      return view != null && view.getEngine().getDocument() != null
          && Boolean.TRUE.equals(view.getEngine().executeScript(
              "(function(){var i=document.querySelector('img');return document.body.contentEditable==='true'"
                  + " && !!i && i.complete && i.naturalWidth>0;})()"));
    })) {
      if (System.nanoTime() > until) fail("Image did not load in the preview");
      Thread.sleep(50);
    }
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var future = new FutureTask<>(action);
    Platform.runLater(future);
    return future.get(15, TimeUnit.SECONDS);
  }
}
