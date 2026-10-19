package dev.gorgi.notes;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.editor.NoteEditor;
import dev.gorgi.notes.markdown.TaskLinks;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.storage.WorkspaceStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Exercises collection workflows through controls without desktop-dependent mouse gestures. */
@EnabledIfSystemProperty(named = "notes.collectionsUiTest", matches = "true")
class CollectionsUiTest {
  @TempDir Path directory;
  private NotesApp app;
  private Stage stage;

  @Test
  void scopesNotesAndTabsMovesDraftsAndRemembersEachCollectionAcrossRestarts() throws Exception {
    var linked = TaskLinks.toggle("Alpha text\n\n- [ ] Linked task\n", 0, false);
    var closed = TaskLinks.toggle("Closed alpha note\n\n- [x] Closed note task\n", 0, true);
    var beta = TaskLinks.toggle("Beta text\n\n- [ ] Beta task\n", 0, false);
    var original =
        new Workspace(
            List.of(
                new Workspace.Note("a1", "Alpha one", "alpha", linked.markdown()),
                new Workspace.Note("a2", "Alpha two", "alpha", closed.markdown()),
                new Workspace.Note("b1", "Beta one", "beta", beta.markdown())),
            List.of(
                new Workspace.Collection("alpha", "Alpha"),
                new Workspace.Collection("beta", "Beta"),
                new Workspace.Collection("gamma", "Gamma")),
            List.of(
                new Workspace.Todo("task", "Linked task", false, "a1", linked.id()),
                new Workspace.Todo(
                    "task-beta", "Beta task", false, "b1", beta.id(), "## Saved beta details"),
                new Workspace.Todo("task-closed", "Closed note task", true, "a2", closed.id()),
                new Workspace.Todo("independent", "Independent task", true)),
            List.of("a1", "b1"),
            "a1",
            false,
            Workspace.DEFAULT_SIDEBAR_WIDTH,
            Workspace.DEFAULT_SIDEBAR_TEXT_SIZE,
            Workspace.DEFAULT_EDITOR_FONT_SIZE,
            "alpha");
    try (var store = new WorkspaceStore(directory)) {
      store.save(original);
    }
    var ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          ready.countDown();
        });
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    System.setProperty("notes.dataDir", directory.toString());
    try {
      fx(
          () -> {
            startApp();
            assertEquals(Set.of("task", "independent"), taskIds());
            assertEquals("1/2", ((Label) control("taskCount")).getText());
            return null;
          });
      var alphaEditor = fx(this::editor);
      respondToDialog(() -> item(menu("Collections"), "New collection").fire(), "New empty");
      fx(
          () -> {
            assertEquals("New empty", selector().getValue().name());
            assertTrue(notes().getItems().isEmpty());
            assertTrue(tabs().getTabs().isEmpty());
            assertEquals(Set.of("independent"), taskIds());
            choose("Alpha");
            return null;
          });
      fx(
          () -> {
            assertEquals("Alpha", selector().getValue().name());
            assertEquals(Set.of("a1", "a2"), noteIds());
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task", "independent"), taskIds());
            assertTrue(
                menu("File").getItems().stream()
                    .noneMatch(item -> "New collection".equals(item.getText())));
            assertNotNull(item(menu("Collections"), "New collection"));
            choose("Beta");
            assertEquals(Set.of("b1"), noteIds());
            assertEquals(Set.of("b1"), tabIds());
            assertEquals(Set.of("task-beta", "independent"), taskIds());
            taskList().getParent().getChildrenUnmodifiable().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getText().equals("Clear completed"))
                .findFirst()
                .orElseThrow()
                .fire();
            assertEquals(Set.of("task-beta"), taskIds());
            assertEquals("0/1", ((Label) control("taskCount")).getText());
            item(menu("File"), "New note").fire();
            editor().rename("Beta created");
            editor().setMode("Write");
            editor().source().setText("New beta details");
            assertEquals(2, tabIds().size());
            assertTrue(taskIds().isEmpty(), "An open, unfocused note hides its tasks");
            assertEquals("0/0", ((Label) control("taskCount")).getText());
            var newTab = tabs().getSelectionModel().getSelectedItem();
            tabs().getSelectionModel().select(tab("b1"));
            assertEquals(Set.of("task-beta"), taskIds(), "Selecting a note restores its tasks");
            tabs().getSelectionModel().select(newTab);
            assertTrue(taskIds().isEmpty());
            return null;
          });
      String created =
          fx(() -> (String) tabs().getSelectionModel().getSelectedItem().getUserData());
      fx(
          () -> {
            item(menu("Collections"), "Alpha").fire();
            assertEquals("Alpha", selector().getValue().name());
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task"), taskIds());
            assertSame(
                alphaEditor, editor(), "Switching collections preserves the editor and its draft");
            var cell = noteCell("a2");
            assertNotNull(cell.getContextMenu(), "A note row has a right-click menu");
            var move = (Menu) item(cell.getContextMenu().getItems(), "Move to collection…");
            move.getOnShowing().handle(new Event(Menu.ON_SHOWING));
            assertTrue(item(move, "Alpha").isDisable());
            item(move, "Beta").fire();
            assertEquals(Set.of("a1"), noteIds());
            choose("Beta");
            assertEquals(Set.of("b1", "a2", created), noteIds());
            assertEquals(Set.of("b1", created), tabIds(), "Moving a closed note does not open it");
            assertEquals(Set.of("task-beta"), taskIds());
            tabs().getSelectionModel().select(tab(created));
            var selectedMove = (Menu) item(menu("Collections"), "Move note to collection…");
            selectedMove.getOnShowing().handle(new Event(Menu.ON_SHOWING));
            item(selectedMove, "Gamma").fire();
            assertEquals(Set.of("b1"), tabIds());
            choose("Gamma");
            assertEquals(Set.of(created), tabIds());
            selectedMove = (Menu) item(menu("Collections"), "Move note to collection…");
            selectedMove.getOnShowing().handle(new Event(Menu.ON_SHOWING));
            item(selectedMove, "Beta").fire();
            assertTrue(tabs().getTabs().isEmpty());
            choose("Beta");
            notes()
                .getSelectionModel()
                .select(
                    notes().getItems().stream()
                        .filter(note -> note.id().equals("a2"))
                        .findFirst()
                        .orElseThrow());
            notes()
                .fireEvent(
                    new KeyEvent(
                        KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(Set.of("b1", "a2", created), tabIds());
            assertEquals(Set.of("task-closed"), taskIds());
            assertEquals("1/1", ((Label) control("taskCount")).getText());
            tabs().getSelectionModel().select(tab("b1"));
            assertEquals(Set.of("task-beta"), taskIds());
            item(menu("File"), "Close tab").fire();
            assertFalse(taskIds().contains("task-beta"), "Closing a source note hides its tasks");
            tabs().getSelectionModel().select(tab("a2"));
            assertEquals(Set.of("task-closed"), taskIds());
            choose("Alpha");
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task"), taskIds());
            return null;
          });
      awaitPreview(alphaEditor);
      fx(
          () -> {
            var view = (WebView) alphaEditor.lookup("#markdown-preview");
            view.getEngine()
                .executeScript(
                    "var p=document.createElement('p');"
                        + "p.textContent='Pending rendered draft';document.body.appendChild(p);"
                        + "document.dispatchEvent(new Event('input',{bubbles:true}));");
            var move = (Menu) item(tab("a1").getContextMenu().getItems(), "Move to collection…");
            move.getOnShowing().handle(new Event(Menu.ON_SHOWING));
            item(move, "Gamma").fire();
            assertTrue(notes().getItems().isEmpty());
            assertTrue(tabs().getTabs().isEmpty());
            assertTrue(taskIds().isEmpty());
            assertFalse(selector().isDisabled(), "Empty collections remain selectable");
            choose("Gamma");
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task"), taskIds());
            assertSame(alphaEditor, editor());
            assertTrue(
                editor().markdown().contains("Pending rendered draft"),
                "Moving a note commits pending preview edits");
            item(menu("File"), "Save now").fire();
            stopApp();
            return null;
          });
      try (var store = new WorkspaceStore(directory)) {
        var saved = store.load();
        assertEquals("gamma", saved.activeCollectionId());
        assertEquals(Set.of("a1", "a2", created), Set.copyOf(saved.openTabs()));
        assertEquals("a1", saved.tasks().getFirst().sourceNoteId());
        assertEquals(linked.id(), saved.tasks().getFirst().sourceItemId());
        assertEquals(
            Set.of("task", "task-beta", "task-closed"),
            saved.tasks().stream().map(Workspace.Todo::id).collect(Collectors.toSet()),
            "Closing, hiding, and clearing visible tasks preserve hidden tasks");
        assertEquals(
            "## Saved beta details",
            saved.tasks().stream()
                .filter(task -> task.id().equals("task-beta"))
                .findFirst()
                .orElseThrow()
                .markdown());
      }
      fx(
          () -> {
            startApp();
            assertEquals("Gamma", selector().getValue().name());
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task"), taskIds());
            choose("Beta");
            assertEquals(
                Set.of(created, "a2"), tabIds(), "Closed tabs stay closed after restarting");
            assertTrue(taskIds().isEmpty(), "The selected note has no tasks");
            tabs().getSelectionModel().select(tab("a2"));
            assertEquals(Set.of("task-closed"), taskIds());
            notes()
                .getSelectionModel()
                .select(
                    notes().getItems().stream()
                        .filter(note -> note.id().equals("b1"))
                        .findFirst()
                        .orElseThrow());
            notes()
                .fireEvent(
                    new KeyEvent(
                        KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(Set.of("task-beta"), taskIds(), "Reopening a note restores its tasks");
            item(menu("File"), "Close tab").fire();
            tabs().getSelectionModel().select(tab("a2"));
            assertEquals(Set.of("task-closed"), taskIds());
            choose("Alpha");
            assertTrue(tabs().getTabs().isEmpty());
            assertTrue(taskIds().isEmpty());
            choose("Gamma");
            return null;
          });
      respondToDialog(
          () -> item((Menu) item(menu("Collections"), "Rename collection…"), "Gamma").fire(),
          "Renamed gamma");
      fx(
          () -> {
            assertEquals("Renamed gamma", selector().getValue().name());
            assertEquals(Set.of("a1"), tabIds());
            assertEquals(Set.of("task"), taskIds());
            return null;
          });
      respondToDialog(
          () ->
              item((Menu) item(menu("Collections"), "Delete collection…"), "Renamed gamma").fire(),
          null);
      fx(
          () -> {
            assertEquals("Alpha", selector().getValue().name());
            assertEquals(
                Set.of("a1"), tabIds(), "Deleting a collection transfers its open note tabs");
            assertEquals(Set.of("task"), taskIds());
            assertTrue(editor().markdown().contains("Pending rendered draft"));
            item(menu("File"), "Save now").fire();
            stopApp();
            return null;
          });
      try (var store = new WorkspaceStore(directory)) {
        var saved = store.load();
        assertEquals("alpha", saved.activeCollectionId());
        assertEquals(
            "alpha",
            saved.notes().stream()
                .filter(note -> note.id().equals("a1"))
                .findFirst()
                .orElseThrow()
                .collectionId());
      }
      verifyTabReordering(created);
    } finally {
      fx(
          () -> {
            stopApp();
            return null;
          });
      System.clearProperty("notes.dataDir");
      Platform.exit();
    }
  }

  private void verifyTabReordering(String created) throws Exception {
    String taskTab = Workspace.taskTabId("task-closed");
    var noteEditor =
        fx(
            () -> {
              startApp();
              choose("Beta");
              tabs().getSelectionModel().select(tab("a2"));
              var editor = editor();
              editor.setMode("Write");
              editor.source().appendText("\nReordered note draft\n");
              taskList().getSelectionModel().selectFirst();
              taskList()
                  .fireEvent(
                      new KeyEvent(
                          KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
              assertEquals(taskTab, tabs().getSelectionModel().getSelectedItem().getUserData());
              assertTrue(taskIds().isEmpty(), "Selecting a task hides its source note's tasks");
              editor().setMode("Write");
              editor().source().setText("## Task draft saved after dragging\n");
              assertEquals(List.of(created, "a2", taskTab), tabOrder());
              return editor;
            });
    var taskEditor = fx(this::editor);
    dragTab(taskTab, 0);
    fx(
        () -> {
          assertEquals(List.of(taskTab, created, "a2"), tabOrder());
          assertSame(taskEditor, editor());
          return null;
        });
    dragTab("a2", 0);
    fx(
        () -> {
          assertEquals(List.of("a2", taskTab, created), tabOrder());
          assertSame(noteEditor, editor());
          assertEquals(Set.of("task-closed"), taskIds());
          return null;
        });
    dragTab(taskTab, 2);
    dragTab("a2", 2);
    var expectedOrder = List.of(created, taskTab, "a2");
    fx(
        () -> {
          assertEquals(expectedOrder, tabOrder());
          choose("Alpha");
          assertEquals(
              Set.of("a1", taskTab), tabIds(), "Hidden notes remain open in their collection");
          choose("Beta");
          assertEquals(expectedOrder, tabOrder(), "Collection switches preserve the dragged order");
          tabs().getSelectionModel().select(tab(taskTab));
          assertSame(taskEditor, editor());
          assertTrue(editor().markdown().contains("Task draft saved after dragging"));
          tabs().getSelectionModel().select(tab("a2"));
          assertSame(noteEditor, editor());
          assertTrue(editor().markdown().contains("Reordered note draft"));
          assertEquals(Set.of("task-closed"), taskIds());
          item(menu("File"), "Save now").fire();
          stopApp();
          return null;
        });
    List<String> savedOrder;
    try (var store = new WorkspaceStore(directory)) {
      savedOrder = store.load().openTabs();
      assertEquals(expectedOrder, savedOrder.stream().filter(id -> !id.equals("a1")).toList());
      assertTrue(savedOrder.contains("a1"), "Saving preserves hidden collection tabs");
    }
    var allTabs = savedOrder;
    fx(
        () -> {
          startApp();
          assertEquals("Beta", selector().getValue().name());
          assertEquals(expectedOrder, tabOrder(), "Restarting preserves note and task tab order");
          assertEquals("a2", tabs().getSelectionModel().getSelectedItem().getUserData());
          assertEquals(Set.of("task-closed"), taskIds());
          assertTrue(editor().markdown().contains("Reordered note draft"));
          tabs().getSelectionModel().select(tab(taskTab));
          assertTrue(taskIds().isEmpty());
          assertTrue(editor().markdown().contains("Task draft saved after dragging"));
          choose("All notes");
          assertEquals(allTabs, tabOrder(), "The combined view retains the complete saved order");
          stopApp();
          return null;
        });
  }

  /** Sends a press-drag-release gesture through the real tab skin without desktop coordinates. */
  private void dragTab(String id, int targetIndex) throws Exception {
    var header = fx(() -> tabHeader(id));
    var gesture =
        fx(
            () -> {
              tabs().applyCss();
              tabs().layout();
              var from = header.localToScene(header.getBoundsInLocal());
              var target = tabHeader(tabOrder().get(targetIndex));
              var to = target.localToScene(target.getBoundsInLocal());
              double destination =
                  from.getMinX() > to.getMinX()
                      ? to.getMinX() + from.getWidth() / 2 + 1
                      : to.getMaxX() - from.getWidth() / 2 - 1;
              mouse(header, MouseEvent.MOUSE_PRESSED, from.getCenterX(), from.getCenterY());
              return new double[] {from.getCenterX(), from.getCenterY(), destination};
            });
    try {
      for (int step = 1; step <= 12; step++) {
        double x = gesture[0] + (gesture[2] - gesture[0]) * step / 12;
        fx(
            () -> {
              mouse(header, MouseEvent.MOUSE_DRAGGED, x, gesture[1]);
              return null;
            });
        Thread.sleep(25);
      }
    } finally {
      fx(
          () -> {
            mouse(header, MouseEvent.MOUSE_RELEASED, gesture[2], gesture[1]);
            return null;
          });
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!fx(() -> tabOrder().get(targetIndex).equals(id))) {
      if (System.nanoTime() > deadline) {
        fail("Dragging " + id + " to index " + targetIndex + " left tabs in " + fx(this::tabOrder));
      }
      Thread.sleep(25);
    }
    assertEquals(id, fx(() -> tabs().getSelectionModel().getSelectedItem().getUserData()));
  }

  private Node tabHeader(String id) {
    tabs().applyCss();
    tabs().layout();
    return tabs().lookupAll(".tab").stream()
        .filter(node -> tab(id).getId().equals(node.getId()))
        .findFirst()
        .orElseThrow();
  }

  private static void mouse(Node header, EventType<MouseEvent> type, double x, double y) {
    header.fireEvent(
        new MouseEvent(
            type,
            x,
            y,
            x,
            y,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            type != MouseEvent.MOUSE_RELEASED,
            false,
            false,
            false,
            false,
            type == MouseEvent.MOUSE_PRESSED,
            null));
  }

  private void startApp() throws Exception {
    app = new NotesApp();
    app.init();
    stage = new Stage();
    app.start(stage);
  }

  private void stopApp() throws Exception {
    if (stage != null) {
      stage.hide();
      stage = null;
    }
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private Menu menu(String name) {
    return ((MenuBar) stage.getScene().lookup(".menu-bar"))
        .getMenus().stream().filter(menu -> name.equals(menu.getText())).findFirst().orElseThrow();
  }

  private static MenuItem item(Menu menu, String label) {
    return item(menu.getItems(), label);
  }

  private static MenuItem item(List<MenuItem> items, String label) {
    return items.stream().filter(item -> label.equals(item.getText())).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private ComboBox<Workspace.Collection> selector() {
    return (ComboBox<Workspace.Collection>) control("headerCollection");
  }

  @SuppressWarnings("unchecked")
  private ListView<Workspace.Note> notes() {
    return (ListView<Workspace.Note>) control("noteList");
  }

  @SuppressWarnings("unchecked")
  private ListView<Workspace.Todo> taskList() {
    return (ListView<Workspace.Todo>) control("taskList");
  }

  private void choose(String name) {
    selector()
        .setValue(
            selector().getItems().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow());
  }

  private TabPane tabs() {
    return (TabPane) control("tabs");
  }

  private Object control(String name) {
    try {
      var field = NotesApp.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(app);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private Tab tab(String id) {
    return tabs().getTabs().stream()
        .filter(tab -> id.equals(tab.getUserData()))
        .findFirst()
        .orElseThrow();
  }

  private Set<String> tabIds() {
    return tabs().getTabs().stream()
        .map(tab -> (String) tab.getUserData())
        .collect(Collectors.toSet());
  }

  private List<String> tabOrder() {
    return tabs().getTabs().stream().map(tab -> (String) tab.getUserData()).toList();
  }

  private Set<String> noteIds() {
    return notes().getItems().stream().map(Workspace.Note::id).collect(Collectors.toSet());
  }

  private Set<String> taskIds() {
    return taskList().getItems().stream().map(Workspace.Todo::id).collect(Collectors.toSet());
  }

  private NoteEditor editor() {
    return (NoteEditor) tabs().getSelectionModel().getSelectedItem().getContent();
  }

  private ListCell<?> noteCell(String id) {
    var list = notes();
    var note =
        list.getItems().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    var cell = list.getCellFactory().call(list);
    cell.updateListView(list);
    cell.updateIndex(list.getItems().indexOf(note));
    return cell;
  }

  private static void awaitPreview(NoteEditor editor) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!fx(
        () -> {
          var preview = (WebView) editor.lookup("#markdown-preview");
          return preview.getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED;
        })) {
      if (System.nanoTime() > deadline) {
        fail("Preview did not load");
      }
      Thread.sleep(50);
    }
  }

  private void respondToDialog(Runnable action, String text) throws Exception {
    fx(
        () -> {
          Platform.runLater(
              () -> {
                var pane =
                    Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .map(window -> window.getScene().getRoot())
                        .filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast)
                        .findFirst()
                        .orElseThrow();
                if (text != null) {
                  ((TextField) pane.lookup(".text-field")).setText(text);
                }
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
              });
          action.run();
          return null;
        });
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var future = new FutureTask<>(action);
    Platform.runLater(future);
    return future.get(20, TimeUnit.SECONDS);
  }
}
