package dev.gorgi.notes;

import dev.gorgi.notes.editor.EmbeddedImage;
import dev.gorgi.notes.editor.NoteEditor;
import dev.gorgi.notes.markdown.TaskLinks;
import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.storage.WorkspaceStore;
import dev.gorgi.notes.theme.Theme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/** JavaFX application for a local Markdown notebook and linked tasks. */
public final class NotesApp extends Application {
  private static final Workspace.Collection ALL_NOTES = new Workspace.Collection("", "All notes");
  private final ObservableList<Workspace.Note> notes = FXCollections.observableArrayList();
  private final ObservableList<Workspace.Collection> collections =
      FXCollections.observableArrayList();
  private final ObservableList<Workspace.Todo> tasks = FXCollections.observableArrayList();
  private final FilteredList<Workspace.Todo> visibleTasks = new FilteredList<>(tasks);
  private final Map<String, NoteEditor> editors = new HashMap<>();
  // Keep hidden collection tabs alive so their drafts, caret positions, and open state survive.
  private final Map<String, Tab> documentTabs = new LinkedHashMap<>();
  private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService rendering = Executors.newVirtualThreadPerTaskExecutor();
  private final PauseTransition autosave = new PauseTransition(Duration.millis(650));
  private final PauseTransition searchDelay = new PauseTransition(Duration.millis(220));
  private WorkspaceStore store;
  private Workspace initial;
  private Exception startupError;
  private Theme theme;
  private Stage stage;
  private BorderPane root;
  private SplitPane body;
  private VBox sidebar;
  private final TabPane tabs = new TabPane();
  private StackPane notebookPane;
  private final Menu collectionsMenu = new Menu("Collections");
  private final ListView<Workspace.Todo> taskList = new ListView<>(visibleTasks);
  private final ListView<Workspace.Note> noteList = new ListView<>();
  private final TextField search = new TextField();
  private final ComboBox<Workspace.Collection> headerCollection = new ComboBox<>();
  private final Label saveStatus = new Label("Opening notebook…");
  private final Label taskCount = new Label();
  private final Label libraryCount = new Label();
  private String collectionFilter = "";
  private boolean sidebarRight;
  private boolean starting = true;
  private boolean closing;
  private int openDialogs;
  private boolean restoreFullscreenAfterDialogs;
  private boolean compact;
  private boolean compactSidebar;
  private boolean sidebarHidden;
  private boolean updatingHeaderCollection;
  private boolean updatingCollectionTabs;
  private Label subtitle;
  private Label workspaceLabel;
  private Label brand;
  private HBox appHeader;
  private Button sidebarToggle;
  private Button newNoteButton;
  private double sidebarFraction = Workspace.DEFAULT_SIDEBAR_WIDTH;
  private double sidebarTextSize = Workspace.DEFAULT_SIDEBAR_TEXT_SIZE;
  private double editorFontSize = Workspace.DEFAULT_EDITOR_FONT_SIZE;
  private SidebarSize sidebarBeforePreview;
  private boolean restoringSidebarSize;
  private long sidebarResizeRevision;
  private double textScale = 1;
  private long revision;

