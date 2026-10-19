package dev.gorgi.notes.storage;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import dev.gorgi.notes.model.Workspace;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** One atomic workspace file, one previous-save backup, and one writer per workspace. */
public final class WorkspaceStore implements AutoCloseable {
  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;

  public WorkspaceStore(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
    lockChannel =
        FileChannel.open(
            directory.resolve("workspace.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      lockChannel.close();
      throw new IOException("This notebook is already open in another window.", e);
    } catch (IOException e) {
      lockChannel.close();
      throw e;
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IOException("This notebook is already open in another application instance.");
    }
    lock = acquired;
  }

  public static Path defaultDirectory() {
    var override = System.getProperty("notes.dataDir");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    var xdg = System.getenv("XDG_DATA_HOME");
    var base =
        xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
    return base.resolve("javafx-notes");
  }

  public Path directory() {
    return directory;
  }

  public Path file() {
    return directory.resolve("workspace.xml");
  }

  public Workspace load() throws IOException {
    if (!Files.exists(file())) {
      if (Files.exists(directory.resolve("workspace.xml.bak"))) {
        throw new IOException(
            "The workspace file is missing, but a backup exists. Restore workspace.xml.bak first.");
      }
      return Workspace.fresh();
    }
    var p = new Properties();
    try (var input = Files.newInputStream(file())) {
      p.loadFromXML(input);
    }
    try {
      if (!"1".equals(required(p, "version"))) {
        throw new IllegalArgumentException("Unsupported workspace version");
      }
      var collections = new ArrayList<Workspace.Collection>();
      var notes = new ArrayList<Workspace.Note>();
      var tasks = new ArrayList<Workspace.Todo>();
      var open = new ArrayList<String>();
      for (int i = 0; i < count(p, "collections"); i++) {
        var k = "collection." + i + ".";
        collections.add(new Workspace.Collection(required(p, k + "id"), required(p, k + "name")));
      }
      for (int i = 0; i < count(p, "notes"); i++) {
        var k = "note." + i + ".";
        notes.add(
            new Workspace.Note(
                required(p, k + "id"),
                required(p, k + "title"),
                required(p, k + "collection"),
                required(p, k + "markdown")));
      }
      for (int i = 0; i < count(p, "tasks"); i++) {
        var k = "task." + i + ".";
        tasks.add(
            new Workspace.Todo(
                required(p, k + "id"),
                required(p, k + "text"),
                bool(p, k + "done"),
                p.getProperty(k + "sourceNote", ""),
                p.getProperty(k + "sourceItem", ""),
                p.getProperty(k + "markdown", "")));
      }
      for (int i = 0; i < count(p, "open"); i++) {
        open.add(required(p, "open." + i));
      }
      var result =
          new Workspace(
              notes,
              collections,
              tasks,
              open,
              required(p, "selected"),
              bool(p, "sidebarRight"),
              optionalNumber(p, "sidebarWidth", Workspace.DEFAULT_SIDEBAR_WIDTH),
              optionalNumber(p, "sidebarTextSize", Workspace.DEFAULT_SIDEBAR_TEXT_SIZE),
              optionalNumber(p, "editorFontSize", Workspace.DEFAULT_EDITOR_FONT_SIZE),
              collections.stream()
                      .anyMatch(c -> c.id().equals(p.getProperty("activeCollection", "")))
                  ? p.getProperty("activeCollection", "")
                  : "");
      validate(result);
      return result;
    } catch (IllegalArgumentException e) {
      throw new IOException(
          "Cannot read workspace.xml: "
              + e.getMessage()
              + ". Your files have not been overwritten.",
          e);
    }
  }

  public synchronized void save(Workspace workspace) throws IOException {
    if (!lock.isValid()) {
      throw new IOException("This notebook has been closed.");
    }
    validate(workspace);
    var p = new Properties();
    p.setProperty("version", "1");
    p.setProperty("collections", "" + workspace.collections().size());
    p.setProperty("notes", "" + workspace.notes().size());
    p.setProperty("tasks", "" + workspace.tasks().size());
    p.setProperty("open", "" + workspace.openTabs().size());
    p.setProperty("selected", workspace.selectedTab());
    p.setProperty("activeCollection", workspace.activeCollectionId());
    p.setProperty("sidebarRight", "" + workspace.sidebarRight());
    p.setProperty("sidebarWidth", Double.toString(workspace.sidebarWidth()));
    p.setProperty("sidebarTextSize", Double.toString(workspace.sidebarTextSize()));
    p.setProperty("editorFontSize", Double.toString(workspace.editorFontSize()));
    for (int i = 0; i < workspace.collections().size(); i++) {
      var c = workspace.collections().get(i);
      var k = "collection." + i + ".";
      p.setProperty(k + "id", c.id());
      p.setProperty(k + "name", c.name());
    }
    for (int i = 0; i < workspace.notes().size(); i++) {
      var n = workspace.notes().get(i);
      var k = "note." + i + ".";
      p.setProperty(k + "id", n.id());
      p.setProperty(k + "title", n.title());
      p.setProperty(k + "collection", n.collectionId());
      p.setProperty(k + "markdown", n.markdown());
    }
    for (int i = 0; i < workspace.tasks().size(); i++) {
      var t = workspace.tasks().get(i);
      var k = "task." + i + ".";
      p.setProperty(k + "id", t.id());
      p.setProperty(k + "text", t.text());
      p.setProperty(k + "done", "" + t.done());
      if (!t.markdown().isEmpty()) {
        p.setProperty(k + "markdown", t.markdown());
      }
      if (t.linked()) {
        p.setProperty(k + "sourceNote", t.sourceNoteId());
        p.setProperty(k + "sourceItem", t.sourceItemId());
      }
    }
    for (int i = 0; i < workspace.openTabs().size(); i++) {
      p.setProperty("open." + i, workspace.openTabs().get(i));
    }
    var temp = Files.createTempFile(directory, "workspace-", ".tmp");
    try {
      try (var out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        p.storeToXML(
            Channels.newOutputStream(out), "JavaFX Notes workspace — UTF-8 Markdown", "UTF-8");
        out.force(true);
      }
      if (Files.exists(file())) {
        Files.copy(file(), directory.resolve("workspace.xml.bak"), REPLACE_EXISTING);
      }
      try {
        Files.move(temp, file(), ATOMIC_MOVE, REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file(), REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void validate(Workspace w) {
    var collectionIds = unique(w.collections().stream().map(Workspace.Collection::id).toList());
    var noteIds = unique(w.notes().stream().map(Workspace.Note::id).toList());
    var taskIds = unique(w.tasks().stream().map(Workspace.Todo::id).toList());
    if (w.activeCollectionId() == null
        || (!w.activeCollectionId().isEmpty() && !collectionIds.contains(w.activeCollectionId()))) {
      throw new IllegalArgumentException("Unknown active collection");
    }
    unique(w.openTabs());
    if (collectionIds.isEmpty()) {
      throw new IllegalArgumentException("At least one collection is required");
    }
    if (w.collections().stream().anyMatch(c -> c.name().isBlank())) {
      throw new IllegalArgumentException("Empty collection name");
    }
    if (w.notes().stream().anyMatch(n -> !collectionIds.contains(n.collectionId()))) {
      throw new IllegalArgumentException("Unknown collection");
    }
    var tabIds = new HashSet<>(noteIds);
    for (var taskId : taskIds) {
      tabIds.add(Workspace.taskTabId(taskId));
    }
    if (!tabIds.containsAll(w.openTabs())) {
      throw new IllegalArgumentException("Unknown open tab");
    }
    if (!w.selectedTab().isEmpty() && !w.openTabs().contains(w.selectedTab())) {
      throw new IllegalArgumentException("Unknown selected tab");
    }
  }

  private static Set<String> unique(List<String> values) {
    var result = new HashSet<String>();
    for (var value : values) {
      if (value.isBlank() || !result.add(value)) {
        throw new IllegalArgumentException("Empty or duplicate identifier");
      }
    }
    return result;
  }

  private static String required(Properties p, String key) {
    var value = p.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing " + key);
    }
    return value;
  }

  private static int count(Properties p, String key) {
    int count = Integer.parseInt(required(p, key));
    if (count < 0 || count > 100_000) {
      throw new IllegalArgumentException("Invalid " + key + " count");
    }
    return count;
  }

  /** Optional appearance preferences must never prevent access to the notebook. */
  private static double optionalNumber(Properties p, String key, double fallback) {
    var value = p.getProperty(key);
    if (value == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static boolean bool(Properties p, String key) {
    var value = required(p, key);
    if (!value.equals("true") && !value.equals("false")) {
      throw new IllegalArgumentException("Invalid " + key);
    }
    return Boolean.parseBoolean(value);
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      if (lock.isValid()) {
        lock.release();
      }
    } finally {
      lockChannel.close();
    }
  }
}
