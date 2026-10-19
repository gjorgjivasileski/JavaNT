package dev.gorgi.notes.model;

import java.util.List;
import java.util.UUID;

/** Immutable snapshots cross the boundary between the UI and storage thread. */
public record Workspace(
    List<Note> notes,
    List<Collection> collections,
    List<Todo> tasks,
    List<String> openTabs,
    String selectedTab,
    boolean sidebarRight,
    double sidebarWidth,
    double sidebarTextSize,
    double editorFontSize,
    String activeCollectionId) {
  public static final double DEFAULT_SIDEBAR_WIDTH = 1.0 / 6;
  public static final double MIN_SIDEBAR_WIDTH = .10;
  public static final double MAX_SIDEBAR_WIDTH = .70;
  public static final double DEFAULT_SIDEBAR_TEXT_SIZE = 6;
  public static final double MIN_SIDEBAR_TEXT_SIZE = 6;
  public static final double MAX_SIDEBAR_TEXT_SIZE = 18;
  public static final double DEFAULT_EDITOR_FONT_SIZE = 14;
  public static final double MIN_EDITOR_FONT_SIZE = 8;
  public static final double MAX_EDITOR_FONT_SIZE = 40;

  public Workspace {
    notes = List.copyOf(notes);
    collections = List.copyOf(collections);
    tasks = List.copyOf(tasks);
    openTabs = List.copyOf(openTabs);
    sidebarWidth =
        normalizedSetting(
            sidebarWidth, DEFAULT_SIDEBAR_WIDTH, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH);
    sidebarTextSize =
        normalizedSetting(
            sidebarTextSize,
            DEFAULT_SIDEBAR_TEXT_SIZE,
            MIN_SIDEBAR_TEXT_SIZE,
            MAX_SIDEBAR_TEXT_SIZE);
    editorFontSize =
        normalizedSetting(
            editorFontSize, DEFAULT_EDITOR_FONT_SIZE, MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE);
  }

  public Workspace(
      List<Note> notes,
      List<Collection> collections,
      List<Todo> tasks,
      List<String> openTabs,
      String selectedTab,
      boolean sidebarRight,
      double sidebarWidth,
      double sidebarTextSize,
      double editorFontSize) {
    this(
        notes,
        collections,
        tasks,
        openTabs,
        selectedTab,
        sidebarRight,
        sidebarWidth,
        sidebarTextSize,
        editorFontSize,
        "");
  }

  public Workspace(
      List<Note> notes,
      List<Collection> collections,
      List<Todo> tasks,
      List<String> openTabs,
      String selectedTab,
      boolean sidebarRight,
      double sidebarWidth,
      double sidebarTextSize) {
    this(
        notes,
        collections,
        tasks,
        openTabs,
        selectedTab,
        sidebarRight,
        sidebarWidth,
        sidebarTextSize,
        DEFAULT_EDITOR_FONT_SIZE);
  }

  public Workspace(
      List<Note> notes,
      List<Collection> collections,
      List<Todo> tasks,
      List<String> openTabs,
      String selectedTab,
      boolean sidebarRight) {
    this(
        notes,
        collections,
        tasks,
        openTabs,
        selectedTab,
        sidebarRight,
        DEFAULT_SIDEBAR_WIDTH,
        DEFAULT_SIDEBAR_TEXT_SIZE,
        DEFAULT_EDITOR_FONT_SIZE);
  }

  private static double normalizedSetting(double value, double fallback, double min, double max) {
    return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
  }

  /** Compatibility aliases for snapshots created before task tabs were introduced. */
  public List<String> openNotes() {
    return openTabs;
  }

  public String selectedNote() {
    return selectedTab;
  }

  public static String taskTabId(String id) {
    return "task:" + id;
  }

  public record Note(String id, String title, String collectionId, String markdown) {}

  public record Collection(String id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** Tasks have their own identity and may optionally follow one checkbox in a note. */
  public record Todo(
      String id,
      String text,
      boolean done,
      String sourceNoteId,
      String sourceItemId,
      String markdown) {
    public Todo {
      markdown = markdown == null ? "" : markdown;
      sourceNoteId = sourceNoteId == null ? "" : sourceNoteId;
      sourceItemId = sourceItemId == null ? "" : sourceItemId;
      if (sourceNoteId.isEmpty() || sourceItemId.isEmpty()) {
        sourceNoteId = "";
        sourceItemId = "";
      }
    }

    public Todo(String id, String text, boolean done) {
      this(id, text, done, "", "", "");
    }

    public Todo(String id, String text, boolean done, String sourceNoteId, String sourceItemId) {
      this(id, text, done, sourceNoteId, sourceItemId, "");
    }

    public boolean linked() {
      return !sourceNoteId.isEmpty();
    }

    public Todo withMarkdown(String value) {
      return new Todo(id, text, done, sourceNoteId, sourceItemId, value);
    }

    public Todo withoutLink() {
      return new Todo(id, text, done, "", "", markdown);
    }
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static Workspace fresh() {
    var inbox = new Collection(id(), "Inbox");
    var note =
        new Note(
            id(),
            "A little space to think",
            inbox.id(),
            """
            # A little space to think

            Welcome to your notebook. A quiet home for ideas, plans, and the things you don't want to forget.

            ## Make yourself at home

            - Create a note with **Ctrl+N**.
            - Group related notes into **collections**.
            - Keep your next steps in the task sidebar.
            - Move the sidebar using **View → Sidebar on right**.

            > Write on the left. See your words take shape on the right.

            ## Your first idea

            Start here. Everything is saved automatically on this device.

            ```java
            var idea = "Something worth remembering";
            ```

            - [ ] Write something today
            - [ ] Make room for the next idea
            """);
    return new Workspace(
        List.of(note), List.of(inbox), List.of(), List.of(note.id()), note.id(), false);
  }
}