  /**
   * Launches the application.
   *
   * @param args command-line arguments passed to JavaFX
   */
  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void init() {
    try {
      String dataArgument =
          getParameters() == null ? null : getParameters().getNamed().get("data-dir");
      var directory =
          dataArgument == null ? WorkspaceStore.defaultDirectory() : Path.of(dataArgument);
      store = new WorkspaceStore(directory);
      initial = store.load();
      theme = Theme.load();
    } catch (Exception e) {
      startupError = e;
    }
  }

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    if (startupError != null) {
      var alert = new Alert(Alert.AlertType.ERROR, startupError.getMessage(), ButtonType.CLOSE);
      alert.setHeaderText("The notebook could not be opened");
      alert.showAndWait();
      Platform.exit();
      return;
    }
    notes.setAll(initial.notes());
    collections.setAll(initial.collections());
    tasks.setAll(initial.tasks());
    for (var sourceId :
        tasks.stream()
            .filter(Workspace.Todo::linked)
            .map(Workspace.Todo::sourceNoteId)
            .distinct()
            .toList()) {
      var markdown = documentMarkdown(sourceId);
      tasks.setAll(TaskLinks.reconcile(sourceId, markdown == null ? "" : markdown, tasks));
    }
    sidebarRight = initial.sidebarRight();
    sidebarFraction = initial.sidebarWidth();
    sidebarTextSize = initial.sidebarTextSize();
    editorFontSize = Math.round(initial.editorFontSize());
    root = new BorderPane();
    root.getStyleClass().add("app");
    applyAppStyle();
    root.setTop(new VBox(menu(), header()));
    tabs.setId("note-tabs");
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
    tabs.getTabs()
        .addListener(
            (ListChangeListener<Tab>)
                change -> {
                  while (change.next()) {
                    if (change.wasPermutated() && !updatingCollectionTabs) {
                      rememberTabOrder();
                      changed();
                    }
                  }
                });
    tabs.addEventHandler(MouseEvent.MOUSE_CLICKED, this::renameDoubleClickedTab);
    tabs.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, old, selected) -> {
              if (old != null) {
                var previousEditor = editors.get((String) old.getUserData());
                if (previousEditor != null) {
                  previousEditor.flushPendingEdits();
                }
              }
              if (selected != null) {
                var id = (String) selected.getUserData();
                stage.setTitle(documentTitle(id) + " — JavaFX Notes");
                var note = findNote(id);
                if (noteList.getItems().contains(note)) {
                  noteList.getSelectionModel().select(note);
                } else {
                  noteList.getSelectionModel().clearSelection();
                }
              } else {
                stage.setTitle("JavaFX Notes");
              }
              renderTasks();
              refreshHeaderCollection();
              changed();
            });
    var emptyTitle = new Label("Room for your next idea.");
    emptyTitle.getStyleClass().add("empty-title");
    emptyTitle.setWrapText(true);
    var emptyHint = new Label("Open a note or task from the sidebar, or start a new note.");
    emptyHint.setWrapText(true);
    emptyHint.setMaxWidth(400);
    var empty = new VBox(14, emptyTitle, emptyHint, button("+  New note", this::newNote));
    empty.setPadding(new Insets(20));
    empty.getStyleClass().add("empty-notebook");
    empty.setAlignment(Pos.CENTER);
    empty.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(tabs.getTabs()));
    empty.managedProperty().bind(empty.visibleProperty());
    notebookPane = new StackPane(tabs, empty);
    notebookPane.setMinWidth(0);
    sidebar = sidebar();
    applySidebarAppearance();
    body = new SplitPane();
    root.setCenter(body);
    configureSidebarSizing();
    arrangeSidebar();
    var bottomSpacer = new Region();
    HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
    workspaceLabel = new Label("LOCAL WORKSPACE");
    var bottom =
        new HBox(12, new Label("●"), saveStatus, bottomSpacer, workspaceLabel, libraryCount);
    bottom.getStyleClass().add("app-status");
    root.setBottom(bottom);
    var scene = new Scene(root, 1320, 850);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm());
    stage.setScene(scene);
    stage.setMinWidth(420);
    stage.setMinHeight(460);
    stage.setTitle("JavaFX Notes");
    scene.widthProperty().addListener((obs, old, width) -> adaptWidth(width.doubleValue()));
    scene.heightProperty().addListener((obs, old, height) -> adaptHeight(height.doubleValue()));
    for (var id : initial.openTabs()) {
      openDocument(id);
    }
    if (!initial.selectedTab().isEmpty()) {
      openDocument(initial.selectedTab());
    }
    selectCollection(initial.activeCollectionId());
    renderCollections();
    refreshNotes();
    renderTasks();
    autosave.setOnFinished(e -> save(null, false));
    searchDelay.setOnFinished(e -> refreshNotes());
    search.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());
    stage.setOnCloseRequest(
        e -> {
          e.consume();
          requestClose();
        });
    stage.show();
    adaptWidth(scene.getWidth());
    adaptHeight(scene.getHeight());
    starting = false;
    changed();
    io.scheduleWithFixedDelay(
        () -> {
          Theme next = Theme.load();
          Platform.runLater(
              () -> {
                if (!closing && !next.equals(theme)) {
                  applyTheme(next);
                }
              });
        },
        4,
        4,
        TimeUnit.SECONDS);
  }

  private MenuBar menu() {
    var file = new Menu("File");
    file.getItems()
        .addAll(
            item("New note", "Shortcut+N", this::newNote),
            new SeparatorMenuItem(),
            item("Import Markdown…", "Shortcut+O", this::importNote),
            item("Export Markdown…", "Shortcut+Shift+S", this::exportNote),
            item("Save now", "Shortcut+S", () -> save(null, true)),
            new SeparatorMenuItem(),
            item("Close tab", "Shortcut+W", this::closeSelected),
            item("Rename…", "F2", () -> renameDocument(selectedId())),
            item("Delete…", null, this::deleteDocument),
            new SeparatorMenuItem(),
            item("Quit", "Shortcut+Q", this::requestClose));
    var edit = new Menu("Edit");
    edit.getItems()
        .addAll(
            item("Undo", "Shortcut+Z", () -> editText("undo")),
            item("Redo", "Shortcut+Shift+Z", () -> editText("redo")),
            new SeparatorMenuItem(),
            item("Cut", "Shortcut+X", () -> editText("cut")),
            item("Copy", "Shortcut+C", () -> editText("copy")),
            item("Paste", "Shortcut+V", () -> editText("paste")),
            item("Select all", "Shortcut+A", () -> editText("all")),
            new SeparatorMenuItem(),
            item(
                "Find notes",
                "Shortcut+K",
                () -> {
                  search.requestFocus();
                  search.selectAll();
                }));
    var view = new Menu("View");
    var right = new CheckMenuItem("Sidebar on right");
    right.setSelected(sidebarRight);
    right.setOnAction(
        e -> {
          sidebarRight = right.isSelected();
          arrangeSidebar();
          changed();
        });
    view.getItems()
        .addAll(
            right,
            item("Toggle notes & tasks", "Shortcut+L", this::toggleSidebar),
            item("Sidebar size…", null, this::sidebarSizeDialog),
            new SeparatorMenuItem(),
            item("Larger text", "Shortcut+Equals", () -> setTextScale(textScale + .1)),
            item("Smaller text", "Shortcut+Minus", () -> setTextScale(textScale - .1)),
            item("Reset text size", "Shortcut+0", () -> setTextScale(1)),
            new SeparatorMenuItem(),
            item("Full screen", "F11", () -> stage.setFullScreen(!stage.isFullScreen())),
            item(
                "Reload Omarchy theme",
                null,
                () ->
                    io.execute(
                        () -> {
                          var next = Theme.load();
                          Platform.runLater(() -> applyTheme(next));
                        })));
    var help = new Menu("Help");
    help.getItems()
        .addAll(
            item(
                "Markdown & shortcuts",
                null,
                () ->
                    info(
                        "Make yourself at home",
                        "Ctrl+N  New note\n"
                            + "Ctrl+K  Find notes\n"
                            + "Ctrl+S  Save now\n"
                            + "Ctrl+W  Close tab\n"
                            + "Ctrl+B / Ctrl+I  Bold / italic in the editor\n\n"
                            + "# Heading\n"
                            + "**bold** and *italic*\n"
                            + "- Bullet list\n"
                            + "- [ ] Checklist item\n"
                            + "[text](https://example.com)\n"
                            + "`inline code` and fenced code blocks\n\n"
                            + "Tables and strikethrough (~~text~~) are supported.\n"
                            + "Remote images and raw HTML are disabled in the preview.")),
            item(
                "About JavaFX Notes",
                null,
                () ->
                    info(
                        "JavaFX Notes",
                        "A quiet home for notes and next steps.\n"
                            + "Java 26 · JavaFX · Markdown\n\n"
                            + "Saved on this device:\n"
                            + store.directory()
                            + "\n\n"
                            + "The app follows your active Omarchy colors and terminal"
                            + " font.")));
    return new MenuBar(file, edit, view, collectionsMenu, imagesMenu(), help);
  }

  private Menu imagesMenu() {
    var menu = new Menu("Images");
    var insert = item("Insert image…", "Shortcut+Shift+I", this::insertImage);
    insert.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(tabs.getTabs()));
    var select = new Menu("Select image");
    var inline = new RadioMenuItem("Inline with text");
    var floating = new RadioMenuItem("Floating");
    var modes = new ToggleGroup();
    inline.setToggleGroup(modes);
    floating.setToggleGroup(modes);
    inline.setOnAction(event -> editImage(editor -> editor.setSelectedImageFloating(false)));
    floating.setOnAction(event -> editImage(editor -> editor.setSelectedImageFloating(true)));
    var front = item("Bring to front", null, () -> editImage(NoteEditor::bringImageToFront));
    var back = item("Send to back", null, () -> editImage(NoteEditor::sendImageToBack));
    var delete = item("Delete image", null, () -> editImage(NoteEditor::deleteSelectedImage));
    menu.getItems()
        .addAll(
            insert,
            select,
            new SeparatorMenuItem(),
            inline,
            floating,
            new SeparatorMenuItem(),
            front,
            back,
            delete);
    menu.setOnShowing(
        event -> {
          var editor = editors.get(selectedId());
          boolean selected = editor != null && editor.hasSelectedImage();
          inline.setDisable(!selected);
          floating.setDisable(!selected);
          front.setDisable(!selected);
          back.setDisable(!selected);
          delete.setDisable(!selected);
          modes.selectToggle(
              selected ? (editor.isSelectedImageFloating() ? floating : inline) : null);
          select.getItems().clear();
          if (editor != null) {
            for (var image : editor.imageChoices()) {
              var choice = item(image.name(), null, () -> editor.selectImage(image.id()));
              choice.setMnemonicParsing(false);
              select.getItems().add(choice);
            }
          }
          select.setDisable(select.getItems().isEmpty());
        });
    return menu;
  }

  private void editImage(Consumer<NoteEditor> action) {
    var editor = editors.get(selectedId());
    if (editor != null && editor.hasSelectedImage()) {
      action.accept(editor);
      editor.flushPendingEdits();
    }
  }

  private void insertImage() {
    String id = selectedId();
    var editor = editors.get(id);
    if (editor == null) {
      return;
    }
    editor.flushPendingEdits();
    var chooser = new FileChooser();
    chooser.setTitle("Insert image");
    chooser
        .getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(
                "Images (PNG, JPEG, GIF)",
                "*.png",
                "*.jpg",
                "*.jpeg",
                "*.gif",
                "*.PNG",
                "*.JPG",
                "*.JPEG",
                "*.GIF"));
    // Use the same fullscreen workaround as the app's other dialogs.
    boolean fullscreen = stage.isFullScreen();
    if (fullscreen) {
      stage.setFullScreen(false);
    }
    java.io.File file;
    try {
      file = chooser.showOpenDialog(stage);
    } finally {
      if (fullscreen && !closing && stage.isShowing()) {
        stage.setFullScreen(true);
      }
    }
    if (file == null) {
      return;
    }
    rendering.execute(
        () -> {
          try {
            var image = EmbeddedImage.read(file.toPath());
            Platform.runLater(
                () -> {
                  if (!closing && editors.get(id) == editor) {
                    editor.insertImage(image);
                  }
                });
          } catch (IOException | IllegalArgumentException exception) {
            Platform.runLater(
                () -> {
                  if (!closing) {
                    error("Image could not be inserted", exception);
                  }
                });
          }
        });
  }

  private HBox header() {
    var icon = entityIcon(false);
    brand = new Label("NOTES");
    brand.getStyleClass().addAll("brand", "note-label");
    brand.setMinWidth(Region.USE_PREF_SIZE);
    subtitle = new Label("A little space to think.");
    subtitle.getStyleClass().add("muted");
    sidebarToggle = button("☰", this::toggleSidebar);
    sidebarToggle.setTooltip(new Tooltip("Notes and tasks"));
    sidebarToggle.setAccessibleText("Show notes and tasks");
    headerCollection.setId("note-collection");
    headerCollection.setAccessibleText("Active collection");
    headerCollection.setTooltip(new Tooltip("Choose the collection to browse"));
    headerCollection.setPromptText("Collection");
    headerCollection.setMinWidth(76);
    headerCollection.setPrefWidth(150);
    headerCollection.setMaxWidth(180);
    headerCollection
        .valueProperty()
        .addListener(
            (obs, old, value) -> {
              if (updatingHeaderCollection || value == null) {
                return;
              }
              selectCollection(value.id());
            });
    refreshHeaderCollection();
    var spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    search.setPromptText("Find a note…");
    search.setPrefWidth(130);
    search.setId("search-notes");
    search.setTooltip(new Tooltip("Find a note · Ctrl+K"));
    search.setAccessibleText("Search notes by title or content");
    search.setMinWidth(50);
    search.setMaxWidth(140);
    HBox.setHgrow(search, Priority.ALWAYS);
    newNoteButton = button("+  New note", this::newNote);
    newNoteButton.getStyleClass().add("primary");
    newNoteButton.setId("new-note");
    newNoteButton.setMinWidth(Region.USE_PREF_SIZE);
    newNoteButton.setAccessibleText("New note");
    appHeader =
        new HBox(
            14,
            sidebarToggle,
            icon,
            brand,
            subtitle,
            spacer,
            headerCollection,
            search,
            newNoteButton);
    appHeader.setAlignment(Pos.CENTER_LEFT);
    appHeader.getStyleClass().add("app-header");
    return appHeader;
  }

  private void refreshHeaderCollection() {
    updatingHeaderCollection = true;
    try {
      headerCollection.getItems().setAll(ALL_NOTES);
      headerCollection.getItems().addAll(collections);
      headerCollection.setValue(
          collections.stream()
              .filter(c -> c.id().equals(collectionFilter))
              .findFirst()
              .orElse(ALL_NOTES));
    } finally {
      updatingHeaderCollection = false;
    }
  }

  private VBox sidebar() {
    noteList.setId("notes-list");
    noteList.setPrefHeight(0);
    noteList.setPlaceholder(new Label("No notes here yet"));
    noteList.setCellFactory(
        list ->
            new ListCell<>() {
              private final Label name = new Label();
              private final Label preview = new Label();
              private final HBox heading = new HBox(3.5, entityIcon(false, .5), name);
              private final VBox content = new VBox(2.5, heading, preview);
              private final Menu move = moveNoteMenu("Move to collection…", this::getItem);
              private final ContextMenu menu = new ContextMenu(move);

              {
                name.getStyleClass().add("note-name");
                name.setMinWidth(0);
                name.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(name, Priority.ALWAYS);
                heading.setAlignment(Pos.CENTER_LEFT);
                preview.getStyleClass().add("note-excerpt");
                preview.setMinWidth(0);
                preview.setMaxWidth(Double.MAX_VALUE);
                content.prefWidthProperty().bind(widthProperty().subtract(12));
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                menu.setOnShowing(event -> populateMoveNoteMenu(move, getItem()));
              }

              @Override
              protected void updateItem(Workspace.Note note, boolean empty) {
                super.updateItem(note, empty);
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setAccessibleText(null);
                if (empty || note == null) {
                  return;
                }
                name.setText(displayTitle(note));
                preview.setText(excerpt(note.markdown()));
                setAccessibleText("Note: " + displayTitle(note));
                setGraphic(content);
                setContextMenu(menu);
              }
            });
    noteList.setOnMouseClicked(
        e -> {
          if (e.getButton() == MouseButton.PRIMARY) {
            openNote(noteList.getSelectionModel().getSelectedItem());
          }
        });
    noteList.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            openNote(noteList.getSelectionModel().getSelectedItem());
          }
        });
    var notesPanel = new VBox(5, section("NOTES", new Label("↵ to open")), noteList);
    notesPanel.getStyleClass().add("sidebar-section");
    notesPanel.setMinWidth(0);
    noteList.setMinHeight(12);
    VBox.setVgrow(noteList, Priority.ALWAYS);
    taskList.setId("tasks-list");
    taskList.setMinHeight(12);
    taskCount.getStyleClass().add("task-label");
    var emptyTasks =
        new Label(
            "Select a note tab to see its tasks. Create tasks with the checkbox toolbar button or"
                + " by clicking a checkbox in the preview.");
    emptyTasks.getStyleClass().add("empty-hint");
    emptyTasks.setWrapText(true);
    taskList.setPlaceholder(emptyTasks);
    taskList.setCellFactory(list -> new TaskCell());
    taskList.setOnKeyPressed(
        e -> {
          if (e.getCode() == KeyCode.ENTER) {
            openTask(taskList.getSelectionModel().getSelectedItem());
            e.consume();
          }
        });
    var clear = button("Clear completed", this::clearCompletedTasks);
    clear.getStyleClass().add("quiet-button");
    var taskPanel = new VBox(5, section("TASKS", taskCount), taskList, clear);
    VBox.setVgrow(taskList, Priority.ALWAYS);
    taskPanel.getStyleClass().add("sidebar-section");
    taskPanel.setMinWidth(0);
    var vertical = new SplitPane(notesPanel, taskPanel);
    vertical.setOrientation(Orientation.VERTICAL);
    vertical.setDividerPositions(.58);
    vertical.setId("sidebar-sections");
    vertical.getStyleClass().add("sidebar-sections");
    vertical.setAccessibleText("Notes and tasks sections");
    VBox.setVgrow(vertical, Priority.ALWAYS);
    vertical.setMinHeight(0);
    vertical.setMinWidth(0);
    // The percentage setting supplies the lower bound, even in smaller windows.
    var box = new VBox(vertical);
    box.getStyleClass().add("sidebar");
    box.setMinWidth(0);
    box.setPrefWidth(150);
    box.setMaxWidth(Double.MAX_VALUE);
    return box;
  }

  private void configureSidebarSizing() {
    body.getDividers()
        .addListener(
            (ListChangeListener<SplitPane.Divider>)
                change -> {
                  while (change.next()) {
                    for (var divider : change.getAddedSubList()) {
                      divider
                          .positionProperty()
                          .addListener(
                              (obs, old, position) -> {
                                if (!restoringSidebarSize && body.getItems().size() == 2) {
                                  double fraction =
                                      body.getItems().getFirst() == sidebar
                                          ? position.doubleValue()
                                          : 1 - position.doubleValue();
                                  double bounded =
                                      Math.clamp(
                                          fraction,
                                          Workspace.MIN_SIDEBAR_WIDTH,
                                          Workspace.MAX_SIDEBAR_WIDTH);
                                  if (Math.abs(fraction - bounded) > .000001) {
                                    setSidebarSize(bounded, sidebarTextSize);
                                  } else if (Math.abs(sidebarFraction - fraction) > .000001) {
                                    sidebarFraction = fraction;
                                    if (sidebarBeforePreview == null) {
                                      changed();
                                    }
                                  }
                                }
                              });
                    }
                  }
                });
    body.widthProperty()
        .addListener(
            (obs, old, width) -> {
              restoringSidebarSize = true;
              long resizeRevision = ++sidebarResizeRevision;
              Platform.runLater(
                  () -> {
                    if (resizeRevision != sidebarResizeRevision) {
                      return;
                    }
                    try {
                      if (body.getItems().size() == 2) {
                        // SplitPane distributes resize deltas equally. Finish that layout before
                        // restoring the chosen proportion, keeping both default and dragged sizes.
                        body.layout();
                        body.setDividerPositions(
                            sidebarRight ? 1 - sidebarFraction : sidebarFraction);
                        body.layout();
                      }
                    } finally {
                      restoringSidebarSize = false;
                    }
                  });
            });
  }

  private void arrangeSidebar() {
    boolean wasRestoring = restoringSidebarSize;
    restoringSidebarSize = true;
    try {
      if (compact) {
        body.getItems().setAll(compactSidebar ? sidebar : notebookPane);
        return;
      }
      if (sidebarHidden) {
        body.getItems().setAll(notebookPane);
        return;
      }
      if (sidebarRight) {
        body.getItems().setAll(notebookPane, sidebar);
      } else {
        body.getItems().setAll(sidebar, notebookPane);
      }
      body.setDividerPositions(sidebarRight ? 1 - sidebarFraction : sidebarFraction);
    } finally {
      restoringSidebarSize = wasRestoring;
    }
  }

  private void toggleSidebar() {
    if (compact) {
      compactSidebar = !compactSidebar;
    } else {
      sidebarHidden = !sidebarHidden;
    }
    arrangeSidebar();
  }

  private record SidebarSize(double width, double textSize) {}

  private void sidebarSizeDialog() {
    var previous = new SidebarSize(sidebarFraction, sidebarTextSize);
    var width =
        new Slider(
            Workspace.MIN_SIDEBAR_WIDTH * 100,
            Workspace.MAX_SIDEBAR_WIDTH * 100,
            sidebarFraction * 100);
    width.setId("sidebar-width");
    width.setAccessibleText("Sidebar width as a percentage of the window");
    width.setBlockIncrement(5);
    width.setMajorTickUnit(20);
    width.setMinorTickCount(3);
    width.setShowTickMarks(true);
    var textSize =
        new Slider(
            Workspace.MIN_SIDEBAR_TEXT_SIZE, Workspace.MAX_SIDEBAR_TEXT_SIZE, sidebarTextSize);
    textSize.setId("sidebar-text-size");
    textSize.setAccessibleText("Sidebar text size");
    textSize.setBlockIncrement(1);
    textSize.setMajorTickUnit(3);
    textSize.setMinorTickCount(2);
    textSize.setSnapToTicks(true);
    textSize.setShowTickMarks(true);
    var widthValue = new Label();
    var textValue = new Label();
    Runnable preview =
        () -> {
          widthValue.setText(String.format(Locale.ROOT, "%.1f%%", width.getValue()));
          textValue.setText(String.format(Locale.ROOT, "%.0f px", textSize.getValue()));
          setSidebarSize(width.getValue() / 100, textSize.getValue());
        };
    width.valueProperty().addListener((obs, old, value) -> preview.run());
    textSize.valueProperty().addListener((obs, old, value) -> preview.run());
    var hint =
        new Label(
            "Preview changes before applying. Width applies when the sidebar is beside the editor."
                + " Text size follows the app’s zoom setting.");
    hint.setWrapText(true);
    hint.setMinHeight(Region.USE_PREF_SIZE);
    hint.getStyleClass().add("muted");
    var content =
        new VBox(
            10,
            new HBox(12, new Label("Sidebar width"), widthValue),
            width,
            new HBox(12, new Label("Text size"), textValue),
            textSize,
            hint);
    content.setPrefWidth(340);
    var dialog = styleDialog(new Dialog<ButtonType>());
    dialog.setTitle("Sidebar size");
    dialog.setHeaderText("Adjust the sidebar");
    dialog.getDialogPane().setContent(content);
    var reset = new ButtonType("Reset", ButtonBar.ButtonData.OTHER);
    dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL, ButtonType.APPLY);
    dialog
        .getDialogPane()
        .lookupButton(reset)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            event -> {
              event.consume();
              width.setValue(Workspace.DEFAULT_SIDEBAR_WIDTH * 100);
              textSize.setValue(Workspace.DEFAULT_SIDEBAR_TEXT_SIZE);
            });
    sidebarBeforePreview = previous;
    boolean apply = false;
    try {
      preview.run();
      apply = dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY;
    } finally {
      if (!apply) {
        setSidebarSize(previous.width(), previous.textSize());
      }
      sidebarBeforePreview = null;
      changed();
    }
  }

  private void setSidebarSize(double fraction, double textSize) {
    sidebarFraction =
        Math.clamp(fraction, Workspace.MIN_SIDEBAR_WIDTH, Workspace.MAX_SIDEBAR_WIDTH);
    sidebarTextSize =
        Math.clamp(textSize, Workspace.MIN_SIDEBAR_TEXT_SIZE, Workspace.MAX_SIDEBAR_TEXT_SIZE);
    boolean wasRestoring = restoringSidebarSize;
    restoringSidebarSize = true;
    try {
      applySidebarAppearance();
      root.applyCss();
      body.layout();
      if (body.getItems().size() == 2) {
        body.setDividerPositions(sidebarRight ? 1 - sidebarFraction : sidebarFraction);
        body.layout();
      }
    } finally {
      restoringSidebarSize = wasRestoring;
    }
    if (sidebarBeforePreview == null) {
      changed();
    }
  }

  private void applySidebarAppearance() {
    sidebar.setStyle("-fx-font-size:" + (sidebarTextSize * textScale) + "px;");
    if (stage.getScene() != null) {
      adaptHeight(stage.getScene().getHeight());
    }
  }

  private void adaptWidth(double width) {
    double available = width / textScale;
    boolean nextCompact = available < 850;
    if (nextCompact != compact) {
      compact = nextCompact;
      compactSidebar = false;
      arrangeSidebar();
    }
    root.pseudoClassStateChanged(PseudoClass.getPseudoClass("compact"), available < 700);
    brand.setVisible(available >= 510);
    brand.setManaged(available >= 510);
    subtitle.setVisible(available >= 1240);
    subtitle.setManaged(available >= 1240);
    newNoteButton.setText(available < 700 ? "+" : "+  New note");
    newNoteButton.setTooltip(new Tooltip("New note · Ctrl+N"));
    search.setPrefWidth(available < 700 ? 80 : 130);
    headerCollection.setPrefWidth(available < 700 ? 100 : 150);
    appHeader.setSpacing(available < 700 ? 8 : 14);
    workspaceLabel.setVisible(available >= 1000);
    workspaceLabel.setManaged(available >= 1000);
    libraryCount.setVisible(available >= 620);
    libraryCount.setManaged(available >= 620);
  }

  private void adaptHeight(double height) {
    boolean shortWindow = height / textScale < 670;
    root.pseudoClassStateChanged(PseudoClass.getPseudoClass("short"), shortWindow);
  }

  private void renderCollections() {
    var group = new ToggleGroup();
    collectionsMenu
        .getItems()
        .setAll(
            item("New collection", "Shortcut+Shift+N", this::newCollection),
            new SeparatorMenuItem(),
            collectionFilterItem("All notes", "", group));
    var rename = new Menu("Rename collection…");
    var delete = new Menu("Delete collection…");
    var move = moveNoteMenu("Move note to collection…", () -> findNote(selectedId()));
    collectionsMenu.setOnShowing(event -> populateMoveNoteMenu(move, findNote(selectedId())));
    delete.setDisable(collections.size() <= 1);
    for (var c : collections) {
      collectionsMenu.getItems().add(collectionFilterItem(c.name(), c.id(), group));
      var renameItem = item(c.name(), null, () -> renameCollection(c));
      var deleteItem = item(c.name(), null, () -> deleteCollection(c));
      renameItem.setMnemonicParsing(false);
      deleteItem.setMnemonicParsing(false);
      rename.getItems().add(renameItem);
      delete.getItems().add(deleteItem);
    }
    collectionsMenu.getItems().addAll(new SeparatorMenuItem(), rename, delete, move);
    refreshHeaderCollection();
  }

  private RadioMenuItem collectionFilterItem(String label, String id, ToggleGroup group) {
    var item = new RadioMenuItem(label);
    item.setMnemonicParsing(false);
    item.setToggleGroup(group);
    item.setSelected(collectionFilter.equals(id));
    item.setOnAction(
        e -> {
          selectCollection(id);
        });
    return item;
  }

  private void selectCollection(String id) {
    if (id == null || (!id.isEmpty() && collections.stream().noneMatch(c -> c.id().equals(id)))) {
      return;
    }
    var editor = editors.get(selectedId());
    if (editor != null) {
      editor.flushPendingEdits();
    }
    collectionFilter = id;
    refreshCollectionTabs();
    renderCollections();
    refreshNotes();
    changed();
  }

  private void refreshCollectionTabs() {
    if (updatingCollectionTabs) {
      return;
    }
    updatingCollectionTabs = true;
    try {
      var selected = tabs.getSelectionModel().getSelectedItem();
      var visible =
          documentTabs.entrySet().stream()
              .filter(
                  entry -> {
                    var note = findNote(entry.getKey());
                    return note == null
                        || collectionFilter.isEmpty()
                        || note.collectionId().equals(collectionFilter);
                  })
              .map(Map.Entry::getValue)
              .toList();
      if (!tabs.getTabs().equals(visible)) {
        tabs.getTabs().setAll(visible);
      }
      if (selected != null && visible.contains(selected)) {
        tabs.getSelectionModel().select(selected);
      }
    } finally {
      updatingCollectionTabs = false;
    }
  }

  private void rememberTabOrder() {
    var visibleTabs = tabs.getTabs().stream().collect(Collectors.toSet());
    var nextVisible = tabs.getTabs().iterator();
    var reordered = new LinkedHashMap<String, Tab>();
    // Replace only the visible slots so hidden collections retain their relative tab order.
    for (var original : documentTabs.values()) {
      var tab = visibleTabs.contains(original) ? nextVisible.next() : original;
      reordered.put((String) tab.getUserData(), tab);
    }
    documentTabs.clear();
    documentTabs.putAll(reordered);
  }

  private Menu moveNoteMenu(String label, Supplier<Workspace.Note> note) {
    var menu = new Menu(label);
    menu.setOnShowing(event -> populateMoveNoteMenu(menu, note.get()));
    return menu;
  }

  private void populateMoveNoteMenu(Menu menu, Workspace.Note note) {
    menu.getItems().clear();
    menu.setDisable(note == null || collections.size() <= 1);
    if (note == null) {
      return;
    }
    for (var collection : collections) {
      var destination = item(collection.name(), null, () -> moveNote(note.id(), collection.id()));
      destination.setMnemonicParsing(false);
      destination.setDisable(collection.id().equals(note.collectionId()));
      menu.getItems().add(destination);
    }
  }

  private void moveNote(String id, String collectionId) {
    var note = findNote(id);
    if (note == null
        || note.collectionId().equals(collectionId)
        || collections.stream().noneMatch(c -> c.id().equals(collectionId))) {
      return;
    }
    var editor = editors.get(id);
    if (editor != null) {
      editor.setCollection(collectionId);
    } else {
      notes.set(
          notes.indexOf(note),
          new Workspace.Note(note.id(), note.title(), collectionId, note.markdown()));
    }
    refreshCollectionTabs();
    renderCollections();
    refreshNotes();
    changed();
  }

  private void refreshNotes() {
    var selected = selectedId();
    var query = search.getText().strip().toLowerCase(Locale.ROOT);
    noteList
        .getItems()
        .setAll(
            notes.stream()
                .filter(
                    n -> collectionFilter.isEmpty() || n.collectionId().equals(collectionFilter))
                .filter(
                    n ->
                        query.isEmpty()
                            || (n.title() + "\n" + n.markdown())
                                .toLowerCase(Locale.ROOT)
                                .contains(query))
                .toList());
    var note = findNote(selected);
    if (noteList.getItems().contains(note)) {
      noteList.getSelectionModel().select(note);
    } else {
      noteList.getSelectionModel().clearSelection();
    }
    libraryCount.setText(notes.size() + " notes  /  " + collections.size() + " collections");
  }

  private void renderTasks() {
    var focusedDocument = selectedId();
    visibleTasks.setPredicate(
        task -> !task.linked() || focusedDocument.equals(task.sourceNoteId()));
    selectVisibleTask(focusedDocument);
    long done = visibleTasks.stream().filter(Workspace.Todo::done).count();
    taskCount.setText(done + "/" + visibleTasks.size());
  }

  private void selectVisibleTask(String id) {
    var task = taskForTab(id);
    if (visibleTasks.contains(task)) {
      taskList.getSelectionModel().select(task);
    } else {
      taskList.getSelectionModel().clearSelection();
    }
  }

  private void replaceTask(String id, String text, Boolean done) {
    // Commit pending rendered edits before applying an action from the sidebar.
    editors.values().forEach(NoteEditor::flushPendingEdits);
    var previous = findTask(id);
    if (previous != null) {
      commitTask(
          new Workspace.Todo(
              id,
              text == null ? previous.text() : text,
              done == null ? previous.done() : done,
              previous.sourceNoteId(),
              previous.sourceItemId(),
              previous.markdown()));
    }
  }

  private void commitTask(Workspace.Todo updated) {
    var previous = findTask(updated.id());
    if (previous == null) {
      return;
    }
    // The editor changes title, completion, and details; source linkage belongs to the workspace.
    var replacement =
        new Workspace.Todo(
            updated.id(),
            updated.text(),
            updated.done(),
            previous.sourceNoteId(),
            previous.sourceItemId(),
            updated.markdown());
    if (previous.equals(replacement)) {
      return;
    }
    tasks.set(tasks.indexOf(previous), replacement);
    var key = Workspace.taskTabId(updated.id());
    if (!previous.markdown().equals(replacement.markdown())) {
      reconcileTasks(key, replacement.markdown());
    }
    if (replacement.linked()
        && (!previous.text().equals(replacement.text()) || previous.done() != replacement.done())) {
      var source = documentMarkdown(replacement.sourceNoteId());
      if (source == null) {
        var current = findTask(replacement.id());
        tasks.set(tasks.indexOf(current), current.withoutLink());
      } else {
        var markdown =
            TaskLinks.update(
                source,
                replacement.sourceItemId(),
                replacement.done(),
                previous.text().equals(replacement.text()) ? null : replacement.text());
        updateDocumentMarkdown(replacement.sourceNoteId(), markdown);
        reconcileTasks(replacement.sourceNoteId(), markdown);
      }
    }
    refreshTaskEditors();
    renderTasks();
    changed();
  }

  private void reconcileTasks(String sourceId, String markdown) {
    if (tasks.stream().anyMatch(t -> t.sourceNoteId().equals(sourceId))) {
      updateTasks(TaskLinks.reconcile(sourceId, markdown, tasks));
    }
  }

  private void updateTasks(List<Workspace.Todo> updated) {
    if (!tasks.equals(updated)) {
      tasks.setAll(updated);
      refreshTaskEditors();
      renderTasks();
      changed();
    }
  }

  private void refreshTaskEditors() {
    for (var task : tasks) {
      var key = Workspace.taskTabId(task.id());
      var editor = editors.get(key);
      if (editor != null) {
        editor.updateTask(task);
      }
      tabs.getTabs().stream()
          .filter(tab -> key.equals(tab.getUserData()))
          .findFirst()
          .ifPresent(tab -> tab.setText(displayTitle(task)));
      if (key.equals(selectedId())) {
        stage.setTitle(displayTitle(task) + " — JavaFX Notes");
      }
    }
  }

  private String documentMarkdown(String id) {
    var task = taskForTab(id);
    if (task != null) {
      return task.markdown();
    }
    var note = findNote(id);
    return note == null ? null : note.markdown();
  }

  private void updateDocumentMarkdown(String id, String markdown) {
    var editor = editors.get(id);
    if (editor != null) {
      editor.updateMarkdown(markdown);
      return;
    }
    var task = taskForTab(id);
    if (task != null) {
      commitTask(task.withMarkdown(markdown));
      return;
    }
    var note = findNote(id);
    if (note != null) {
      notes.set(
          notes.indexOf(note),
          new Workspace.Note(note.id(), note.title(), note.collectionId(), markdown));
      searchDelay.playFromStart();
      changed();
    }
  }

  private void removeTask(String id) {
    closeTab(Workspace.taskTabId(id));
    tasks.removeIf(task -> task.id().equals(id));
    reconcileTasks(Workspace.taskTabId(id), "");
    renderTasks();
    changed();
  }

  private boolean removeLinkedTask(String sourceId, String itemId) {
    if (itemId.isEmpty()) {
      return true;
    }
    var task =
        tasks.stream()
            .filter(t -> t.sourceNoteId().equals(sourceId) && t.sourceItemId().equals(itemId))
            .findFirst()
            .orElse(null);
    if (task == null) {
      return true;
    }
    var editor = editors.get(Workspace.taskTabId(task.id()));
    if (editor != null) {
      editor.flushPendingEdits();
    }
    task = findTask(task.id());
    if (task == null) {
      return true;
    }
    if (!task.markdown().isBlank()
        && !confirm(
            "Delete “" + displayTitle(task) + "”?",
            "This task has details. Delete the task and its details and remove its checkbox? The"
                + " row text will remain.")) {
      return false;
    }
    removeTask(task.id());
    return true;
  }

  private void clearCompletedTasks() {
    editors.values().forEach(NoteEditor::flushPendingEdits);
    for (var id :
        visibleTasks.stream().filter(Workspace.Todo::done).map(Workspace.Todo::id).toList()) {
      removeTask(id);
    }
  }

  private final class TaskCell extends ListCell<Workspace.Todo> {
    private final CheckBox check = new CheckBox();
    private final Label label = new Label();
    private final Button remove =
        button(
            "×",
            () -> {
              if (getItem() != null) {
                removeTask(getItem().id());
              }
            });
    private final HBox row = new HBox(4, check, label, remove);
    private final ContextMenu menu =
        new ContextMenu(
            item("Open task", null, () -> openTask(getItem())),
            item(
                "Rename task…",
                null,
                () -> {
                  var task = getItem();
                  if (task == null) {
                    return;
                  }
                  prompt("Edit task", "Task", task.text())
                      .ifPresent(value -> replaceTask(task.id(), value, null));
                }));

    TaskCell() {
      check.setMinWidth(Region.USE_PREF_SIZE);
      check.setOnAction(
          e -> {
            var task = getItem();
            if (task != null) {
              replaceTask(task.id(), null, check.isSelected());
            }
          });
      label.setWrapText(true);
      label.setMinWidth(0);
      label.setMaxWidth(Double.MAX_VALUE);
      label.getStyleClass().add("task-text");
      HBox.setHgrow(label, Priority.ALWAYS);
      remove.getStyleClass().add("task-remove");
      remove.setMinWidth(Region.USE_PREF_SIZE);
      row.setAlignment(Pos.TOP_LEFT);
      row.getStyleClass().add("task-row");
      row.prefWidthProperty().bind(widthProperty().subtract(12));
      getStyleClass().add("task-cell");
      setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
      setOnMouseClicked(
          e -> {
            if (e.getButton() != MouseButton.PRIMARY || getItem() == null) {
              return;
            }
            for (var node = e.getTarget() instanceof javafx.scene.Node target ? target : null;
                node != null && node != this;
                node = node.getParent()) {
              if (node instanceof ButtonBase) {
                return;
              }
            }
            openTask(getItem());
          });
    }

    @Override
    protected void updateItem(Workspace.Todo task, boolean empty) {
      super.updateItem(task, empty);
      setText(null);
      setGraphic(null);
      setContextMenu(null);
      setAccessibleText(null);
      if (empty || task == null) {
        return;
      }
      check.setSelected(task.done());
      check.setAccessibleText("Complete task: " + task.text());
      label.setText(displayTitle(task));
      label.pseudoClassStateChanged(PseudoClass.getPseudoClass("done"), task.done());
      label.setTooltip(new Tooltip("Open task details"));
      remove.setAccessibleText("Remove task: " + task.text());
      setAccessibleText("Task: " + task.text() + (task.done() ? ", completed" : ", incomplete"));
      setContextMenu(menu);
      setGraphic(row);
    }
  }

  private static String excerpt(String markdown) {
    // Bound work to the visible snippet, even for a very large note.
    return markdown
        .substring(0, Math.min(240, markdown.length()))
        .replaceAll("<!-- task:.*?(?:-->|$)", "")
        .replaceAll("[#*`>\\[\\]]", "")
        .replaceAll("\\s+", " ")
        .strip();
  }

  private void newNote() {
    var collection = collectionFilter.isEmpty() ? collections.getFirst().id() : collectionFilter;
    var note = new Workspace.Note(Workspace.id(), "Untitled note", collection, "");
    notes.addFirst(note);
    search.clear();
    openNote(note);
    renderCollections();
    refreshNotes();
    changed();
    editors.get(note.id()).focusEditor();
  }

  private void newCollection() {
    prompt("New collection", "Give these notes a home", "")
        .ifPresent(
            name -> {
              if (duplicateCollection(name, "")) {
                return;
              }
              var c = new Workspace.Collection(Workspace.id(), name);
              collections.add(c);
              selectCollection(c.id());
            });
  }

  private boolean duplicateCollection(String name, String except) {
    if (collections.stream()
        .anyMatch(c -> !c.id().equals(except) && c.name().equalsIgnoreCase(name))) {
      info("Choose another name", "A collection with that name already exists.");
      return true;
    }
    return false;
  }

  private void renameCollection(Workspace.Collection collection) {
    prompt("Rename collection", "Collection name", collection.name())
        .ifPresent(
            value -> {
              if (duplicateCollection(value, collection.id())) {
                return;
              }
              for (int i = 0; i < collections.size(); i++) {
                if (collections.get(i).id().equals(collection.id())) {
                  collections.set(i, new Workspace.Collection(collection.id(), value));
                  break;
                }
              }
              renderCollections();
              changed();
            });
  }

  private void deleteCollection(Workspace.Collection c) {
    if (collections.size() <= 1) {
      return;
    }
    var target =
        collections.stream().filter(other -> !other.id().equals(c.id())).findFirst().orElseThrow();
    if (!confirm("Delete “" + c.name() + "”?", "Its notes will move to “" + target.name() + "”.")) {
      return;
    }
    var affected =
        notes.stream()
            .filter(n -> n.collectionId().equals(c.id()))
            .map(Workspace.Note::id)
            .toList();
    for (var id : affected) {
      moveNote(id, target.id());
    }
    collections.remove(c);
    if (collectionFilter.equals(c.id())) {
      selectCollection(target.id());
    }
    renderCollections();
    refreshNotes();
    changed();
  }

  private void openDocument(String id) {
    var task = taskForTab(id);
    if (task != null) {
      openTask(task);
    } else {
      openNote(findNote(id));
    }
  }

  private boolean selectOpenTab(String id) {
    if (compact && compactSidebar) {
      compactSidebar = false;
      arrangeSidebar();
    }
    for (var tab : tabs.getTabs()) {
      if (id.equals(tab.getUserData())) {
        tabs.getSelectionModel().select(tab);
        return true;
      }
    }
    return false;
  }

  private void openTask(Workspace.Todo task) {
    if (task == null) {
      return;
    }
    var key = Workspace.taskTabId(task.id());
    if (selectOpenTab(key)) {
      return;
    }
    // Switching tabs may commit pending source edits and update this task's linked title.
    var previous = editors.get(selectedId());
    if (previous != null) {
      previous.flushPendingEdits();
    }
    task = findTask(task.id());
    if (task == null) {
      return;
    }
    var tab = new Tab(displayTitle(task));
    tab.setUserData(key);
    tab.setGraphic(entityIcon(true));
    tab.getStyleClass().add("task-tab");
    var editor =
        NoteEditor.forTask(
            task,
            theme,
            rendering,
            this::commitTask,
            url -> getHostServices().showDocument(url),
            change -> updateTasks(TaskLinks.link(key, change, tasks)),
            itemId -> removeLinkedTask(key, itemId));
    addEditorTab(key, tab, editor);
  }

  private void openNote(Workspace.Note note) {
    if (note == null) {
      return;
    }
    if (!collectionFilter.isEmpty() && !note.collectionId().equals(collectionFilter)) {
      selectCollection(note.collectionId());
    }
    if (selectOpenTab(note.id())) {
      return;
    }
    var tab = new Tab(displayTitle(note));
    tab.setUserData(note.id());
    tab.setGraphic(entityIcon(false));
    tab.getStyleClass().add("note-tab");
    var editor =
        new NoteEditor(
            note,
            collections,
            theme,
            rendering,
            updated -> {
              var previous = findNote(updated.id());
              if (previous == null) {
                return;
              }
              notes.set(notes.indexOf(previous), updated);
              tab.setText(displayTitle(updated));
              if (!previous.markdown().equals(updated.markdown())) {
                reconcileTasks(updated.id(), updated.markdown());
              }
              if (!previous.collectionId().equals(updated.collectionId())) {
                refreshCollectionTabs();
                renderCollections();
                refreshNotes();
              }
              if (!previous.title().equals(updated.title()) && updated.id().equals(selectedId())) {
                stage.setTitle(displayTitle(updated) + " — JavaFX Notes");
              }
              searchDelay.playFromStart();
              changed();
            },
            url -> getHostServices().showDocument(url),
            task -> updateTasks(TaskLinks.link(note.id(), task, tasks)),
            itemId -> removeLinkedTask(note.id(), itemId));
    addEditorTab(note.id(), tab, editor);
  }

  private void addEditorTab(String id, Tab tab, NoteEditor editor) {
    tab.setId("document-tab-" + id);
    editor.setEditorFontSize(editorFontSize);
    editor.setTextScale(textScale);
    editor.setOnEditorFontSizeChanged(this::setEditorFontSize);
    editors.put(id, editor);
    documentTabs.put(id, tab);
    tab.setContent(editor);
    var menu = new ContextMenu(item("Rename…", null, () -> renameDocument(id)));
    if (findNote(id) != null) {
      var move = moveNoteMenu("Move to collection…", () -> findNote(id));
      menu.getItems().add(move);
      menu.setOnShowing(event -> populateMoveNoteMenu(move, findNote(id)));
    }
    tab.setContextMenu(menu);
    tab.setOnCloseRequest(e -> editor.flushPendingEdits());
    tab.setOnClosed(
        e -> {
          editor.dispose();
          editors.remove(id);
          documentTabs.remove(id);
          changed();
        });
    refreshCollectionTabs();
    tabs.getSelectionModel().select(tab);
    changed();
  }

  private void renameDoubleClickedTab(MouseEvent event) {
    if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
      return;
    }
    // Match the clicked header by identity, including its label, icon, and padding.
    for (var node = event.getTarget() instanceof javafx.scene.Node target ? target : null;
        node != null && node != tabs;
        node = node.getParent()) {
      if (node.getStyleClass().contains("tab-close-button")) {
        return;
      }
      if (!node.getStyleClass().contains("tab")) {
        continue;
      }
      for (var tab : tabs.getTabs()) {
        if (tab.getId().equals(node.getId())) {
          event.consume();
          tabs.getSelectionModel().select(tab);
          var id = (String) tab.getUserData();
          // Finish mouse dispatch before a modal dialog changes the fullscreen window.
          Platform.runLater(() -> renameDocument(id));
          return;
        }
      }
    }
  }

  private void closeSelected() {
    closeTab(selectedId());
  }

  private void renameDocument(String id) {
    var editor = editors.get(id);
    if (editor == null) {
      return;
    }
    editor.flushPendingEdits();
    var task = taskForTab(id);
    var note = findNote(id);
    if (task == null && note == null) {
      return;
    }
    prompt(
            task == null ? "Rename note" : "Rename task",
            "Name",
            task == null ? note.title() : task.text())
        .ifPresent(editor::rename);
  }

  private void closeTab(String id) {
    var tab = documentTabs.get(id);
    if (tab != null) {
      var editor = editors.get(id);
      if (editor != null) {
        editor.flushPendingEdits();
      }
      documentTabs.remove(id);
      tabs.getTabs().remove(tab);
      editors.remove(id);
      if (editor != null) {
        editor.dispose();
      }
      changed();
    }
  }

  private void deleteDocument() {
    var id = selectedId();
    var editor = editors.get(id);
    if (editor != null) {
      editor.flushPendingEdits();
    }
    var task = taskForTab(id);
    if (task != null) {
      var message =
          "This removes the task and its details from your notebook."
              + (task.linked() ? " Its source checkbox will remain." : "");
      if (confirm("Delete “" + displayTitle(task) + "”?", message)) {
        removeTask(task.id());
      }
      return;
    }
    var note = findNote(id);
    if (note == null) {
      return;
    }
    if (!confirm(
        "Delete “" + displayTitle(note) + "”?",
        "This removes the note from your notebook. Export it first if you want to keep a separate"
            + " copy.")) {
      return;
    }
    closeTab(note.id());
    notes.removeIf(n -> n.id().equals(note.id()));
    updateTasks(TaskLinks.reconcile(note.id(), "", tasks));
    renderCollections();
    refreshNotes();
    changed();
  }

  private void importNote() {
    var chooser = new FileChooser();
    chooser.setTitle("Import Markdown");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("Markdown and text", "*.md", "*.markdown", "*.txt"));
    var file = chooser.showOpenDialog(stage);
    if (file == null) {
      return;
    }
    String collection = collectionFilter.isEmpty() ? collections.getFirst().id() : collectionFilter;
    io.execute(
        () -> {
          try {
            var text = Files.readString(file.toPath());
            var name = file.getName().replaceFirst("\\.[^.]+$", "");
            Platform.runLater(
                () -> {
                  if (closing) {
                    return;
                  }
                  var target =
                      collections.stream().anyMatch(c -> c.id().equals(collection))
                          ? collection
                          : collections.getFirst().id();
                  var note = new Workspace.Note(Workspace.id(), name, target, text);
                  notes.addFirst(note);
                  search.clear();
                  openNote(note);
                  renderCollections();
                  refreshNotes();
                  changed();
                });
          } catch (IOException e) {
            Platform.runLater(() -> error("Import failed", e));
          }
        });
  }

  private void exportNote() {
    var editor = editors.get(selectedId());
    if (editor != null) {
      editor.flushPendingEdits();
    }
    var markdown = documentMarkdown(selectedId());
    if (markdown == null) {
      return;
    }
    var chooser = new FileChooser();
    chooser.setTitle("Export Markdown");
    chooser.setInitialFileName(
        documentTitle(selectedId()).replaceAll("[^\\p{L}\\p{N} ._-]", "_") + ".md");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
    var file = chooser.showSaveDialog(stage);
    if (file == null) {
      return;
    }
    io.execute(
        () -> {
          try {
            Files.writeString(file.toPath(), markdown);
            Platform.runLater(() -> info("Markdown exported", file.toString()));
          } catch (IOException e) {
            Platform.runLater(() -> error("Export failed", e));
          }
        });
  }

  private void changed() {
    if (starting || closing) {
      return;
    }
    revision++;
    saveStatus.setText("Unsaved changes");
    autosave.playFromStart();
  }

  private Workspace snapshot() {
    editors.values().forEach(NoteEditor::flushPendingEdits);
    return new Workspace(
        notes,
        collections,
        tasks,
        List.copyOf(documentTabs.keySet()),
        selectedId(),
        sidebarRight,
        sidebarBeforePreview == null ? sidebarFraction : sidebarBeforePreview.width(),
        sidebarBeforePreview == null ? sidebarTextSize : sidebarBeforePreview.textSize(),
        editorFontSize,
        collectionFilter);
  }

  private void save(Runnable success, boolean explicit) {
    var snapshot = snapshot();
    autosave.stop();
    long savingRevision = revision;
    saveStatus.setText("Saving…");
    io.execute(
        () -> {
          try {
            store.save(snapshot);
            Platform.runLater(
                () -> {
                  if (revision == savingRevision) {
                    saveStatus.setText("All changes saved");
                  }
                  if (success != null) {
                    success.run();
                  }
                });
          } catch (Exception e) {
            Platform.runLater(
                () -> {
                  saveStatus.setText("Save failed — Ctrl+S to retry");
                  if (closing) {
                    closing = false;
                    root.setDisable(false);
                  }
                  if (explicit) {
                    error("Your changes could not be saved", e);
                  }
                });
          }
        });
  }

  private void requestClose() {
    if (closing) {
      return;
    }
    closing = true;
    root.setDisable(true);
    save(
        () -> {
          stage.hide();
          Platform.exit();
        },
        true);
  }

  private void applyTheme(Theme next) {
    theme = next;
    applyAppStyle();
    editors.values().forEach(editor -> editor.setTheme(theme));
  }

  private void applyAppStyle() {
    root.setStyle(theme.fxStyle() + "-fx-font-size:" + (12 * textScale) + "px;");
  }

  private void setTextScale(double value) {
    textScale = Math.clamp(value, .8, 1.6);
    applyAppStyle();
    setSidebarSize(sidebarFraction, sidebarTextSize);
    editors.values().forEach(editor -> editor.setTextScale(textScale));
    adaptWidth(stage.getScene().getWidth());
    adaptHeight(stage.getScene().getHeight());
  }

  private void setEditorFontSize(double value) {
    double size =
        Math.clamp(
            Math.round(value),
            (long) Workspace.MIN_EDITOR_FONT_SIZE,
            (long) Workspace.MAX_EDITOR_FONT_SIZE);
    if (editorFontSize == size) {
      return;
    }
    editorFontSize = size;
    editors.values().forEach(editor -> editor.setEditorFontSize(size));
    changed();
  }

  private Workspace.Note findNote(String id) {
    return notes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
  }

  private Workspace.Todo findTask(String id) {
    return tasks.stream().filter(task -> task.id().equals(id)).findFirst().orElse(null);
  }

  private Workspace.Todo taskForTab(String key) {
    return key.startsWith("task:") ? findTask(key.substring("task:".length())) : null;
  }

  private String documentTitle(String id) {
    var task = taskForTab(id);
    return task == null ? displayTitle(findNote(id)) : displayTitle(task);
  }

  private String selectedId() {
    var tab = tabs.getSelectionModel().getSelectedItem();
    return tab == null ? "" : (String) tab.getUserData();
  }

  private static String displayTitle(Workspace.Note note) {
    return note == null || note.title().isBlank() ? "Untitled note" : note.title().strip();
  }

  private static String displayTitle(Workspace.Todo task) {
    return task == null || task.text().isBlank() ? "Untitled task" : task.text().strip();
  }

  private void editText(String action) {
    var focused = stage.getScene().getFocusOwner();
    var editor = editors.get(selectedId());
    if (!(focused instanceof TextInputControl) && editor != null && editor.editRendered(action)) {
      return;
    }
    TextInputControl text =
        focused instanceof TextInputControl input ? input : editor == null ? null : editor.source();
    if (text == null) {
      return;
    }
    switch (action) {
      case "undo" -> text.undo();
      case "redo" -> text.redo();
      case "cut" -> text.cut();
      case "copy" -> text.copy();
      case "paste" -> text.paste();
      case "all" -> text.selectAll();
      default -> {}
    }
  }

  private MenuItem item(String name, String shortcut, Runnable action) {
    var item = new MenuItem(name);
    if (shortcut != null) {
      item.setAccelerator(KeyCombination.keyCombination(shortcut));
    }
    item.setOnAction(e -> action.run());
    return item;
  }

  private static Button button(String text, Runnable action) {
    var button = new Button(text);
    button.setOnAction(e -> action.run());
    return button;
  }

  private static HBox section(String text, javafx.scene.Node action) {
    var label = new Label(text);
    label.getStyleClass().add("section-title");
    label.setMinWidth(0);
    boolean task = text.equals("TASKS");
    label.getStyleClass().add(task ? "task-label" : "note-label");
    var space = new Region();
    HBox.setHgrow(space, Priority.ALWAYS);
    var box = new HBox(4, entityIcon(task, .5), label, space, action);
    box.setAlignment(Pos.CENTER_LEFT);
    return box;
  }

  private static StackPane entityIcon(boolean task) {
    return entityIcon(task, 1);
  }

  private static StackPane entityIcon(boolean task, double scale) {
    var path = new SVGPath();
    path.setContent(
        task
            ? "M2 1 H12 Q13 1 13 2 V12 Q13 13 12 13 H2 Q1 13 1 12 V2 Q1 1 2 1 Z M4 7 L6 9 L10 5"
            : "M3 1 H9 L13 5 V13 H3 Z M9 1 V5 H13 M5 8 H10 M5 10.5 H10");
    path.getStyleClass().addAll("entity-icon", task ? "task-icon" : "note-icon");
    path.setScaleX(scale);
    path.setScaleY(scale);
    // Group includes the transformed path in layout measurements, keeping the icon's hit area
    // compact too.
    var icon = new StackPane(new javafx.scene.Group(path));
    double size = 15 * scale;
    icon.setMinSize(size, size);
    icon.setPrefSize(size, size);
    icon.setMaxSize(size, size);
    icon.setMouseTransparent(true);
    return icon;
  }

  private <T extends Dialog<?>> T styleDialog(T dialog) {
    dialog.initOwner(stage);
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm());
    // On Linux a fullscreen owner can interfere with mouse input in native dialogs.
    // Keep the owner windowed until all dialogs (including validation alerts) close.
    dialog.setOnShowing(
        event -> {
          openDialogs++;
          restoreFullscreenAfterDialogs |= stage.isFullScreen();
          if (stage.isFullScreen()) {
            stage.setFullScreen(false);
          }
        });
    dialog.setOnHidden(
        event -> {
          openDialogs--;
          Platform.runLater(
              () -> {
                if (openDialogs == 0 && restoreFullscreenAfterDialogs) {
                  restoreFullscreenAfterDialogs = false;
                  if (!closing && stage.isShowing()) {
                    stage.setFullScreen(true);
                  }
                }
              });
        });
    dialog.getDialogPane().setStyle(theme.fxStyle());
    return dialog;
  }

  private Optional<String> prompt(String heading, String label, String current) {
    var dialog = styleDialog(new TextInputDialog(current));
    dialog.setTitle("JavaFX Notes");
    dialog.setHeaderText(heading);
    dialog.setContentText(label);
    dialog
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .disableProperty()
        .bind(dialog.getEditor().textProperty().isEmpty());
    return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
  }

  private boolean confirm(String heading, String text) {
    var dialog =
        styleDialog(
            new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.CANCEL, ButtonType.OK));
    dialog.setHeaderText(heading);
    return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private void info(String heading, String text) {
    var dialog = styleDialog(new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK));
    dialog.setHeaderText(heading);
    dialog.showAndWait();
  }

  private void error(String heading, Exception error) {
    var dialog = styleDialog(new Alert(Alert.AlertType.ERROR, error.getMessage(), ButtonType.OK));
    dialog.setHeaderText(heading);
    dialog.showAndWait();
  }

  @Override
  public void stop() throws Exception {
    autosave.stop();
    searchDelay.stop();
    editors.values().forEach(NoteEditor::dispose);
    rendering.shutdownNow();
    io.shutdown();
    if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
      io.shutdownNow();
    }
    if (store != null) {
      store.close();
    }
  }
}
