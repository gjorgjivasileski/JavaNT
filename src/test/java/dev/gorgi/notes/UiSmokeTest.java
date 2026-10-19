package dev.gorgi.notes;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.editor.EmbeddedImage;
import dev.gorgi.notes.editor.NoteEditor;
import dev.gorgi.notes.markdown.ImageMarkup;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.storage.WorkspaceStore;
import dev.gorgi.notes.theme.Theme;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.robot.Robot;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import netscape.javascript.JSObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.html.HTMLInputElement;

/** Opt-in: opens a real JavaFX window and never uses the user's notebook. */
@EnabledIfSystemProperty(named = "notes.uiTest", matches = "true")
class UiSmokeTest {
    @TempDir Path data;
    private NotesApp app;
    private Stage stage;

    @Test void createEditPreviewTasksMoveSidebarAndReopenSavedWorkspace() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        System.setProperty("notes.dataDir", data.toString());
        try {
            fx(() -> { app = new NotesApp(); app.init(); stage = new Stage(); app.start(stage); stage.setFullScreen(true); return null; });
            await(() -> fxUnchecked(() -> stage.getScene().getWidth() > 900));
            await(() -> fxUnchecked(() -> activePreview() != null && activePreview().getEngine().getDocument() != null));
            // WebKit paints asynchronously after the full-screen resize and DOM load.
            Thread.sleep(400);
            fx(() -> { saveScreenshot(stage.getScene(), Path.of("target/ui-initial.png")); return null; });
            verifySidebarSizing();
            verifySidebarSectionDrag();
            verifySidebarSizePreferences();
            verifyHeaderLayout();
            clickDialogFromMenu("Help", "About JavaFX Notes", null);
            fx(() -> { ((Button) stage.getScene().lookup("#new-note")).fire(); return null; });
            clickDialogFromMenu("File", "Delete…", null);
            fx(() -> {
                ((Button) stage.getScene().lookup("#new-note")).fire();
                return null;
            });
            verifyRename("Unicode — белешка");
            fx(() -> {
                var editor = activeEditor();
                editor.source().setText("# A working notebook\n\nKeep **this idea**.\n\n- [ ] Render Markdown\n\n"
                        + "```\nplain code\n```\n\n| Item | Status |\n| --- | --- |\n| Notebook | Open |\n\n"
                        + "[Documentation](https://openjfx.io/)\n");
                return null;
            });
            await(() -> fxUnchecked(() -> {
                if (activePreview() == null) return false;
                var doc = activePreview().getEngine().getDocument();
                return doc != null && doc.getElementsByTagName("strong").getLength() == 1
                        && doc.getElementsByTagName("h1").item(0).getTextContent().equals("A working notebook");
            }));
            fx(() -> {
                assertTrue(taskList().getItems().isEmpty(), "Typing a checklist does not create a sidebar task");
                activeEditor().setMode("Read");
                var document = activePreview().getEngine().getDocument();
                assertEquals("true", ((Element) document.getElementsByTagName("body").item(0)).getAttribute("contenteditable"));
                assertFalse(((Element) previewCheckbox()).hasAttribute("data-task-id"),
                        "A checkbox is unlinked until the user first clicks it");
                // Real WebKit DOM attributes can be null when absent, unlike our XML fixtures.
                // Edit before the first checkbox click so saving exercises an unlinked checklist,
                // a fence without a language class, and table cells without alignment attributes.
                activePreview().requestFocus();
                var paragraph = document.getElementsByTagName("p").item(0);
                var range = (JSObject) ((JSObject) document).call("createRange");
                range.call("selectNodeContents", paragraph); range.call("collapse", false);
                selectPreviewRange(range);
                assertEquals(Boolean.TRUE, ((JSObject) document).call("execCommand", "insertText", false,
                        " Unlinked text saved."));
                return null;
            });
            await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("Unlinked text saved.")));
            fx(() -> {
                var markdown = activeEditor().source().getText();
                assertTrue(markdown.contains("**this idea**. Unlinked text saved."));
                assertTrue(markdown.contains("- [ ] Render Markdown"));
                assertTrue(markdown.contains("```\nplain code\n```"));
                assertTrue(markdown.contains("| Notebook | Open |"));
                assertTrue(markdown.contains("[Documentation](<https://openjfx.io/>)"));
                assertFalse(markdown.contains("<!-- task:"), "Editing text must not assign a task link");
                assertTrue(taskList().getItems().isEmpty(), "Editing an unlinked checklist must not create tasks");
                return null;
            });
            var deletedTaskId = verifyTaskDeletionAndCreateIndependentTask();
            var toolbarDeletedIds = verifyToolbarDeletionWithDetails();
            await(() -> fxUnchecked(() -> previewCheckbox() != null));
            fx(() -> {
                var document = activePreview().getEngine().getDocument();
                ((JSObject) document.getElementsByTagName("input").item(0)).call("click");
                return null;
            });
            await(() -> fxUnchecked(() -> taskList().getItems().size() == 2
                    && taskList().getItems().stream().anyMatch(task -> task.linked() && task.done())));
            var linkedTask = fx(() -> {
                var linked = taskList().getItems().stream().filter(Workspace.Todo::linked).findFirst().orElseThrow();
                assertEquals("Render Markdown", linked.text());
                assertNotEquals(linked.id(), linked.sourceNoteId(), "The task and its source note are separate entities");
                assertNotEquals(linked.id(), linked.sourceItemId(), "The task has its own identity");
                assertEquals(linked.sourceNoteId(), ((TabPane) stage.getScene().lookup("#note-tabs"))
                        .getSelectionModel().getSelectedItem().getUserData());
                assertTrue(activeEditor().source().getText().contains("- [x] Render Markdown <!-- task:" + linked.sourceItemId() + " -->"));
                assertEquals(linked.sourceItemId(), ((Element) previewCheckbox()).getAttribute("data-task-id"));
                // Repeated clicks update one task, retaining its identity.
                ((JSObject) previewCheckbox()).call("click");
                assertFalse(taskList().getItems().stream().filter(Workspace.Todo::linked).findFirst().orElseThrow().done());
                ((JSObject) previewCheckbox()).call("click");
                assertEquals(2, taskList().getItems().size());
                assertTrue(taskList().getItems().stream().anyMatch(task -> task.id().equals(linked.id()) && task.done()));
                return linked;
            });
            fx(() -> {
                // Use WebKit's editing command and real input events rather than changing the source TextArea.
                var preview = activePreview(); preview.requestFocus();
                var document = preview.getEngine().getDocument();
                var paragraph = document.getElementsByTagName("p").item(0);
                var range = (JSObject) ((JSObject) document).call("createRange");
                range.call("selectNodeContents", paragraph); range.call("collapse", false);
                selectPreviewRange(range);
                assertEquals(Boolean.TRUE, ((JSObject) document).call("execCommand", "insertText", false, " Edited in Read mode."));
                return null;
            });
            await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("Edited in Read mode.")));
            fx(() -> {
                var document = activePreview().getEngine().getDocument();
                var paragraph = document.getElementsByTagName("p").item(0);
                Node editedText = paragraph.getLastChild();
                int start = editedText.getTextContent().indexOf("Edited");
                assertTrue(start >= 0, "The inserted text remains editable in the rendered paragraph");
                var range = (JSObject) ((JSObject) document).call("createRange");
                range.call("setStart", editedText, start); range.call("setEnd", editedText, start + "Edited".length());
                selectPreviewRange(range);
                activeEditor().lookupAll(".format-button").stream()
                        .filter(node -> node instanceof Button button && button.getText().equals("B"))
                        .map(Button.class::cast).findFirst().orElseThrow().fire();
                return null;
            });
            await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("**Edited** in Read mode.")));
            fx(() -> {
                var source = activeEditor().source();
                assertTrue(source.getText().contains("**this idea**"), "Editing rendered text preserves existing formatting");
                assertTrue(source.getText().contains("<!-- task:" + linkedTask.sourceItemId() + " -->"), "Rendered edits preserve the task link");
                assertEquals(2, taskList().getItems().size());
                source.setText(source.getText().replace("- [x] Render Markdown", "- [ ] Review Markdown"));
                return null;
            });
            await(() -> fxUnchecked(() -> taskList().getItems().stream()
                    .anyMatch(task -> task.id().equals(linkedTask.id()) && task.text().equals("Review Markdown") && !task.done())));
            await(() -> fxUnchecked(() -> previewCheckbox() != null && !previewCheckbox().getChecked()
                    && activePreview().getEngine().getDocument().getElementsByTagName("li").item(0).getTextContent().contains("Review Markdown")));
            await(() -> fxUnchecked(() -> taskCheckbox("Review Markdown") != null));
            fx(() -> {
                // Clicking the sidebar before the debounce expires must keep the freshly edited task label.
                var preview = activePreview(); preview.requestFocus();
                var document = preview.getEngine().getDocument();
                var label = document.getElementsByTagName("li").item(0).getLastChild();
                int start = label.getTextContent().indexOf("Review Markdown");
                assertTrue(start >= 0);
                var range = (JSObject) ((JSObject) document).call("createRange");
                range.call("setStart", label, start); range.call("setEnd", label, start + "Review Markdown".length());
                selectPreviewRange(range);
                assertEquals(Boolean.TRUE, ((JSObject) document).call("execCommand", "insertText", false, "Review the notebook"));
                taskCheckbox("Review Markdown").fire();
                return null;
            });
            await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("- [x] Review the notebook")
                    && previewCheckbox() != null && previewCheckbox().getChecked()));
            fx(() -> {
                assertEquals(linkedTask.sourceItemId(), ((Element) previewCheckbox()).getAttribute("data-task-id"));
                assertEquals(2, taskList().getItems().size(), "Sidebar synchronization never duplicates a linked task");
                assertTrue(taskList().getItems().stream().anyMatch(task -> task.id().equals(linkedTask.id())
                        && task.text().equals("Review the notebook") && task.done()));
                return null;
            });
            var projectNoteId = fx(() -> (String) tabs().getSelectionModel().getSelectedItem().getUserData());
            clickDialogFromMenu("Collections", "New collection", "Projects");
            fx(() -> {
                assertEquals("Projects", headerCollection().getValue().name());
                assertTrue(tabs().getTabs().stream().noneMatch(tab -> tab.getStyleClass().contains("note-tab")),
                        "A new collection does not show other collections' note tabs");
                selectCollectionFilter("Inbox");
                selectTab(projectNoteId);
                var move = (Menu) menu("Collections", "Move note to collection…");
                move.getOnShowing().handle(new javafx.event.Event(Menu.ON_SHOWING));
                move.getItems().stream().filter(item -> "Projects".equals(item.getText())).findFirst().orElseThrow().fire();
                selectCollectionFilter("Projects");
                selectTab(projectNoteId);
                assertEquals("Projects", headerCollection().getValue().name());
                return null;
            });
            verifyCollectionMenus();
            fx(() -> {
                taskCheckbox("Finish the notebook").fire();
                var right = (CheckMenuItem) menu("View", "Sidebar on right"); right.setSelected(true); right.fire();
                var split = (SplitPane) ((BorderPane) stage.getScene().getRoot()).getCenter();
                assertTrue(split.getItems().getLast().getStyleClass().contains("sidebar"));
                assertTrue(stage.getWidth() > 850, "Run the smoke test on a desktop large enough for the full sidebar");
                var search = (TextField) stage.getScene().lookup("#search-notes"); search.setText("not-present-in-any-note");
                return null;
            });
            await(() -> fxUnchecked(() -> ((ListView<?>) stage.getScene().lookup("#notes-list")).getItems().isEmpty()));
            fx(() -> { ((TextField) stage.getScene().lookup("#search-notes")).clear(); return null; });
            var toolbarTask = fx(() -> {
                activeEditor().setMode("Write");
                var source = activeEditor().source();
                source.appendText("\n\nWrite mode task details\n\nRead mode task details\n");
                // A collapsed selection in the middle of a row must use the entire row as the task title.
                source.positionCaret(source.getText().indexOf("Write mode task details") + 8); source.requestFocus();
                checklistTool().fire();
                assertEquals(3, taskList().getItems().size(), "The toolbar creates a task before any completion click");
                var task = taskList().getItems().stream().filter(t -> t.text().equals("Write mode task details"))
                        .findFirst().orElseThrow();
                assertFalse(task.done()); assertTrue(task.linked());
                assertTrue(source.getText().contains("- [ ] Write mode task details <!-- task:" + task.sourceItemId() + " -->"));
                source.positionCaret(source.getText().indexOf("Write mode task details") + 8);
                checklistTool().fire();
                assertEquals(2, taskList().getItems().size(), "Applying the toolbar again removes an empty task without a dialog");
                assertTrue(source.getText().contains("- Write mode task details"), "Removing a checkbox preserves its text and list bullet");
                assertFalse(source.getText().contains("<!-- task:" + task.sourceItemId() + " -->"));
                assertNull(showingDialog(), "Tasks without details do not require confirmation");
                source.positionCaret(source.getText().indexOf("Write mode task details") + 8);
                checklistTool().fire();
                var recreated = taskList().getItems().stream().filter(t -> t.text().equals("Write mode task details"))
                        .findFirst().orElseThrow();
                assertNotEquals(task.id(), recreated.id(), "Re-adding the checkbox creates a new task");
                activeEditor().setMode("Read");
                return recreated;
            });
            await(() -> fxUnchecked(() -> previewParagraph("Read mode task details") != null));
            var firstReadToolbarTask = fx(() -> {
                var document = activePreview().getEngine().getDocument();
                var paragraph = previewParagraph("Read mode task details");
                activePreview().requestFocus();
                var range = (JSObject) ((JSObject) document).call("createRange");
                range.call("setStart", paragraph.getFirstChild(), 7); range.call("collapse", true);
                selectPreviewRange(range); checklistTool().fire();
                assertEquals(4, taskList().getItems().size(), "The Read toolbar also creates the linked task immediately");
                var task = taskList().getItems().stream().filter(t -> t.text().equals("Read mode task details"))
                        .findFirst().orElseThrow();
                assertFalse(task.done()); assertTrue(task.linked());
                assertTrue(activeEditor().source().getText().contains("- [ ] Read mode task details <!-- task:" + task.sourceItemId() + " -->"));
                return task;
            });
            await(() -> fxUnchecked(() -> previewInput(firstReadToolbarTask.sourceItemId()) != null));
            fx(() -> {
                selectPreviewTaskText(firstReadToolbarTask.sourceItemId()); checklistTool().fire();
                assertEquals(3, taskList().getItems().size(), "The Read toolbar also removes an empty linked task without a dialog");
                assertNull(showingDialog());
                assertTrue(activeEditor().source().getText().contains("- Read mode task details"));
                assertFalse(activeEditor().source().getText().contains("<!-- task:" + firstReadToolbarTask.sourceItemId() + " -->"));
                return null;
            });
            await(() -> fxUnchecked(() -> previewInput(firstReadToolbarTask.sourceItemId()) == null
                    && previewListItem("Read mode task details") != null));
            var readToolbarTask = fx(() -> {
                selectPreviewContents(previewListItem("Read mode task details")); checklistTool().fire();
                assertEquals(4, taskList().getItems().size());
                var recreated = taskList().getItems().stream().filter(t -> t.text().equals("Read mode task details"))
                        .findFirst().orElseThrow();
                assertNotEquals(firstReadToolbarTask.id(), recreated.id());
                taskList().scrollTo(taskList().getItems().stream().filter(t -> t.id().equals(toolbarTask.id())).findFirst().orElseThrow());
                return recreated;
            });
            await(() -> fxUnchecked(() -> taskLabel("Write mode task details") != null));
            fx(() -> {
                taskLabel("Write mode task details").fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, MouseButton.PRIMARY, 1, false, false, false, false,
                        false, false, false, false, false, true, null));
                var taskTab = tabs().getSelectionModel().getSelectedItem();
                assertEquals(Workspace.taskTabId(toolbarTask.id()), taskTab.getUserData());
                assertTrue(taskTab.getStyleClass().contains("task-tab"), "Tasks open in distinct task tabs");
                assertEquals("Write mode task details", taskTab.getText());
                assertFalse(headerCollection().isDisabled(), "Collection browsing remains available from task tabs");
                assertEquals("Projects", headerCollection().getValue().name());
                assertNotNull(activeEditor().lookup(".format-toolbar #task-done"), "Task completion lives in the toolbar");
                return null;
            });
            verifyRename("Write task with details");
            fx(() -> {
                activeEditor().source().setText("# Task details\n\nKeep **the next action** and a useful description.\n");
                ((CheckBox) activeEditor().lookup("#task-done")).fire();
                menu("File", "Close tab").fire();
                var task = taskList().getItems().stream().filter(t -> t.id().equals(toolbarTask.id())).findFirst().orElseThrow();
                assertEquals("Write task with details", task.text()); assertTrue(task.done());
                assertTrue(task.markdown().contains("**the next action**"));
                taskList().getSelectionModel().select(task);
                taskList().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                assertEquals(Workspace.taskTabId(task.id()), tabs().getSelectionModel().getSelectedItem().getUserData());
                assertTrue(activeEditor().source().getText().contains("**the next action**"));
                assertTrue(((CheckBox) activeEditor().lookup("#task-done")).isSelected());
                selectTab(linkedTask.sourceNoteId());
                assertTrue(activeEditor().source().getText().contains("- [x] Write task with details"),
                        "Task editor title and completion synchronize with its source checkbox");
                assertEquals(4, taskList().getItems().size());
                return null;
            });
            var savedImage = verifyEmbeddedImages();
            verifyEditorFontSize();
            fx(() -> { menu("File", "Save now").fire(); return null; });
            await(() -> Files.exists(data.resolve("workspace.xml")) && fxUnchecked(() ->
                    stage.getScene().getRoot().lookupAll(".app-status .label").stream().anyMatch(n -> n instanceof Label l && l.getText().equals("All changes saved"))));
            await(() -> fxUnchecked(() -> !((ListView<?>) stage.getScene().lookup("#notes-list")).getItems().isEmpty()));
            verifyThemeColors();
            fx(() -> { saveScreenshot(stage.getScene(), Path.of("target/ui-edited.png")); stage.hide(); app.stop(); return null; });
            app = null;
            try (var store = new WorkspaceStore(data)) {
                var saved = store.load();
                assertEquals(2, saved.notes().size());
                assertEquals(2, saved.collections().size());
                var savedNote = saved.notes().stream().filter(n -> n.title().equals("Unicode — белешка")).findFirst().orElseThrow();
                assertTrue(savedNote.markdown().contains("**this idea**"));
                assertTrue(savedNote.markdown().contains("Unlinked text saved."));
                assertTrue(savedNote.markdown().contains("**Edited** in Read mode."));
                assertTrue(savedNote.markdown().contains(savedImage.dataUrl()),
                        "The embedded image travels inside the saved notebook");
                assertTrue(savedNote.markdown().contains(savedImage.title()),
                        "Image size and stacking order survive workspace storage");
                assertFalse(savedNote.markdown().contains("second image"),
                        "Deleting an image removes its Markdown from storage");
                assertTrue(savedNote.markdown().contains("- [x] Review the notebook <!-- task:" + linkedTask.sourceItemId() + " -->"));
                var project = saved.collections().stream().filter(c -> c.name().equals("Projects")).findFirst().orElseThrow();
                assertTrue(saved.notes().stream().anyMatch(n -> n.title().equals("Unicode — белешка") && n.collectionId().equals(project.id())));
                assertEquals(4, saved.tasks().size());
                assertTrue(saved.tasks().stream().noneMatch(task -> task.id().equals(deletedTaskId)),
                        "Deleting a task remains effective after saving");
                assertFalse(saved.openTabs().contains(Workspace.taskTabId(deletedTaskId)));
                assertTrue(saved.tasks().stream().noneMatch(task -> toolbarDeletedIds.contains(task.id())),
                        "Toolbar-deleted tasks stay deleted in the saved workspace");
                assertTrue(toolbarDeletedIds.stream().noneMatch(id -> saved.openTabs().contains(Workspace.taskTabId(id))));
                var independent = saved.tasks().stream().filter(task -> !task.linked()).findFirst().orElseThrow();
                assertEquals("Finish the notebook", independent.text()); assertTrue(independent.done());
                var savedLinked = saved.tasks().stream().filter(Workspace.Todo::linked).findFirst().orElseThrow();
                assertEquals(linkedTask.id(), savedLinked.id()); assertEquals("Review the notebook", savedLinked.text());
                assertEquals(savedNote.id(), savedLinked.sourceNoteId());
                assertEquals(linkedTask.sourceItemId(), savedLinked.sourceItemId()); assertTrue(savedLinked.done());
                var savedToolbar = saved.tasks().stream().filter(task -> task.id().equals(toolbarTask.id())).findFirst().orElseThrow();
                assertEquals("Write task with details", savedToolbar.text()); assertTrue(savedToolbar.done());
                assertTrue(savedToolbar.markdown().contains("**the next action**"));
                assertEquals(savedNote.id(), savedToolbar.sourceNoteId());
                assertEquals(toolbarTask.sourceItemId(), savedToolbar.sourceItemId());
                assertTrue(savedNote.markdown().contains("- [x] Write task with details <!-- task:" + toolbarTask.sourceItemId() + " -->"));
                assertTrue(saved.tasks().stream().anyMatch(task -> task.id().equals(readToolbarTask.id())
                        && task.linked() && !task.done() && task.text().equals("Read mode task details")));
                assertTrue(saved.sidebarRight());
                assertEquals(.30, saved.sidebarWidth(), .005, "The chosen sidebar width is saved");
                assertEquals(12, saved.sidebarTextSize(), .001, "The chosen sidebar text size is saved");
                assertEquals(20, saved.editorFontSize(), .001, "The writing and preview font preference is saved");
                assertEquals(3, saved.openTabs().size());
                assertTrue(saved.openTabs().contains(Workspace.taskTabId(toolbarTask.id())));
            }
            fx(() -> { app = new NotesApp(); app.init(); stage = new Stage(); app.start(stage); stage.setFullScreen(true); return null; });
            await(() -> fxUnchecked(() -> stage.getScene().getWidth() > 900));
            await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("**this idea**")));
            await(() -> fxUnchecked(() -> previewCheckbox() != null && previewCheckbox().getChecked()));
            await(() -> fxUnchecked(() -> previewImage(savedImage.id()) != null
                    && imageLoaded(savedImage.id())));
            fx(() -> {
                var restored = previewImage(savedImage.id());
                assertEquals(savedImage.title(), imageLayoutTitle(restored));
                assertEquals(savedImage.dataUrl(), restored.getAttribute("src"));
                assertFalse(activeEditor().source().getText().contains("base64,"),
                        "Reopened notes keep image bytes out of the Markdown view");
                assertTrue(activeEditor().source().getText().contains("(image:"));
                assertEquals(Integer.toString(savedImage.width()), restored.getAttribute("data-image-width"));
                assertEquals(Integer.toString(savedImage.height()), restored.getAttribute("data-image-height"));
                assertEquals(1, activePreview().getEngine().getDocument().getElementsByTagName("img").getLength());
                return null;
            });
            awaitSidebarSize(.30, 12);
            fx(() -> {
                stage.getScene().getRoot().applyCss();
                assertEquals(20, editorFontSize().getValue());
                assertEquals(20, activeEditor().source().getFont().getSize(), .001);
                assertEquals(20.0 / 14, activePreview().getFontScale(), .0001,
                        "The main text size survives an application restart");
                assertTrue(activeEditor().source().getText().contains("**Edited** in Read mode."));
                assertTrue(activeEditor().source().getText().contains("Unlinked text saved."));
                assertEquals(linkedTask.sourceItemId(), ((Element) previewCheckbox()).getAttribute("data-task-id"));
                assertEquals(4, taskList().getItems().size());
                assertTrue(taskList().getItems().stream().noneMatch(task -> task.id().equals(deletedTaskId)),
                        "A deleted task does not reappear when reopening the workspace");
                assertTrue(taskList().getItems().stream().noneMatch(task -> toolbarDeletedIds.contains(task.id())),
                        "Toolbar-deleted tasks do not reappear after reopening");
                assertTrue(taskList().getItems().stream().anyMatch(task -> task.id().equals(linkedTask.id())
                        && task.sourceItemId().equals(linkedTask.sourceItemId()) && task.done()));
                assertEquals(3, tabs().getTabs().size());
                selectTab(Workspace.taskTabId(toolbarTask.id()));
                assertTrue(tabs().getSelectionModel().getSelectedItem().getStyleClass().contains("task-tab"));
                assertEquals("Write task with details", tabs().getSelectionModel().getSelectedItem().getText());
                assertEquals(20, editorFontSize().getValue(), "Task details use the saved main text size too");
                assertTrue(((CheckBox) activeEditor().lookup("#task-done")).isSelected());
                assertTrue(activeEditor().source().getText().contains("**the next action**"), "Task details survive an application restart");
                selectTab(linkedTask.sourceNoteId());
                menu("File", "Close tab").fire();
                var list = (ListView<?>) stage.getScene().lookup("#notes-list"); list.getSelectionModel().selectFirst();
                list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                assertTrue(activeEditor().source().getText().contains("**this idea**"));
                return null;
            });
            fx(() -> {
                editorFontSize().getValueFactory().setValue(14);
                for (int i = 0; i < 6; i++) menu("View", "Larger text").fire();
                assertEquals(1.6, activePreview().getFontScale(), .0001);
                menu("View", "Reset text size").fire();
                assertEquals(1, activePreview().getFontScale(), .0001);
                stage.setFullScreen(false); stage.setWidth(420); stage.setHeight(460);
                return null;
            });
            await(() -> fxUnchecked(() -> !stage.isFullScreen()));
            // Give the compositor a chance to honor (or override) the requested window size.
            Thread.sleep(400);
            fx(() -> {
                System.out.printf(java.util.Locale.ROOT, "Responsive smoke test: requested 420x460; observed scene %.0fx%.0f%n",
                        stage.getScene().getWidth(), stage.getScene().getHeight());
                // A tiling WM can override Stage.setWidth; zoom still exercises the compact breakpoint.
                if (stage.getScene().getWidth() >= 850)
                    for (int i = 0; i < 6; i++) menu("View", "Larger text").fire();
                stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                return null;
            });
            Thread.sleep(250);
            fx(() -> {
                var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
                var editor = activeEditor();
                for (var button : editor.lookupAll(".format-button"))
                    assertWithinWidth(button, editor, "Formatting button");
                for (var button : editor.lookupAll(".mode-button"))
                    assertWithinWidth(button, editor, "Editor mode button");
                assertWithinWidth(root.lookup("#search-notes"), root, "Search field");
                assertWithinWidth(headerCollection(), root, "Collection dropdown");
                assertWithinWidth(root.lookup("#new-note"), root, "New note button");
                assertHeaderSearchLayout();
                var body = (SplitPane) ((BorderPane) root).getCenter();
                boolean compact = body.getItems().size() == 1;
                System.out.printf("Responsive smoke test: compact navigation=%s, preview scale=%.1f%n", compact, activePreview().getFontScale());
                menu("View", "Toggle notes & tasks").fire();
                if (body.getItems().stream().noneMatch(node -> node.getStyleClass().contains("sidebar")))
                    menu("View", "Toggle notes & tasks").fire();
                assertTrue(body.getItems().stream().anyMatch(node -> node.getStyleClass().contains("sidebar")),
                        "Notes and tasks remain reachable at the observed window size");
                assertNotNull(root.lookup("#tasks-list"));
                assertNotNull(root.lookup("#notes-list"));
                assertNull(root.lookup("#collections-list"));
                menu("View", "Toggle notes & tasks").fire();
                root.applyCss(); root.layout();
                saveScreenshot(stage.getScene(), Path.of("target/ui-compact.png"));
                return null;
            });
        } finally {
            fx(() -> { if (stage != null) stage.hide(); if (app != null) app.stop(); return null; });
            System.clearProperty("notes.dataDir"); Platform.exit();
        }
    }
    private NoteEditor activeEditor() {
        return (NoteEditor) ((TabPane) stage.getScene().lookup("#note-tabs")).getSelectionModel().getSelectedItem().getContent();
    }
    @SuppressWarnings("unchecked")
    private ComboBox<Workspace.Collection> headerCollection() {
        return (ComboBox<Workspace.Collection>) stage.getScene().lookup("#note-collection");
    }
    @SuppressWarnings("unchecked")
    private Spinner<Integer> editorFontSize() {
        return (Spinner<Integer>) activeEditor().lookup("#editor-font-size");
    }
    private void verifyHeaderLayout() throws Exception {
        fx(() -> {
            var root = stage.getScene().getRoot();
            assertNull(root.lookup(".editor-heading"), "The editor heading is completely removed");
            assertNull(root.lookup("#note-title"));
            assertNull(root.lookup("#task-title"));
            assertSame(headerCollection(), root.lookup(".app-header").lookup("#note-collection"),
                    "The collection dropdown belongs to the application header");
            assertHeaderSearchLayout();
            assertFalse(headerCollection().isDisabled());
            var activeCollection = headerCollection().getValue();
            assertNotNull(activeCollection);
            var selected = (String) tabs().getSelectionModel().getSelectedItem().getUserData();
            menu("File", "Close tab").fire();
            assertTrue(tabs().getTabs().isEmpty());
            assertFalse(headerCollection().isDisabled(), "Collection browsing remains available without an open note");
            assertEquals(activeCollection, headerCollection().getValue());
            var notes = (ListView<?>) root.lookup("#notes-list");
            notes.getSelectionModel().selectFirst();
            notes.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(selected, tabs().getSelectionModel().getSelectedItem().getUserData());
            assertEquals(activeCollection, headerCollection().getValue());
            return null;
        });
    }
    private void assertHeaderSearchLayout() {
        var header = (javafx.scene.layout.HBox) stage.getScene().lookup(".app-header");
        var search = (TextField) header.lookup("#search-notes");
        assertEquals(header.getChildren().indexOf(headerCollection()) + 1, header.getChildren().indexOf(search),
                "The collection dropdown sits immediately beside search");
        var collectionBounds = headerCollection().localToScene(headerCollection().getBoundsInLocal());
        var searchBounds = search.localToScene(search.getBoundsInLocal());
        assertEquals(header.getSpacing(), searchBounds.getMinX() - collectionBounds.getMaxX(), 2,
                "The dropdown and search have only the normal header gap between them");
        assertTrue(search.getWidth() >= 50 && search.getWidth() <= 140,
                "Search uses half its former 100–280px width range: " + search.getWidth());
    }
    private void verifyCollectionMenus() throws Exception {
        fx(() -> {
            var notes = (ListView<?>) stage.getScene().lookup("#notes-list");
            selectCollectionFilter("Inbox");
            assertEquals(1, notes.getItems().size());
            var inbox = headerCollection().getItems().stream().filter(c -> c.name().equals("Inbox")).findFirst().orElseThrow();
            assertEquals(inbox.id(), ((Workspace.Note) notes.getItems().getFirst()).collectionId());
            assertEquals("Inbox", headerCollection().getValue().name(),
                    "The header follows the active collection");
            selectCollectionFilter("Projects");
            assertEquals(1, notes.getItems().size());
            assertEquals(tabs().getSelectionModel().getSelectedItem().getUserData(),
                    ((Workspace.Note) notes.getItems().getFirst()).id());
            selectCollectionFilter("All notes");
            assertEquals(2, notes.getItems().size(), "All notes remains available without the collections sidebar");
            return null;
        });
        clickDialogFromMenu("Collections", "New collection", "Temporary");
        fx(() -> {
            assertTrue(((ListView<?>) stage.getScene().lookup("#notes-list")).getItems().isEmpty());
            assertEquals("Temporary", headerCollection().getValue().name());
            return null;
        });
        clickDialogAction("Rename collection", () -> collectionAction("Rename collection…", "Temporary").fire(),
                "Renamed temporary", ButtonType.OK, pane -> {});
        fx(() -> {
            assertTrue(((RadioMenuItem) menu("Collections", "Renamed temporary")).isSelected(),
                    "Renaming retains the selected collection filter");
            assertTrue(headerCollection().getItems().stream().anyMatch(c -> c.name().equals("Renamed temporary")));
            return null;
        });
        clickDialogAction("Delete collection", () -> collectionAction("Delete collection…", "Renamed temporary").fire(),
                null, ButtonType.OK, pane -> {});
        fx(() -> {
            assertEquals(3, headerCollection().getItems().size(), "The selector also contains All notes");
            assertEquals("Inbox", headerCollection().getValue().name());
            selectCollectionFilter("Projects");
            return null;
        });
    }
    private void selectCollectionFilter(String name) {
        var item = (RadioMenuItem) menu("Collections", name);
        item.setSelected(true); item.fire();
    }
    private MenuItem collectionAction(String action, String collection) {
        return ((Menu) menu("Collections", action)).getItems().stream()
                .filter(item -> item.getText().equals(collection)).findFirst().orElseThrow();
    }
    private void verifyRename(String name) throws Exception {
        var target = fx(() -> tabs().getSelectionModel().getSelectedItem());
        var original = fx(target::getText);
        var mode = fx(() -> activeEditor().lookupAll(".mode-button").stream()
                .map(ToggleButton.class::cast).filter(ToggleButton::isSelected)
                .map(ToggleButton::getText).findFirst().orElseThrow());
        var other = fx(() -> tabs().getTabs().stream().filter(tab -> tab != target).findFirst().orElseThrow());
        var otherTitle = fx(other::getText);
        fx(() -> { tabs().getSelectionModel().select(other); return null; });
        mouseClick(fx(() -> tabHeader(target).lookup(".tab-label")));
        fx(() -> {
            assertSame(target, tabs().getSelectionModel().getSelectedItem(), "A single click selects the document");
            assertNull(showingDialog(), "A single tab click does not rename");
            activeEditor().setMode("Write");
            return null;
        });
        mouseDoubleClick(fx(() -> activeEditor().source()));
        fx(() -> {
            assertNull(showingDialog(), "Double-clicking document text does not rename the tab");
            activeEditor().setMode(mode);
            return null;
        });
        clickDialogFromMenu("File", "Rename…", "Cancelled rename", ButtonType.CANCEL);
        assertEquals(original, fx(target::getText), "Cancel preserves the document title");
        fx(() -> { tabs().getSelectionModel().select(other); return null; });
        clickTabRename(target, "Cancelled tab rename", ButtonType.CANCEL, pane -> {
            assertSame(target, tabs().getSelectionModel().getSelectedItem(),
                    "Double-clicking an inactive tab renames that document");
            assertEquals(original, ((TextField) pane.lookup(".text-field")).getText(),
                    "The dialog starts with the clicked document's title");
        });
        assertEquals(original, fx(target::getText), "Cancelling a double-click rename preserves the title");
        clickTabRename(target, name, ButtonType.OK, pane -> {});
        assertEquals(name, fx(target::getText),
                "Rename updates the document's visible tab title");
        assertEquals(otherTitle, fx(other::getText), "Renaming leaves the other tab's title unchanged");
        assertTrue(fx(() -> stage.getTitle().startsWith(name)), "Rename updates the window title");
    }
    private javafx.scene.Node tabHeader(Tab tab) {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        return tabs().lookupAll(".tab").stream().filter(node -> tab.getId().equals(node.getId()))
                .findFirst().orElseThrow();
    }
    private void clickTabRename(Tab tab, String input, ButtonType response,
                                Consumer<DialogPane> beforeResponse) throws Exception {
        boolean fullScreen = fx(() -> stage.isFullScreen());
        var label = fx(() -> tabHeader(tab).lookup(".tab-label"));
        var observed = new StringBuilder();
        long started = System.nanoTime();
        javafx.event.EventHandler<MouseEvent> observe = event -> {
            if (event.getEventType() != MouseEvent.MOUSE_PRESSED
                    && event.getEventType() != MouseEvent.MOUSE_RELEASED
                    && event.getEventType() != MouseEvent.MOUSE_CLICKED) return;
            observed.append("\n").append((System.nanoTime() - started) / 1_000_000).append("ms ")
                    .append(event.getEventType()).append(" ").append(event.getButton())
                    .append(" count=").append(event.getClickCount()).append(" at=")
                    .append(event.getScreenX()).append(",").append(event.getScreenY())
                    .append(" expectedBounds=").append(label.localToScreen(label.getBoundsInLocal()))
                    .append(" target=");
            for (var node = event.getTarget() instanceof javafx.scene.Node n ? n : null;
                 node != null; node = node.getParent())
                observed.append(node.getClass().getSimpleName()).append("#").append(node.getId())
                        .append("[").append(node.getStyleClass()).append("] -> ");
        };
        fx(() -> {
            observed.append("Expected tab=").append(tab.getId()).append(" initialBounds=")
                    .append(label.localToScreen(label.getBoundsInLocal()));
            stage.getScene().addEventFilter(MouseEvent.ANY, observe);
            return null;
        });
        try {
            mouseDoubleClick(label);
            fx(() -> {
                observed.append("\nAfter gesture: selected=")
                        .append(tabs().getSelectionModel().getSelectedItem().getId())
                        .append(" bounds=").append(label.localToScreen(label.getBoundsInLocal()))
                        .append(" fullscreen=").append(stage.isFullScreen())
                        .append(" dialog=").append(showingDialog());
                return null;
            });
            respondToDialog("Double-click " + tab.getText(), input, response, beforeResponse, fullScreen);
        } catch (Exception | AssertionError failure) {
            throw new AssertionError("Tab rename mouse gesture failed: " + fx(observed::toString), failure);
        } finally {
            fx(() -> { stage.getScene().removeEventFilter(MouseEvent.ANY, observe); return null; });
        }
    }
    private record SavedImage(String id, String dataUrl, String title, int width, int height) {}

    private SavedImage verifyEmbeddedImages() throws Exception {
        var fixture = data.resolve("first image.png");
        Files.write(fixture, java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="));
        var imported = EmbeddedImage.read(fixture);
        fx(() -> {
            assertNotNull(menu("Images", "Insert image…"));
            activeEditor().setMode("Write");
            activeEditor().source().appendText("\n\n- Ordinary bullet stays visible.\n");
            activeEditor().insertImage(new EmbeddedImage(imported.name(), imported.dataUrl(), 120, 120));
            return null;
        });
        await(() -> fxUnchecked(() -> firstPreviewImage() != null && imageLoaded(firstPreviewImage().getAttribute("data-image-id"))));
        var firstId = fx(() -> {
            assertTrue(activePreview().isVisible(), "Inserting an image reveals its preview");
            var image = firstPreviewImage();
            String id = image.getAttribute("data-image-id");
            assertNotNull(id, "An inserted image has its managed image identity");
            assertFalse(activeEditor().source().getText().contains("base64,"));
            assertFalse(activeEditor().source().getText().contains("javafxnotes-image:"));
            assertTrue(activeEditor().source().getText().contains("(image:"));
            assertTrue(activeEditor().markdown().contains(imported.dataUrl()));
            activeEditor().selectImage(id);
            assertTrue(activeEditor().hasSelectedImage(), "The inserted image can be selected");
            assertFalse(activeEditor().isSelectedImageFloating(), "New images start inline with the text");
            assertEquals("false", image.getAttribute("contenteditable"));
            menu("Images", "Floating").fire();
            assertTrue(activeEditor().isSelectedImageFloating());
            menu("Images", "Inline with text").fire();
            assertFalse(activeEditor().isSelectedImageFloating(), "The image can return to text flow");
            assertEquals("inline", image.getAttribute("data-image-mode"));
            assertEquals("none", activePreview().getEngine().executeScript(
                    "getComputedStyle(document.querySelector('li.task-list-item')).listStyleType"),
                    "A task displays its checkbox without an unordered-list bullet");
            assertTaskCheckboxAlignment(activePreview());
            assertNotEquals("none", activePreview().getEngine().executeScript(
                    "getComputedStyle(Array.from(document.querySelectorAll('li')).find(function(li){return li.textContent.indexOf('Ordinary bullet stays visible.')>=0;})).listStyleType"),
                    "An ordinary list keeps its bullet");
            return id;
        });
        verifyImageOverlayAfterTextSize(firstId);
        fx(() -> {
            activeEditor().setMode("Read");
            var document = activePreview().getEngine().getDocument();
            var code = document.getElementsByTagName("code").item(0);
            activePreview().requestFocus();
            var range = (JSObject) ((JSObject) document).call("createRange");
            range.call("setStart", code.getFirstChild(), 3);
            range.call("collapse", true);
            selectPreviewRange(range);
            activeEditor().insertImage(new EmbeddedImage("second image.png", imported.dataUrl(), 120, 120));
            assertEquals("plain code\n", code.getTextContent(),
                    "Inserting an image while the caret is in code preserves that code");
            assertTrue(activeEditor().source().getText().contains("```\nplain code\n```"));
            return null;
        });
        await(() -> fxUnchecked(() -> activePreview().getEngine().getDocument().getElementsByTagName("img").getLength() == 2));
        var secondId = fx(() -> {
            var images = activePreview().getEngine().getDocument().getElementsByTagName("img");
            String id = ((Element) images.item(0)).getAttribute("data-image-id");
            if (id.equals(firstId)) id = ((Element) images.item(1)).getAttribute("data-image-id");
            activeEditor().setMode("Read");
            activeEditor().selectImage(firstId);
            menu("Images", "Floating").fire();
            assertTrue(activeEditor().isSelectedImageFloating());
            return id;
        });
        await(() -> fxUnchecked(() -> previewImage(firstId) != null));
        resizePreviewImage(firstId);
        movePreviewImage(firstId);
        fx(() -> {
            activeEditor().selectImage(firstId);
            menu("Images", "Send to back").fire();
            assertTrue(Integer.parseInt(previewImage(firstId).getAttribute("data-image-z")) < 0,
                    "Sending an image behind text gives it a layer below the text");
            menu("Images", "Bring to front").fire();
            assertTrue(Integer.parseInt(previewImage(firstId).getAttribute("data-image-z")) > 0,
                    "Bringing an image forward restores a layer above the text");
            assertTrue(Integer.parseInt(previewImage(firstId).getAttribute("data-image-z"))
                            > Integer.parseInt(previewImage(secondId).getAttribute("data-image-z")),
                    "The front image also appears above remaining inline images");
            activeEditor().selectImage(secondId);
            menu("Images", "Floating").fire();
            menu("Images", "Bring to front").fire();
            return null;
        });
        deletePreviewImageFromContextMenu(secondId);
        await(() -> fxUnchecked(() -> previewImage(secondId) == null
                && !activeEditor().markdown().contains(secondId)));
        fx(() -> {
            activeEditor().selectImage(firstId);
            var document = activePreview().getEngine().getDocument();
            var paragraph = document.getElementsByTagName("p").item(0);
            var range = (JSObject) ((JSObject) document).call("createRange");
            range.call("selectNodeContents", paragraph);
            range.call("collapse", false);
            selectPreviewRange(range);
            assertEquals(Boolean.TRUE, ((JSObject) document).call(
                    "execCommand", "insertText", false, " Image annotation."));
            assertFalse(activeEditor().hasSelectedImage(),
                    "Editing note text clears the image selection so text deletion stays safe");
            return null;
        });
        await(() -> fxUnchecked(() -> activeEditor().source().getText().contains("Image annotation.")));
        return fx(() -> {
            activeEditor().flushPendingEdits();
            var image = previewImage(firstId);
            assertNotNull(image, "Deleting the second image preserves the first");
            assertTrue(activeEditor().source().getText().contains("**this idea**"));
            assertTrue(activeEditor().source().getText().contains("<!-- task:"),
                    "Image interactions preserve linked tasks");
            String title = imageLayoutTitle(image);
            int width = Integer.parseInt(image.getAttribute("data-image-width"));
            int height = Integer.parseInt(image.getAttribute("data-image-height"));
            assertTrue(width > 120 && height > 120, "Dragging the image handle saves a larger image");
            assertTrue(activeEditor().markdown().contains(title));
            assertFalse(activeEditor().source().getText().contains("base64,"));
            saveScreenshot(stage.getScene(), Path.of("target/ui-images.png"));
            System.out.println("Images: inline/floating modes, loading, resize/move, layers, code insertion and context deletion passed");
            return new SavedImage(firstId, imported.dataUrl(), title, width, height);
        });
    }

    private Element firstPreviewImage() {
        var document = activePreview().getEngine().getDocument();
        return document == null ? null : (Element) document.getElementsByTagName("img").item(0);
    }

    static void assertTaskCheckboxAlignment(WebView preview) {
        var alignment = (JSObject) preview.getEngine().executeScript("""
                (function() {
                    var rows = Array.from(document.querySelectorAll('li.task-list-item')).map(function(li) {
                        var paragraph = Array.from(li.children).find(function(child) { return child.tagName === 'P'; });
                        var checkbox = li.querySelector('input[type=checkbox]');
                        if (!paragraph || !checkbox) return null;
                        var text = paragraph.getBoundingClientRect(), box = checkbox.getBoundingClientRect();
                        return box.top < text.bottom && box.bottom > text.top
                            && text.left >= box.right - 1 && text.left - box.right < 12;
                    }).filter(function(value) { return value !== null; });
                    return {count: rows.length, aligned: rows.every(function(value) { return value; })};
                })()
                """);
        assertTrue(((Number) alignment.getMember("count")).intValue() > 0,
                "Exercise checklist rows containing paragraph elements");
        assertEquals(Boolean.TRUE, alignment.getMember("aligned"),
                "Each checklist checkbox appears beside its title on the same row");
    }

    private Element previewImage(String id) {
        var document = activePreview().getEngine().getDocument();
        if (document == null) return null;
        var images = document.getElementsByTagName("img");
        for (int i = 0; i < images.getLength(); i++) {
            var image = (Element) images.item(i);
            if (id.equals(image.getAttribute("data-image-id"))) return image;
        }
        return null;
    }

    private boolean imageLoaded(String id) {
        return Boolean.TRUE.equals(activePreview().getEngine().executeScript(
                "(function(){var image=document.querySelector('[data-image-id=\"" + id
                        + "\"]');return !!image && image.complete && image.naturalWidth>0;})()"));
    }

    private String imageLayoutTitle(Element image) {
        return new ImageMarkup.Layout(image.getAttribute("data-image-id"),
                Integer.parseInt(image.getAttribute("data-image-x")),
                Integer.parseInt(image.getAttribute("data-image-y")),
                Integer.parseInt(image.getAttribute("data-image-width")),
                Integer.parseInt(image.getAttribute("data-image-height")),
                Integer.parseInt(image.getAttribute("data-image-z")),
                "floating".equals(image.getAttribute("data-image-mode"))).title();
    }

    private void resizePreviewImage(String id) throws Exception {
        fx(() -> {
            activeEditor().selectImage(id);
            ((JSObject) previewImage(id)).call("scrollIntoView", true);
            activePreview().getEngine().executeScript("window.scrollBy(0,-80)");
            return null;
        });
        Thread.sleep(200);
        var handle = fx(() -> previewPoint("[data-image-handle=resize]"));
        fx(() -> { new Robot().mouseMove(handle); return null; });
        Thread.sleep(120);
        try {
            fx(() -> { new Robot().mousePress(MouseButton.PRIMARY); return null; });
            for (int step = 1; step <= 8; step++) {
                double x = handle.getX() + step * 6;
                double y = handle.getY() + step * 6;
                fx(() -> { new Robot().mouseMove(x, y); return null; });
                Thread.sleep(40);
            }
        } finally {
            fx(() -> { new Robot().mouseRelease(MouseButton.PRIMARY); return null; });
        }
        await(() -> fxUnchecked(() -> Integer.parseInt(previewImage(id).getAttribute("data-image-width")) > 120
                && Integer.parseInt(previewImage(id).getAttribute("data-image-height")) > 120));
    }

    private void verifyImageOverlayAfterTextSize(String id) throws Exception {
        int originalSize = fx(() -> editorFontSize().getValue());
        fx(() -> {
            activeEditor().selectImage(id);
            editorFontSize().getValueFactory().setValue(20);
            return null;
        });
        await(() -> fxUnchecked(() -> imageOutlineAligned(id)));
        fx(() -> {
            assertTrue(activeEditor().hasSelectedImage(), "Resizing text preserves the selected image");
            editorFontSize().getValueFactory().setValue(originalSize);
            return null;
        });
        await(() -> fxUnchecked(() -> imageOutlineAligned(id)));
    }

    private boolean imageOutlineAligned(String id) {
        return Boolean.TRUE.equals(activePreview().getEngine().executeScript(
                "(function(){var image=document.querySelector('img[data-image-id=\"" + id
                        + "\"]'), outline=document.querySelector('.note-image-selection');"
                        + "if(!image || !outline || getComputedStyle(outline).display==='none')return false;"
                        + "var a=image.getBoundingClientRect(),b=outline.getBoundingClientRect();"
                        + "return Math.abs(a.left-b.left)<1 && Math.abs(a.top-b.top)<1"
                        + " && Math.abs(a.width-b.width)<1 && Math.abs(a.height-b.height)<1;})()"));
    }

    private void movePreviewImage(String id) throws Exception {
        var before = fx(() -> {
            activeEditor().selectImage(id);
            ((JSObject) previewImage(id)).call("scrollIntoView", true);
            activePreview().getEngine().executeScript("window.scrollBy(0,-80)");
            return new javafx.geometry.Point2D(
                    Integer.parseInt(previewImage(id).getAttribute("data-image-x")),
                    Integer.parseInt(previewImage(id).getAttribute("data-image-y")));
        });
        Thread.sleep(200);
        var handle = fx(() -> previewPoint("[data-image-handle=move]"));
        fx(() -> { new Robot().mouseMove(handle); return null; });
        Thread.sleep(120);
        try {
            fx(() -> { new Robot().mousePress(MouseButton.PRIMARY); return null; });
            for (int step = 1; step <= 8; step++) {
                double x = handle.getX() + step * 6;
                double y = handle.getY() + step * 3;
                fx(() -> { new Robot().mouseMove(x, y); return null; });
                Thread.sleep(40);
            }
        } finally {
            fx(() -> { new Robot().mouseRelease(MouseButton.PRIMARY); return null; });
        }
        await(() -> fxUnchecked(() -> Integer.parseInt(previewImage(id).getAttribute("data-image-x")) > before.getX()
                && Integer.parseInt(previewImage(id).getAttribute("data-image-y")) > before.getY()));
    }

    private void deletePreviewImageFromContextMenu(String id) throws Exception {
        fx(() -> {
            ((JSObject) previewImage(id)).call("scrollIntoView", true);
            activePreview().getEngine().executeScript("window.scrollBy(0,-80)");
            return null;
        });
        Thread.sleep(200);
        fx(() -> {
            new Robot().mouseMove(previewPoint("img[data-image-id=\"" + id + "\"]"));
            return null;
        });
        Thread.sleep(100);
        fx(() -> { new Robot().mousePress(MouseButton.SECONDARY); return null; });
        fx(() -> { new Robot().mouseRelease(MouseButton.SECONDARY); return null; });
        await(() -> fxUnchecked(() -> imageContextMenu() != null));
        var context = fx(this::imageContextMenu);
        try {
            var delete = fx(() -> {
                assertTrue(context.getItems().stream().anyMatch(item -> "Bring to front".equals(item.getText())));
                assertTrue(context.getItems().stream().anyMatch(item -> "Send to back".equals(item.getText())));
                return context.getScene().getRoot().lookupAll(".label").stream()
                        .filter(node -> node instanceof Label label && label.getText().equals("Delete image"))
                        .findFirst().orElseThrow();
            });
            mouseClick(delete);
            await(() -> fxUnchecked(() -> !context.isShowing()));
        } finally {
            fx(() -> { context.hide(); return null; });
        }
    }

    private ContextMenu imageContextMenu() {
        return Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu context && context.isShowing()
                        && context.getItems().stream().anyMatch(item -> "Delete image".equals(item.getText())))
                .map(ContextMenu.class::cast).findFirst().orElse(null);
    }

    private javafx.geometry.Point2D previewPoint(String selector) {
        var document = (JSObject) activePreview().getEngine().getDocument();
        var element = (JSObject) document.call("querySelector", selector);
        assertNotNull(element, "Preview mouse target exists: " + selector);
        var bounds = (JSObject) element.call("getBoundingClientRect");
        double x = ((Number) bounds.getMember("left")).doubleValue()
                + ((Number) bounds.getMember("width")).doubleValue() / 2;
        double y = ((Number) bounds.getMember("top")).doubleValue()
                + ((Number) bounds.getMember("height")).doubleValue() / 2;
        assertTrue(x >= 0 && x < activePreview().getWidth(), "Image handle is visible horizontally");
        assertTrue(y >= 0 && y < activePreview().getHeight(), "Image handle is visible vertically");
        return activePreview().localToScreen(x, y);
    }

    private void verifyEditorFontSize() throws Exception {
        fx(() -> {
            var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
            assertEquals(14, editorFontSize().getValue());
            assertTrue(editorFontSize().isEditable(), "The main text size can be typed as well as stepped");
            assertEquals(14, activeEditor().source().getFont().getSize(), .001);
            assertEquals(1, activePreview().getFontScale(), .0001);
            double toolFont = checklistTool().getFont().getSize();
            double sidebarFont = ((Label) root.lookup(".sidebar .note-name")).getFont().getSize();
            double menuFont = ((Label) root.lookup(".menu-bar .label")).getFont().getSize();
            var markdown = new java.util.HashMap<String, String>();
            for (var tab : tabs().getTabs()) {
                ((NoteEditor) tab.getContent()).flushPendingEdits();
                markdown.put((String) tab.getUserData(), ((NoteEditor) tab.getContent()).source().getText());
            }
            var selected = (String) tabs().getSelectionModel().getSelectedItem().getUserData();
            editorFontSize().getEditor().setText("20");
            editorFontSize().getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(20, editorFontSize().getValue(), "A typed font size is committed by Enter");
            editorFontSize().getEditor().setText("invalid");
            editorFontSize().getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(20, editorFontSize().getValue(), "Invalid input preserves the last usable text size");
            assertEquals("20", editorFontSize().getEditor().getText());
            for (var tab : tabs().getTabs()) {
                tabs().getSelectionModel().select(tab);
                activeEditor().setMode("Split");
                root.applyCss(); root.layout();
                assertEquals(20, editorFontSize().getValue(), "Every open note and task reflects the chosen text size");
                assertEquals(20, activeEditor().source().getFont().getSize(), .001);
                assertEquals(20.0 / 14, activePreview().getFontScale(), .0001);
                assertEquals(markdown.get((String) tab.getUserData()), activeEditor().source().getText(),
                        "Changing display size never rewrites Markdown");
            }
            selectTab(selected); root.applyCss(); root.layout();
            assertEquals(toolFont, checklistTool().getFont().getSize(), .001,
                    "Main text size leaves toolbar text unchanged");
            assertEquals(sidebarFont, ((Label) root.lookup(".sidebar .note-name")).getFont().getSize(), .001,
                    "Main text size leaves sidebar text unchanged");
            assertEquals(menuFont, ((Label) root.lookup(".menu-bar .label")).getFont().getSize(), .001,
                    "Main text size leaves menu text unchanged");
            return null;
        });
        System.out.println("Main text size: all note/task editors update together; toolbar, sidebar, menu and Markdown stay unchanged");
    }
    private void verifyThemeColors() throws Exception {
        fx(() -> {
            var themeField = NotesApp.class.getDeclaredField("theme"); themeField.setAccessible(true);
            var theme = (Theme) themeField.get(app);
            String checkboxColor = (String) activePreview().getEngine().executeScript(
                    "getComputedStyle(document.querySelector('input[type=checkbox]:checked')).backgroundColor");
            assertEquals(javafx.scene.paint.Color.web(theme.taskColor()), javafx.scene.paint.Color.web(checkboxColor),
                    "The preview checkbox uses the task color even when WebView lacks accent-color support");
            return null;
        });
        // Exercise the theme-polling callback without changing the user's desktop theme files.
        fx(() -> {
            var themeField = NotesApp.class.getDeclaredField("theme"); themeField.setAccessible(true);
            var original = (Theme) themeField.get(app);
            var applyTheme = NotesApp.class.getDeclaredMethod("applyTheme", Theme.class); applyTheme.setAccessible(true);
            try {
                for (String palette : new String[]{
                        "background = '#fafafa'\ndark_background = '#ececec'\naccent = '#007744'\nred = '#be2451'",
                        "background = '#16161e'\ndark_background = '#20222b'\naccent = '#f0bc65'\ncyan = '#6cd8e0'"}) {
                    var theme = Theme.parse(palette, original.font());
                    applyTheme.invoke(app, theme);
                    var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
                    var note = javafx.scene.paint.Color.web(theme.noteColor());
                    var task = javafx.scene.paint.Color.web(theme.taskColor());
                    assertNotEquals(note, task);
                    assertEquals(note, ((Label) root.lookup(".sidebar .note-name")).getTextFill());
                    assertEquals(task, ((Label) root.lookup(".sidebar .task-text")).getTextFill());
                    assertEquals(note, ((javafx.scene.shape.SVGPath) root.lookup(".note-icon")).getStroke());
                    assertEquals(task, ((javafx.scene.shape.SVGPath) root.lookup(".task-icon")).getStroke());
                    var accent = javafx.scene.paint.Color.web(theme.accent());
                    assertTrue(headerCollection().getBackground().getFills().stream()
                                    .anyMatch(fill -> accent.equals(fill.getFill())),
                            "The collection dropdown keeps a filled accent background after a theme change");
                }
            } finally {
                applyTheme.invoke(app, original);
                stage.getScene().getRoot().applyCss();
            }
            return null;
        });
        System.out.println("Theme colors: open note/task labels and icons follow light and dark palette changes");
    }
    private String verifyTaskDeletionAndCreateIndependentTask() throws Exception {
        var originalNoteId = fx(() -> (String) tabs().getSelectionModel().getSelectedItem().getUserData());
        var parent = fx(() -> {
            ((Button) stage.getScene().lookup("#new-note")).fire();
            activeEditor().setMode("Write");
            activeEditor().rename("Temporary task source");
            activeEditor().source().setText("Task to delete");
            activeEditor().source().positionCaret(5);
            activeEditor().source().requestFocus();
            checklistTool().fire();
            var task = taskList().getItems().getFirst();
            assertEquals("Task to delete", task.text());
            assertTrue(task.linked());
            taskList().getSelectionModel().select(task);
            taskList().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(Workspace.taskTabId(task.id()), tabs().getSelectionModel().getSelectedItem().getUserData());
            activeEditor().setMode("Write");
            activeEditor().source().setText("Finish the notebook");
            activeEditor().source().positionCaret(5);
            activeEditor().source().requestFocus();
            checklistTool().fire();
            activeEditor().source().appendText("\n\nDetails still pending when deletion is requested.\n");
            return task;
        });
        clickDialogFromMenu("File", "Delete…", null, ButtonType.CANCEL);
        var childId = fx(() -> {
            assertEquals(Workspace.taskTabId(parent.id()), tabs().getSelectionModel().getSelectedItem().getUserData(),
                    "Cancel keeps the task tab open");
            var retained = taskList().getItems().stream().filter(task -> task.id().equals(parent.id())).findFirst().orElseThrow();
            assertTrue(retained.markdown().contains("Details still pending"), "Cancel preserves pending task details");
            assertEquals(2, taskList().getItems().size());
            var child = taskList().getItems().stream().filter(task -> !task.id().equals(parent.id())).findFirst().orElseThrow();
            assertEquals("Finish the notebook", child.text());
            assertEquals(Workspace.taskTabId(parent.id()), child.sourceNoteId());
            assertTrue(child.linked(), "Cancel preserves the child task link");
            return child.id();
        });
        clickDialogFromMenu("File", "Delete…", null);
        fx(() -> {
            assertEquals(1, taskList().getItems().size());
            assertTrue(tabs().getTabs().stream().noneMatch(tab -> Workspace.taskTabId(parent.id()).equals(tab.getUserData())),
                    "Deleting a task closes its editor tab");
            var child = taskList().getItems().getFirst();
            assertEquals(childId, child.id());
            assertFalse(child.linked(), "Tasks sourced from deleted task details survive as independent tasks");
            selectTab(parent.sourceNoteId());
            assertTrue(activeEditor().source().getText().contains("- [ ] Task to delete <!-- task:" + parent.sourceItemId() + " -->"),
                    "Deleting a task preserves the original source checklist");
            return null;
        });
        clickDialogFromMenu("File", "Delete…", null);
        fx(() -> {
            selectTab(originalNoteId);
            assertEquals(1, taskList().getItems().size());
            assertEquals("Finish the notebook", taskList().getItems().getFirst().text());
            return null;
        });
        return parent.id();
    }
    private java.util.List<String> verifyToolbarDeletionWithDetails() throws Exception {
        var originalNoteId = fx(() -> (String) tabs().getSelectionModel().getSelectedItem().getUserData());
        int previousTasks = fx(() -> taskList().getItems().size());
        var parent = fx(() -> {
            ((Button) stage.getScene().lookup("#new-note")).fire();
            activeEditor().setMode("Write");
            activeEditor().rename("Temporary toolbar removal source");
            activeEditor().source().setText("- **Toolbar parent**\n  - Nested text survives.\n\nKeep another paragraph.\n");
            activeEditor().source().positionCaret(8); activeEditor().source().requestFocus();
            checklistTool().fire();
            var created = taskList().getItems().stream().filter(t -> t.text().contains("Toolbar parent")).findFirst().orElseThrow();
            taskList().getSelectionModel().select(created);
            taskList().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            activeEditor().setMode("Write");
            activeEditor().source().setText("Task details\n\nChild action to keep\n");
            activeEditor().source().positionCaret(activeEditor().source().getText().indexOf("Child action") + 3);
            activeEditor().source().requestFocus(); checklistTool().fire();
            activeEditor().setMode("Read");
            return created;
        });
        await(() -> fxUnchecked(() -> previewParagraph("Task details") != null));
        var child = fx(() -> {
            var created = taskList().getItems().stream().filter(t -> t.text().equals("Child action to keep")).findFirst().orElseThrow();
            assertEquals(Workspace.taskTabId(parent.id()), created.sourceNoteId());
            // Leave an actual rendered edit pending until the tab is switched to the source note.
            selectPreviewContents(previewParagraph("Task details"));
            assertEquals(Boolean.TRUE, ((JSObject) activePreview().getEngine().getDocument()).call(
                    "execCommand", "insertText", false, " with pending rendered information."));
            selectTab(parent.sourceNoteId()); activeEditor().setMode("Read");
            return created;
        });
        await(() -> fxUnchecked(() -> previewInput(parent.sourceItemId()) != null));
        var originalSource = fx(() -> activeEditor().source().getText());
        var originalDom = fx(() -> (String) activePreview().getEngine().executeScript("document.body.innerHTML"));
        Runnable remove = () -> { selectPreviewTaskText(parent.sourceItemId()); checklistTool().fire(); };
        Consumer<DialogPane> checkBeforeResponse = pane -> {
            assertEquals(originalSource, activeEditor().source().getText(), "Confirmation is requested before changing the source");
            assertEquals(originalDom, activePreview().getEngine().executeScript("document.body.innerHTML"),
                    "Opening confirmation leaves the rendered checkbox and row unchanged");
            var retained = taskList().getItems().stream().filter(t -> t.id().equals(parent.id())).findFirst().orElseThrow();
            assertTrue(retained.markdown().contains("pending rendered information"), "Pending rendered task details are retained");
            assertTrue(tabs().getTabs().stream().anyMatch(tab -> Workspace.taskTabId(parent.id()).equals(tab.getUserData())));
        };
        clickDialogAction("Remove detailed toolbar task", remove, null, ButtonType.CANCEL, checkBeforeResponse);
        fx(() -> {
            assertEquals(previousTasks + 2, taskList().getItems().size());
            assertEquals(originalSource, activeEditor().source().getText(), "Cancel leaves the source unchanged");
            assertEquals(originalDom, activePreview().getEngine().executeScript("document.body.innerHTML"),
                    "Cancel leaves the rendered checkbox and formatting unchanged");
            assertNotNull(previewInput(parent.sourceItemId()));
            assertTrue(taskList().getItems().stream().anyMatch(t -> t.id().equals(child.id()) && t.linked()),
                    "Cancel keeps a child task linked to the parent's details");
            return null;
        });
        clickDialogAction("Remove detailed toolbar task", remove, null, ButtonType.OK, checkBeforeResponse);
        await(() -> fxUnchecked(() -> previewInput(parent.sourceItemId()) == null));
        fx(() -> {
            assertEquals(previousTasks + 1, taskList().getItems().size());
            assertTrue(taskList().getItems().stream().noneMatch(t -> t.id().equals(parent.id())));
            assertTrue(tabs().getTabs().stream().noneMatch(tab -> Workspace.taskTabId(parent.id()).equals(tab.getUserData())),
                    "Removing the source checkbox closes the deleted task's open editor");
            assertTrue(activeEditor().source().getText().contains("- **Toolbar parent**"), "Removal keeps the row's bold text and bullet");
            assertTrue(activeEditor().source().getText().contains("  - Nested text survives."), "Nested list content survives removal");
            assertFalse(activeEditor().source().getText().contains("<!-- task:" + parent.sourceItemId() + " -->"));
            var preservedChild = taskList().getItems().stream().filter(t -> t.id().equals(child.id())).findFirst().orElseThrow();
            assertFalse(preservedChild.linked(), "Tasks created in deleted details survive as independent tasks");
            taskList().getSelectionModel().select(preservedChild);
            taskList().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        clickDialogFromMenu("File", "Delete…", null);
        fx(() -> { selectTab(parent.sourceNoteId()); return null; });
        clickDialogFromMenu("File", "Delete…", null);
        fx(() -> {
            selectTab(originalNoteId);
            assertEquals(previousTasks, taskList().getItems().size());
            return null;
        });
        System.out.println("Toolbar task removal: blank tasks toggle immediately; detailed tasks confirm, cancel safely, and preserve children");
        return java.util.List.of(parent.id(), child.id());
    }
    private WebView activePreview() { return (WebView) activeEditor().lookup("#markdown-preview"); }
    private TabPane tabs() { return (TabPane) stage.getScene().lookup("#note-tabs"); }
    private void selectTab(String id) {
        tabs().getSelectionModel().select(tabs().getTabs().stream().filter(tab -> id.equals(tab.getUserData())).findFirst().orElseThrow());
    }
    private Button checklistTool() {
        return activeEditor().lookupAll(".format-button").stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> button.getText().equals("☐")).findFirst().orElseThrow();
    }
    private Node previewParagraph(String text) {
        var preview = activePreview();
        if (preview == null || preview.getEngine().getDocument() == null) return null;
        var paragraphs = preview.getEngine().getDocument().getElementsByTagName("p");
        for (int i = 0; i < paragraphs.getLength(); i++) if (paragraphs.item(i).getTextContent().equals(text)) return paragraphs.item(i);
        return null;
    }
    private Node previewListItem(String text) {
        var document = activePreview().getEngine().getDocument();
        if (document == null) return null;
        var items = document.getElementsByTagName("li");
        for (int i = 0; i < items.getLength(); i++) if (items.item(i).getTextContent().strip().equals(text)) return items.item(i);
        return null;
    }
    private HTMLInputElement previewInput(String taskItemId) {
        var document = activePreview().getEngine().getDocument();
        if (document == null) return null;
        var inputs = document.getElementsByTagName("input");
        for (int i = 0; i < inputs.getLength(); i++)
            if (taskItemId.equals(((Element) inputs.item(i)).getAttribute("data-task-id"))) return (HTMLInputElement) inputs.item(i);
        return null;
    }
    private void selectPreviewTaskText(String taskItemId) {
        var input = previewInput(taskItemId); assertNotNull(input);
        var row = (Node) ((JSObject) input).call("closest", "li");
        var text = row.getLastChild();
        // A nested list must not move the selection into a different checklist row.
        for (var node = row.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && ((Element) node).getTagName().equalsIgnoreCase("strong")) {
                text = node; break;
            }
        }
        selectPreviewContents(text);
    }
    private void selectPreviewContents(Node node) {
        activePreview().requestFocus();
        var range = (JSObject) ((JSObject) activePreview().getEngine().getDocument()).call("createRange");
        range.call("selectNodeContents", node); range.call("collapse", false);
        selectPreviewRange(range);
    }
    @SuppressWarnings("unchecked")
    private ListView<Workspace.Todo> taskList() { return (ListView<Workspace.Todo>) stage.getScene().lookup("#tasks-list"); }
    private CheckBox taskCheckbox(String label) {
        return taskList().lookupAll(".task-row .check-box").stream().filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast).filter(box -> ("Complete task: " + label).equals(box.getAccessibleText()))
                .findFirst().orElse(null);
    }
    private Label taskLabel(String text) {
        return taskList().lookupAll(".task-text").stream().filter(Label.class::isInstance)
                .map(Label.class::cast).filter(label -> label.getText().equals(text)).findFirst().orElse(null);
    }
    private HTMLInputElement previewCheckbox() {
        var preview = activePreview();
        if (preview == null || preview.getEngine().getDocument() == null) return null;
        var inputs = preview.getEngine().getDocument().getElementsByTagName("input");
        return inputs.getLength() == 0 ? null : (HTMLInputElement) inputs.item(0);
    }
    private void selectPreviewRange(JSObject range) {
        var selection = (JSObject) ((JSObject) activePreview().getEngine().getDocument()).call("getSelection");
        selection.call("removeAllRanges"); selection.call("addRange", range);
    }
    private static void assertWithinWidth(javafx.scene.Node child, javafx.scene.Node parent, String description) {
        assertNotNull(child, description + " is present");
        var bounds = child.localToScene(child.getBoundsInLocal());
        var available = parent.localToScene(parent.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= available.getMinX() - 2 && bounds.getMaxX() <= available.getMaxX() + 2,
                description + " stays inside the available width: " + bounds + " within " + available);
    }
    private DialogPane showingDialog() {
        return Window.getWindows().stream().filter(window -> window != stage && window.isShowing())
                .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().orElse(null);
    }
    private void verifySidebarSizing() throws Exception {
        var body = fx(() -> (SplitPane) ((BorderPane) stage.getScene().getRoot()).getCenter());
        fx(() -> { body.setManaged(false); return null; });
        try {
            for (double width : new double[]{1200, 1800, 1000}) {
                fx(() -> { body.resize(width, body.getHeight()); return null; });
                await(() -> fxUnchecked(() -> sidebarRatio(body, .2)));
            }
            fx(() -> { body.setDividerPositions(.4); body.layout(); return null; });
            fx(() -> { body.resize(1500, body.getHeight()); return null; });
            await(() -> fxUnchecked(() -> sidebarRatio(body, 2.0 / 3)));
            fx(() -> {
                var right = (CheckMenuItem) menu("View", "Sidebar on right");
                right.setSelected(true); right.fire(); body.layout(); return null;
            });
            await(() -> fxUnchecked(() -> sidebarRatio(body, 2.0 / 3)));
            fx(() -> {
                var right = (CheckMenuItem) menu("View", "Sidebar on right");
                right.setSelected(false); right.fire();
                body.setDividerPositions(1.0 / 6); body.layout(); return null;
            });
        } finally {
            fx(() -> { body.setManaged(true); stage.getScene().getRoot().requestLayout(); return null; });
        }
        await(() -> fxUnchecked(() -> sidebarRatio(body, .2)));
    }
    private boolean sidebarRatio(SplitPane body, double ratio) {
        var sidebar = body.getItems().stream().filter(node -> node.getStyleClass().contains("sidebar")).findFirst().orElseThrow();
        var writing = body.getItems().stream().filter(node -> node != sidebar).findFirst().orElseThrow();
        return Math.abs(sidebar.getLayoutBounds().getWidth() - ratio * writing.getLayoutBounds().getWidth()) < 3;
    }
    private void verifySidebarSizePreferences() throws Exception {
        awaitSidebarSize(1.0 / 6, 6);
        double editorFont = fx(() -> activeEditor().source().getFont().getSize());
        sidebarSizeDialog(pane -> {
            sidebarWidthSlider(pane).setValue(10);
            sidebarTextSlider(pane).setValue(18);
        }, .10, 18, ButtonType.APPLY);
        awaitSidebarSize(.10, 18);
        fx(() -> {
            var notes = (ListView<?>) stage.getScene().lookup("#notes-list");
            var cell = notes.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast).filter(row -> !row.isEmpty() && row.getIndex() == 0).findFirst().orElseThrow();
            assertTrue(notes.getHeight() >= cell.prefHeight(-1), "Large text leaves at least one complete note row visible");
            saveScreenshot(stage.getScene(), Path.of("target/ui-sidebar-narrow.png")); return null;
        });
        sidebarSizeDialog(this::resetSidebarSize, 1.0 / 6, 6, ButtonType.APPLY);
        awaitSidebarSize(1.0 / 6, 6);
        sidebarSizeDialog(pane -> {
            sidebarWidthSlider(pane).setValue(30);
            sidebarTextSlider(pane).setValue(12);
        }, .30, 12, ButtonType.CANCEL);
        awaitSidebarSize(1.0 / 6, 6);

        sidebarSizeDialog(pane -> {
            sidebarWidthSlider(pane).setValue(30);
            sidebarTextSlider(pane).setValue(12);
        }, .30, 12, ButtonType.APPLY);
        awaitSidebarSize(.30, 12);

        sidebarSizeDialog(pane -> {
            assertEquals(30, sidebarWidthSlider(pane).getValue(), .5, "Reopening reflects the applied width");
            assertEquals(12, sidebarTextSlider(pane).getValue(), .001, "Reopening reflects the applied text size");
            resetSidebarSize(pane);
        }, 1.0 / 6, 6, ButtonType.CANCEL);
        awaitSidebarSize(.30, 12);

        sidebarSizeDialog(this::resetSidebarSize, 1.0 / 6, 6, ButtonType.APPLY);
        awaitSidebarSize(1.0 / 6, 6);
        // Keep a non-default preference for the save/reopen assertions later in this test.
        sidebarSizeDialog(pane -> {
            sidebarWidthSlider(pane).setValue(30);
            sidebarTextSlider(pane).setValue(12);
        }, .30, 12, ButtonType.APPLY);
        awaitSidebarSize(.30, 12);
        assertEquals(editorFont, fx(() -> activeEditor().source().getFont().getSize()), .001,
                "Changing sidebar text leaves the editor font unchanged");
        System.out.println("Sidebar preferences: live preview, apply, cancel, reset, and editor font isolation passed");
    }
    private Slider sidebarWidthSlider(DialogPane pane) { return (Slider) pane.lookup("#sidebar-width"); }
    private Slider sidebarTextSlider(DialogPane pane) { return (Slider) pane.lookup("#sidebar-text-size"); }
    private void resetSidebarSize(DialogPane pane) {
        var reset = pane.getButtonTypes().stream().filter(type -> "Reset".equals(type.getText())).findFirst().orElseThrow();
        ((Button) pane.lookupButton(reset)).fire();
        assertTrue(pane.getScene().getWindow().isShowing(), "Reset previews defaults without closing the dialog");
    }
    private void awaitSidebarSize(double fraction, double textSize) throws Exception {
        await(() -> fxUnchecked(() -> {
            var root = (BorderPane) stage.getScene().getRoot(); root.applyCss(); root.layout();
            var name = (Label) root.lookup(".sidebar .note-name");
            return name != null && Math.abs(name.getFont().getSize() - textSize) < .01
                    && sidebarRatio((SplitPane) root.getCenter(), fraction / (1 - fraction));
        }));
    }
    private void sidebarSizeDialog(Consumer<DialogPane> edit, double width, double textSize, ButtonType response) throws Exception {
        boolean fullScreen = fx(() -> stage.isFullScreen());
        double editorFont = fx(() -> activeEditor().source().getFont().getSize());
        Platform.runLater(() -> menu("View", "Sidebar size…").fire());
        await(() -> fxUnchecked(() -> showingDialog() != null));
        var pane = fx(this::showingDialog);
        try {
            fx(() -> {
                assertNotNull(sidebarWidthSlider(pane));
                assertNotNull(sidebarTextSlider(pane));
                edit.accept(pane);
                pane.applyCss(); pane.layout();
                saveScreenshot(pane.getScene(), Path.of("target/ui-sidebar-settings.png"));
                assertEquals(width * 100, sidebarWidthSlider(pane).getValue(), .001);
                assertEquals(textSize, sidebarTextSlider(pane).getValue(), .001);
                return null;
            });
            await(() -> fxUnchecked(() -> {
                var root = (BorderPane) stage.getScene().getRoot(); root.applyCss(); root.layout();
                var body = (SplitPane) root.getCenter();
                // Leaving fullscreen can make the tiled owner narrow enough to hide the sidebar.
                // In that case the callers verify the actual result once fullscreen is restored.
                if (body.getItems().size() != 2) return true;
                var name = (Label) root.lookup(".sidebar .note-name");
                return name != null && Math.abs(name.getFont().getSize() - textSize) < .01
                        && sidebarRatio(body, width / (1 - width));
            }));
            fx(() -> {
                assertEquals(editorFont, activeEditor().source().getFont().getSize(), .001,
                        "The live sidebar preview does not alter the editor font");
                ((Button) pane.lookupButton(response)).fire();
                return null;
            });
            await(() -> fxUnchecked(() -> showingDialog() == null && stage.isFullScreen() == fullScreen));
            Thread.sleep(350);
        } finally {
            fx(() -> {
                if (pane.getScene() != null && pane.getScene().getWindow().isShowing())
                    ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                return null;
            });
        }
    }
    private void verifySidebarSectionDrag() throws Exception {
        fx(() -> {
            assertNull(stage.getScene().lookup("#collections-list"), "The collections section is removed from the sidebar");
            assertNull(stage.getScene().lookup("#collections-notes-sections"));
            return null;
        });
        dragSidebarDivider("#sidebar-sections", "Notes section", "Tasks section");
        System.out.println("Sidebar divider mouse test: notes and tasks have adjustable vertical space");
    }
    private void dragSidebarDivider(String selector, String firstDescription, String secondDescription) throws Exception {
        fx(() -> { stage.toFront(); stage.requestFocus(); return null; });
        await(() -> fxUnchecked(stage::isFocused));
        var split = fx(() -> (SplitPane) stage.getScene().lookup(selector));
        assertNotNull(split, "The sidebar section split is present: " + selector);
        double before = fx(() -> split.getDividerPositions()[0]);
        double firstHeight = fx(() -> split.getItems().getFirst().getLayoutBounds().getHeight());
        double secondHeight = fx(() -> split.getItems().getLast().getLayoutBounds().getHeight());
        var target = fx(() -> {
            assertEquals(javafx.geometry.Orientation.VERTICAL, split.getOrientation());
            var divider = split.lookupAll(".split-pane-divider").stream()
                    .filter(node -> node.getParent() == split).findFirst().orElseThrow();
            assertTrue(divider.getLayoutBounds().getHeight() >= 8, "The divider has a usable mouse target");
            return divider.localToScreen(divider.getBoundsInLocal());
        });
        var observed = new StringBuilder();
        javafx.event.EventHandler<MouseEvent> observe = event -> {
            if (event.getEventType() == MouseEvent.MOUSE_PRESSED
                    || event.getEventType() == MouseEvent.MOUSE_DRAGGED
                    || event.getEventType() == MouseEvent.MOUSE_RELEASED) {
                observed.append(event.getEventType()).append(" target=").append(event.getTarget())
                        .append(" screen=").append(event.getScreenX()).append(',').append(event.getScreenY())
                        .append(" scene=").append(event.getSceneX()).append(',').append(event.getSceneY()).append('\n');
            }
        };
        fx(() -> {
            stage.getScene().addEventFilter(MouseEvent.ANY, observe);
            observed.append("Divider=").append(target).append(" stageFocused=").append(stage.isFocused()).append('\n');
            new Robot().mouseMove(target.getCenterX(), target.getCenterY());
            return null;
        });
        Thread.sleep(120);
        try {
            fx(() -> { new Robot().mousePress(MouseButton.PRIMARY); return null; });
            for (int step = 1; step <= 8; step++) {
                double y = target.getCenterY() - step * 6;
                fx(() -> { new Robot().mouseMove(target.getCenterX(), y); return null; });
                Thread.sleep(40);
            }
        } finally {
            fx(() -> { new Robot().mouseRelease(MouseButton.PRIMARY); return null; });
        }
        try {
            await(() -> fxUnchecked(() -> split.getDividerPositions()[0] < before - .04));
        } catch (AssertionError failure) {
            throw new AssertionError("Sidebar divider mouse drag failed: " + fx(observed::toString), failure);
        } finally {
            fx(() -> { stage.getScene().removeEventFilter(MouseEvent.ANY, observe); return null; });
        }
        fx(() -> {
            assertTrue(split.getItems().getFirst().getLayoutBounds().getHeight() < firstHeight - 10,
                    "Dragging upward shrinks " + firstDescription);
            assertTrue(split.getItems().getLast().getLayoutBounds().getHeight() > secondHeight + 10,
                    "Dragging upward grows " + secondDescription);
            assertWithinWidth(split.getItems().getFirst(), split, firstDescription);
            assertWithinWidth(split.getItems().getLast(), split, secondDescription);
            split.setDividerPositions(before);
            split.layout();
            return null;
        });
    }
    private void clickDialogFromMenu(String menuText, String itemText, String input) throws Exception {
        clickDialogFromMenu(menuText, itemText, input, ButtonType.OK);
    }
    private void clickDialogFromMenu(String menuText, String itemText, String input, ButtonType response) throws Exception {
        clickDialogAction(itemText, () -> menu(menuText, itemText).fire(), input, response, pane -> {});
    }
    private void clickDialogAction(String description, Runnable action, String input, ButtonType response,
                                   Consumer<DialogPane> beforeResponse) throws Exception {
        boolean fullScreen = fx(() -> stage.isFullScreen());
        // Activate the same menu action without relying on fullscreen Stage screen coordinates.
        Platform.runLater(action);
        respondToDialog(description, input, response, beforeResponse, fullScreen);
    }
    private void respondToDialog(String description, String input, ButtonType response,
                                  Consumer<DialogPane> beforeResponse, boolean fullScreen) throws Exception {
        await(() -> fxUnchecked(() -> showingDialog() != null));
        var pane = fx(this::showingDialog);
        try {
            assertFalse(fx(() -> stage.isFullScreen()), "The owner leaves fullscreen while a dialog is open");
            // Allow the compositor to position the dialog after leaving fullscreen.
            Thread.sleep(350);
            var responseButton = fx(() -> {
                beforeResponse.accept(pane);
                var button = (Button) pane.lookupButton(response);
                if (input != null) {
                    var editor = (TextField) pane.lookup(".text-field");
                    if (editor.getText().isBlank() && response == ButtonType.OK)
                        assertTrue(button.isDisabled(), "An empty name cannot be submitted");
                    editor.setText(input);
                }
                assertFalse(button.isDisabled(), "The requested dialog action must be enabled");
                saveScreenshot(pane.getScene(), Path.of("target/ui-dialog.png"));
                return button;
            });
            mouseClick(responseButton);
            await(() -> fxUnchecked(() -> showingDialog() == null));
            await(() -> fxUnchecked(() -> stage.isFullScreen() == fullScreen));
            Thread.sleep(350);
            System.out.printf("Dialog mouse test: %s closed with %s; fullscreen restored=%s%n", description, response.getText(), fullScreen);
        } finally {
            // Release a stuck modal loop after failure so the test process can shut down.
            fx(() -> {
                if (pane.getScene() != null && pane.getScene().getWindow().isShowing()) {
                    var cancel = pane.lookupButton(ButtonType.CANCEL);
                    ((Button) (cancel == null ? pane.lookupButton(ButtonType.OK) : cancel)).fire();
                }
                return null;
            });
        }
    }
    private void mouseClick(javafx.scene.Node node) throws Exception {
        fx(() -> {
            var bounds = node.localToScreen(node.getBoundsInLocal());
            assertNotNull(bounds, "Mouse target must be in a visible window");
            new Robot().mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(120);
        fx(() -> { new Robot().mousePress(MouseButton.PRIMARY); return null; });
        Thread.sleep(60);
        fx(() -> { new Robot().mouseRelease(MouseButton.PRIMARY); return null; });
        Thread.sleep(120);
    }
    private void mouseDoubleClick(javafx.scene.Node node) throws Exception {
        // Ensure an earlier click on this target cannot turn this gesture into a triple click.
        Thread.sleep(650);
        fx(() -> {
            var bounds = node.localToScreen(node.getBoundsInLocal());
            assertNotNull(bounds, "Double-click target must be in a visible window");
            new Robot().mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(120);
        fx(() -> {
            // Queue one native gesture so a busy FX thread cannot stretch its click interval.
            var robot = new Robot();
            for (int click = 0; click < 2; click++) {
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseRelease(MouseButton.PRIMARY);
            }
            return null;
        });
        Thread.sleep(120);
    }
    private MenuItem menu(String menu, String item) {
        return ((MenuBar) stage.getScene().lookup(".menu-bar")).getMenus().stream().filter(m -> m.getText().equals(menu))
                .flatMap(m -> m.getItems().stream()).filter(m -> item.equals(m.getText())).findFirst().orElseThrow();
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action); Platform.runLater(future); return future.get(15, TimeUnit.SECONDS);
    }
    private static boolean fxUnchecked(Callable<Boolean> action) {
        try { return fx(action); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("Timed out waiting for the JavaFX UI");
            Thread.sleep(100);
        }
    }
    private static void saveScreenshot(Scene scene, Path path) throws IOException {
        var image = scene.snapshot(null); int width = (int) image.getWidth(), height = (int) image.getHeight();
        var raw = new ByteArrayOutputStream();
        try (var compressed = new DeflaterOutputStream(raw)) {
            var pixels = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                compressed.write(0);
                for (int x = 0; x < width; x++) {
                    int c = pixels.getArgb(x, y);
                    compressed.write((c >> 16) & 255); compressed.write((c >> 8) & 255);
                    compressed.write(c & 255); compressed.write((c >> 24) & 255);
                }
            }
        }
        try (var out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeLong(0x89504e470d0a1a0aL);
            var bytes = new ByteArrayOutputStream(); var header = new DataOutputStream(bytes);
            header.writeInt(width); header.writeInt(height); header.write(new byte[]{8, 6, 0, 0, 0});
            chunk(out, "IHDR", bytes.toByteArray()); chunk(out, "IDAT", raw.toByteArray()); chunk(out, "IEND", new byte[0]);
        }
    }
    private static void chunk(DataOutputStream out, String name, byte[] bytes) throws IOException {
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII); var crc = new CRC32(); crc.update(type); crc.update(bytes);
        out.writeInt(bytes.length); out.write(type); out.write(bytes); out.writeInt((int) crc.getValue());
    }
}
