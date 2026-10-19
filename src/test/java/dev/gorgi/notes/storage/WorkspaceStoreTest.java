package dev.gorgi.notes.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.markdown.TaskLinks;
import dev.gorgi.notes.model.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceStoreTest {
    @TempDir Path directory;

    @Test void aClosedStoreCannotOverwriteAnotherInstancesWorkspace() throws IOException {
        var closed = new WorkspaceStore(directory);
        closed.save(sampleWorkspace());
        closed.close();
        try (var next = new WorkspaceStore(directory)) {
            var fresh = Workspace.fresh(); next.save(fresh);
            assertThrows(IOException.class, () -> closed.save(sampleWorkspace()));
            assertEquals(fresh, next.load());
        }
    }

    @Test
    void newDirectoryStartsWithAUsableNotebookWithoutWritingIt() throws IOException {
        try (var store = new WorkspaceStore(directory.resolve("new-notebook"))) {
            var workspace = store.load();
            assertFalse(workspace.collections().isEmpty());
            assertFalse(workspace.notes().isEmpty());
            assertTrue(workspace.openNotes().contains(workspace.selectedNote()));
            assertTrue(workspace.notes().stream().anyMatch(n -> n.id().equals(workspace.selectedNote())));
            assertFalse(Files.exists(store.file()));
            store.save(workspace);
            assertEquals(workspace, store.load());
        }
    }

    @Test
    void roundTripsUnicodeMarkdownTasksCollectionsAndTabOrder() throws IOException {
        var workspace = sampleWorkspace();
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
        }
        try (var reopened = new WorkspaceStore(directory)) {
            assertEquals(workspace, reopened.load());
        }
    }

    @Test void sidebarSizesSurviveClosingAndReopeningTheWorkspace() throws IOException {
        var workspace = withSidebarSizes(sampleWorkspace(), .35, 11);
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            var properties = readProperties(store.file());
            assertEquals("1", properties.getProperty("version"));
            assertEquals("0.35", properties.getProperty("sidebarWidth"));
            assertEquals("11.0", properties.getProperty("sidebarTextSize"));
        }
        try (var reopened = new WorkspaceStore(directory)) {
            assertEquals(workspace, reopened.load());
        }
    }

    @Test void olderWorkspacesUseDefaultSidebarSizesWithoutChangingNotebookData() throws IOException {
        var original = sampleWorkspace();
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.remove("sidebarWidth");
            properties.remove("sidebarTextSize");
            writeProperties(store.file(), properties);
            var saved = Files.readAllBytes(store.file());

            var loaded = store.load();
            assertEquals(Workspace.DEFAULT_SIDEBAR_WIDTH, loaded.sidebarWidth());
            assertEquals(Workspace.DEFAULT_SIDEBAR_TEXT_SIZE, loaded.sidebarTextSize());
            assertEquals(original, loaded);
            assertArrayEquals(saved, Files.readAllBytes(store.file()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "", "NaN", "Infinity", "-Infinity", "1e500"})
    void corruptSidebarPreferencesFallBackIndependentlyWithoutBlockingNotes(String value) throws IOException {
        var original = withSidebarSizes(sampleWorkspace(), .35, 11);
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.setProperty("sidebarWidth", value);
            writeProperties(store.file(), properties);
            var saved = Files.readAllBytes(store.file());
            assertEquals(withSidebarSizes(original, Workspace.DEFAULT_SIDEBAR_WIDTH, 11), store.load());
            assertArrayEquals(saved, Files.readAllBytes(store.file()));

            properties.setProperty("sidebarWidth", "0.35");
            properties.setProperty("sidebarTextSize", value);
            writeProperties(store.file(), properties);
            saved = Files.readAllBytes(store.file());
            assertEquals(withSidebarSizes(original, .35, Workspace.DEFAULT_SIDEBAR_TEXT_SIZE), store.load());
            assertArrayEquals(saved, Files.readAllBytes(store.file()));
        }
    }

    @ParameterizedTest
    @CsvSource({"-1, -10, 0.10, 6", "0, 0, 0.10, 6", "1, 100, 0.70, 18", "0.10, 18, 0.10, 18"})
    void sidebarSizesClampToSupportedLimitsInSnapshotsAndSavedPreferences(
            double width, double textSize, double expectedWidth, double expectedTextSize) throws IOException {
        var original = sampleWorkspace();
        var expected = withSidebarSizes(original, expectedWidth, expectedTextSize);
        assertEquals(expected, withSidebarSizes(original, width, textSize));
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.setProperty("sidebarWidth", Double.toString(width));
            properties.setProperty("sidebarTextSize", Double.toString(textSize));
            writeProperties(store.file(), properties);
            assertEquals(expected, store.load());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonfiniteSidebarSizesUseDefaultsInSnapshots(double value) {
        var original = sampleWorkspace();
        assertEquals(original, withSidebarSizes(original, value, value));
    }

    @Test void editorFontSizeSurvivesClosingAndReopeningTheWorkspace() throws IOException {
        var workspace = withEditorFontSize(withSidebarSizes(sampleWorkspace(), .35, 11), 19.5);
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            var properties = readProperties(store.file());
            assertEquals("1", properties.getProperty("version"));
            assertEquals("19.5", properties.getProperty("editorFontSize"));
        }
        try (var reopened = new WorkspaceStore(directory)) {
            assertEquals(workspace, reopened.load());
        }
    }

    @Test void olderWorkspacesUseDefaultEditorFontSizeWithoutChangingOtherPreferences() throws IOException {
        var original = withSidebarSizes(sampleWorkspace(), .35, 11);
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.remove("editorFontSize");
            writeProperties(store.file(), properties);
            var saved = Files.readAllBytes(store.file());

            var loaded = store.load();
            assertEquals(Workspace.DEFAULT_EDITOR_FONT_SIZE, loaded.editorFontSize());
            assertEquals(original, loaded);
            assertArrayEquals(saved, Files.readAllBytes(store.file()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "", "NaN", "Infinity", "-Infinity", "1e500"})
    void corruptEditorFontSizeUsesDefaultWithoutBlockingNotes(String value) throws IOException {
        var original = withSidebarSizes(sampleWorkspace(), .35, 11);
        try (var store = new WorkspaceStore(directory)) {
            store.save(withEditorFontSize(original, 20));
            var properties = readProperties(store.file());
            properties.setProperty("editorFontSize", value);
            writeProperties(store.file(), properties);
            var saved = Files.readAllBytes(store.file());
            assertEquals(original, store.load());
            assertArrayEquals(saved, Files.readAllBytes(store.file()));
        }
    }

    @ParameterizedTest
    @CsvSource({"-1, 8", "0, 8", "8, 8", "40, 40", "100, 40"})
    void editorFontSizeClampsToSupportedLimitsInSnapshotsAndSavedPreferences(
            double fontSize, double expectedFontSize) throws IOException {
        var original = withSidebarSizes(sampleWorkspace(), .35, 11);
        var expected = withEditorFontSize(original, expectedFontSize);
        assertEquals(expected, withEditorFontSize(original, fontSize));
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.setProperty("editorFontSize", Double.toString(fontSize));
            writeProperties(store.file(), properties);
            assertEquals(expected, store.load());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonfiniteEditorFontSizesUseDefaultInSnapshots(double value) {
        var original = withSidebarSizes(sampleWorkspace(), .35, 11);
        assertEquals(original, withEditorFontSize(original, value));
    }

    @Test void linkedTaskIdentitySurvivesClosingAndReopeningWorkspace() throws IOException {
        var original = sampleWorkspace();
        var change = TaskLinks.toggle(original.notes().getFirst().markdown(), 0, true);
        var first = original.notes().getFirst();
        var notes = List.of(new Workspace.Note(first.id(), first.title(), first.collectionId(), change.markdown()),
                original.notes().get(1));
        var tasks = TaskLinks.link(first.id(), change, original.tasks());
        var linked = new Workspace(notes, original.collections(), tasks, original.openNotes(), original.selectedNote(), true);
        try (var store = new WorkspaceStore(directory)) { store.save(linked); }
        try (var reopened = new WorkspaceStore(directory)) {
            var loaded = reopened.load();
            assertEquals(linked, loaded);
            var task = loaded.tasks().getLast();
            assertTrue(task.linked());
            var edit = TaskLinks.toggle(loaded.notes().getFirst().markdown(), 0, false);
            var updated = TaskLinks.link(first.id(), edit, loaded.tasks());
            assertEquals(loaded.tasks().size(), updated.size());
            assertEquals(task.id(), updated.getLast().id());
            assertFalse(updated.getLast().done());
        }
    }

    @Test void originalVersionOneWorkspaceLoadsTasksAsIndependentEntities() throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            store.save(sampleWorkspace());
            var properties = readProperties(store.file());
            assertEquals("1", properties.getProperty("version"));
            assertNull(properties.getProperty("task.0.sourceNote"));
            assertNull(properties.getProperty("task.0.markdown"));
            assertTrue(store.load().tasks().stream().noneMatch(Workspace.Todo::linked));
            assertTrue(store.load().tasks().stream().allMatch(task -> task.markdown().isEmpty()));
            assertEquals(sampleWorkspace(), store.load());
        }
    }

    @Test void roundTripsTaskDetailsAndMixedTabsWithATaskSelected() throws IOException {
        var original = sampleWorkspace();
        var details = "# Подготовка 📝\n\nKeep **this detail**.\n\n- [ ] Review\n\n```java\nvar ready = true;\n```\n";
        var firstTask = original.tasks().getFirst().withMarkdown(details);
        var tasks = List.of(firstTask, original.tasks().get(1));
        var tabs = List.of("note-two", Workspace.taskTabId(firstTask.id()), "note-one",
                Workspace.taskTabId(tasks.getLast().id()));
        var workspace = new Workspace(original.notes(), original.collections(), tasks, tabs,
                Workspace.taskTabId(firstTask.id()), false);
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            var properties = readProperties(store.file());
            assertEquals("1", properties.getProperty("version"));
            assertEquals(details, properties.getProperty("task.0.markdown"));
            assertEquals(tabs.get(1), properties.getProperty("open.1"));
        }
        try (var reopened = new WorkspaceStore(directory)) {
            var loaded = reopened.load();
            assertEquals(workspace, loaded);
            assertEquals(tabs, loaded.openTabs());
            assertEquals(Workspace.taskTabId(firstTask.id()), loaded.selectedTab());
            assertEquals(details, loaded.tasks().getFirst().markdown());
        }
    }

    @Test void taskOnlyNotebookCanRestoreItsOpenTask() throws IOException {
        var task = new Workspace.Todo("task", "Plan the day", false).withMarkdown("## Details\n");
        var workspace = new Workspace(List.of(), List.of(new Workspace.Collection("inbox", "Inbox")),
                List.of(task), List.of(Workspace.taskTabId(task.id())), Workspace.taskTabId(task.id()), false);
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            assertEquals(workspace, store.load());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-one", "task:missing", "task:note-one"})
    void unknownTaskTabsAreRejectedWithoutOverwritingWorkspace(String tabId) throws IOException {
        var original = sampleWorkspace();
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var saved = Files.readAllBytes(store.file());
            var invalid = new Workspace(original.notes(), original.collections(), original.tasks(),
                    List.of(tabId), tabId, false);
            assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
            assertArrayEquals(saved, Files.readAllBytes(store.file()));

            var properties = readProperties(store.file());
            properties.setProperty("open.0", tabId);
            properties.setProperty("selected", tabId);
            try (var output = Files.newOutputStream(store.file())) {
                properties.storeToXML(output, "Invalid task tab fixture", "UTF-8");
            }
            assertThrows(IOException.class, store::load);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesAnEmptyNotebookAndSidebarSetting(boolean sidebarRight) throws IOException {
        var empty = new Workspace(List.of(), List.of(new Workspace.Collection("inbox", "Inbox")),
                List.of(), List.of(), "", sidebarRight);
        try (var store = new WorkspaceStore(directory)) {
            store.save(empty);
            assertEquals(empty, store.load());
        }
    }

    @Test
    void preservesAnEmptyNoteAndNoOpenTabs() throws IOException {
        var workspace = new Workspace(List.of(new Workspace.Note("blank", "", "inbox", "")),
                List.of(new Workspace.Collection("inbox", "Inbox")), List.of(), List.of(), "", false);
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            assertEquals(workspace, store.load());
        }
    }

    @Test
    void eachSuccessfulSaveKeepsExactlyThePreviousWorkspaceAsBackup() throws IOException {
        var backup = directory.resolve("workspace.xml.bak");
        try (var store = new WorkspaceStore(directory)) {
            store.save(sampleWorkspace());
            var first = Files.readAllBytes(store.file());
            assertFalse(Files.exists(backup));

            var second = Workspace.fresh();
            store.save(second);
            assertEquals(second, store.load());
            assertArrayEquals(first, Files.readAllBytes(backup));
            var secondBytes = Files.readAllBytes(store.file());

            var third = Workspace.fresh();
            store.save(third);
            assertEquals(third, store.load());
            assertArrayEquals(secondBytes, Files.readAllBytes(backup));
            assertNoTemporaryFiles();
        }
    }

    @Test
    void failedBackupLeavesTheSavedWorkspaceIntactAndRemovesTemporaryFiles() throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            var original = sampleWorkspace();
            store.save(original);
            var originalBytes = Files.readAllBytes(store.file());
            var blockedBackup = Files.createDirectory(directory.resolve("workspace.xml.bak"));
            Files.writeString(blockedBackup.resolve("occupied"), "Prevent replacement of this directory");

            assertThrows(IOException.class, () -> store.save(Workspace.fresh()));
            assertArrayEquals(originalBytes, Files.readAllBytes(store.file()));
            assertEquals(original, store.load());
            assertNoTemporaryFiles();
        }
    }

    @Test
    void invalidSnapshotDoesNotReplaceEitherSavedFile() throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            var valid = sampleWorkspace();
            store.save(Workspace.fresh());
            store.save(valid);
            var saved = Files.readAllBytes(store.file());
            var backup = Files.readAllBytes(directory.resolve("workspace.xml.bak"));
            var invalid = new Workspace(valid.notes(), valid.collections(), valid.tasks(),
                    valid.openNotes(), "missing-note", false);

            assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
            assertArrayEquals(saved, Files.readAllBytes(store.file()));
            assertArrayEquals(backup, Files.readAllBytes(directory.resolve("workspace.xml.bak")));
            assertNoTemporaryFiles();
        }
    }

    @Test
    void malformedXmlIsRefusedWithoutOverwritingItOrItsBackup() throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            store.save(sampleWorkspace());
            store.save(Workspace.fresh());
            var backup = Files.readAllBytes(directory.resolve("workspace.xml.bak"));
            var corrupt = "<properties><entry key=\"note.0.markdown\">unfinished";
            Files.writeString(store.file(), corrupt);

            assertThrows(IOException.class, store::load);
            assertEquals(corrupt, Files.readString(store.file()));
            assertArrayEquals(backup, Files.readAllBytes(directory.resolve("workspace.xml.bak")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsupported-version", "missing-markdown", "negative-count", "excessive-count",
            "invalid-boolean", "duplicate-note", "unknown-collection", "unknown-open-note", "unknown-selected-tab"})
    void inconsistentWorkspaceIsRefusedWithoutReplacingIt(String corruption) throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            store.save(sampleWorkspace());
            var properties = readProperties(store.file());
            switch (corruption) {
                case "unsupported-version" -> properties.setProperty("version", "999");
                case "missing-markdown" -> properties.remove("note.0.markdown");
                case "negative-count" -> properties.setProperty("notes", "-1");
                case "excessive-count" -> properties.setProperty("notes", "100001");
                case "invalid-boolean" -> properties.setProperty("sidebarRight", "yes");
                case "duplicate-note" -> properties.setProperty("note.1.id", properties.getProperty("note.0.id"));
                case "unknown-collection" -> properties.setProperty("note.0.collection", "missing-collection");
                case "unknown-open-note" -> properties.setProperty("open.0", "missing-note");
                case "unknown-selected-tab" -> properties.setProperty("selected", "missing-note");
                default -> fail("Unknown corruption fixture: " + corruption);
            }
            try (var output = Files.newOutputStream(store.file())) {
                properties.storeToXML(output, "Corrupt fixture", "UTF-8");
            }
            var corrupt = Files.readAllBytes(store.file());

            assertThrows(IOException.class, store::load);
            assertArrayEquals(corrupt, Files.readAllBytes(store.file()));
            assertFalse(Files.exists(directory.resolve("workspace.xml.bak")));
        }
    }

    @Test
    void missingPrimaryWithBackupRequiresRecoveryInsteadOfStartingFresh() throws IOException {
        try (var store = new WorkspaceStore(directory)) {
            store.save(sampleWorkspace());
            store.save(Workspace.fresh());
            var backup = directory.resolve("workspace.xml.bak");
            var backupBytes = Files.readAllBytes(backup);
            Files.delete(store.file());

            assertThrows(IOException.class, store::load);
            assertFalse(Files.exists(store.file()));
            assertArrayEquals(backupBytes, Files.readAllBytes(backup));
        }
    }

    @Test
    void onlyOneWriterCanOpenANotebookAndClosingReleasesTheLock() throws IOException {
        var original = sampleWorkspace();
        try (var first = new WorkspaceStore(directory)) {
            first.save(original);
            assertThrows(IOException.class, () -> {
                try (var unexpected = new WorkspaceStore(directory)) {
                    fail("A second writer acquired the notebook lock");
                }
            });
            assertEquals(original, first.load());
        }
        try (var reopened = new WorkspaceStore(directory)) {
            assertEquals(original, reopened.load());
            reopened.save(Workspace.fresh());
        }
    }

    @Test
    void snapshotsDoNotChangeWhenTheUiListsAreEdited() {
        var initial = sampleWorkspace();
        var notes = new ArrayList<>(initial.notes());
        var collections = new ArrayList<>(initial.collections());
        var tasks = new ArrayList<>(initial.tasks());
        var tabs = new ArrayList<>(initial.openNotes());
        var snapshot = new Workspace(notes, collections, tasks, tabs, initial.selectedNote(), initial.sidebarRight());
        notes.clear();
        collections.clear();
        tasks.clear();
        tabs.clear();

        assertEquals(initial, snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.notes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.collections().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.openNotes().clear());
    }

    private static Workspace sampleWorkspace() {
        var markdown = """
                # Белешки — 日本語 📝

                **Bold**, _italic_, ~~done~~, and `a < b && b > c`.

                - [ ] Подготви чај ☕
                - [x] Save a thought

                > A & B <C> "quoted" 'apostrophe'

                ```java
                var message = "Здраво 🌍";
                ```

                | Idea | Status |
                | --- | --- |
                | Café | Ready |
                """;
        return new Workspace(
                List.of(new Workspace.Note("note-one", "Идеи 📝 & <thoughts>", "ideas", markdown),
                        new Workspace.Note("note-two", "Plan", "inbox", "Next steps\n\n")),
                List.of(new Workspace.Collection("inbox", "Inbox"), new Workspace.Collection("ideas", "Идеи")),
                List.of(new Workspace.Todo("task-one", "Купи млеко 🥛", false),
                        new Workspace.Todo("task-two", "Finish & review", true)),
                List.of("note-two", "note-one"), "note-one", true);
    }

    @Test
    void remembersOpenTabsAcrossCollectionsAndTheActiveCollection() throws IOException {
        var original = sampleWorkspace();
        var workspace = new Workspace(original.notes(), original.collections(), original.tasks(),
                original.openTabs(), "note-two", false, original.sidebarWidth(),
                original.sidebarTextSize(), original.editorFontSize(), "inbox");
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
        }
        try (var reopened = new WorkspaceStore(directory)) {
            assertEquals(workspace, reopened.load());
            assertEquals(List.of("note-two", "note-one"), reopened.load().openTabs());
            assertEquals("inbox", reopened.load().activeCollectionId());
        }
    }

    @Test
    void anActiveEmptyCollectionStillRetainsOtherCollectionsOpenTabs() throws IOException {
        var original = sampleWorkspace();
        var collections = new ArrayList<>(original.collections());
        collections.add(new Workspace.Collection("empty", "Empty"));
        var workspace = new Workspace(original.notes(), collections, original.tasks(),
                original.openTabs(), "", false, original.sidebarWidth(),
                original.sidebarTextSize(), original.editorFontSize(), "empty");
        try (var store = new WorkspaceStore(directory)) {
            store.save(workspace);
            assertEquals(workspace, store.load());
        }
    }

    @Test
    void olderWorkspacesWithoutAnActiveCollectionKeepAllOpenTabs() throws IOException {
        var original = sampleWorkspace();
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.remove("activeCollection");
            writeProperties(store.file(), properties);
            assertEquals(original, store.load());
        }
    }

    @Test
    void aStaleActiveCollectionDoesNotPreventOpeningTheNotebook() throws IOException {
        var original = sampleWorkspace();
        try (var store = new WorkspaceStore(directory)) {
            store.save(original);
            var properties = readProperties(store.file());
            properties.setProperty("activeCollection", "deleted-collection");
            writeProperties(store.file(), properties);
            assertEquals(original, store.load());
        }
    }

    private static Properties readProperties(Path file) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.loadFromXML(input);
        }
        return properties;
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        try (var output = Files.newOutputStream(file)) {
            properties.storeToXML(output, "Sidebar preference fixture", "UTF-8");
        }
    }

    private static Workspace withSidebarSizes(Workspace workspace, double width, double textSize) {
        return new Workspace(workspace.notes(), workspace.collections(), workspace.tasks(), workspace.openTabs(),
                workspace.selectedTab(), workspace.sidebarRight(), width, textSize, workspace.editorFontSize());
    }

    private static Workspace withEditorFontSize(Workspace workspace, double fontSize) {
        return new Workspace(workspace.notes(), workspace.collections(), workspace.tasks(), workspace.openTabs(),
                workspace.selectedTab(), workspace.sidebarRight(), workspace.sidebarWidth(), workspace.sidebarTextSize(),
                fontSize);
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
