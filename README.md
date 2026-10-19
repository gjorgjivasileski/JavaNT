# JavaFX Notes

**JavaNT** means **Java Notes and Tasks**.

A local desktop notebook and task list built with Java 26, JavaFX 26.0.2, and Maven. The interface follows your active Omarchy palette and terminal font, with note and task tabs across the top and notes and tasks in a movable sidebar.

## Build and run

Use a **JDK 26** with `JAVA_HOME` pointing to that installation. The Maven Wrapper downloads Maven 3.9.16 on first use, so a separate Maven installation is unnecessary. The first build also downloads dependencies.

```bash
cd /home/gorgi/Work/Personal_Apps/JavaFXNotes
./mvnw --version
./mvnw clean verify
./mvnw javafx:run
```

Check that `./mvnw --version` reports Java 26. Run the application in a graphical desktop session.

### Single runnable JAR

To bundle the app, JavaFX, and Markdown libraries into one file:

```bash
./mvnw -Pfat-jar package
java -jar target/javafx-notes-1.0.0-all.jar
```

Copy `target/javafx-notes-1.0.0-all.jar` anywhere and run it with an installed **Java 26**:

```bash
java -jar /path/to/javafx-notes-1.0.0-all.jar
```

No Maven, separate JavaFX installation, or adjacent library directory is needed to run it. The JAR includes JavaFX's native libraries for the build machine's OS and architecture: a build on this Linux x86_64 machine runs on compatible Linux x86_64 desktops. Build separately on other target platforms. The Java runtime and system desktop libraries are not included; use the native application below to bundle Java itself.

Your notes and tasks remain in the workspace directory described under **Local storage**; moving the JAR does not move that data. To use a workspace in a chosen location:

```bash
java -jar /path/to/javafx-notes-1.0.0-all.jar --data-dir=/path/to/my-notes
```

The `fat-jar` profile uses [Maven Shade](https://maven.apache.org/plugins/maven-shade-plugin/) and creates an additional `-all.jar`. The ordinary JAR remains modular for the native build.

**JavaFX support limitation:** the shaded JAR loads JavaFX from the classpath and prints `WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module ...'`. Startup continues, and this build has been tested locally, but [JavaFX 26 officially requires its libraries to be loaded as named modules](https://openjfx.io/javadoc/26/javafx.graphics/javafx/application/Application.html). Native-access flags do not resolve this module warning. Use the native application below for supported modular packaging without this warning; copy its entire application directory when moving it.

### Application with bundled Java

To build the Linux application with its own Java runtime:

```bash
./mvnw -Pnative clean package
./target/native/JavaFXNotes/bin/JavaFXNotes
```

The `native` profile uses `jlink` and `jpackage` to create `target/native/JavaFXNotes/`. Keep or copy that entire directory together; its launcher uses the bundled runtime. Build the image on the OS and architecture where it will run. It does not require a separately installed JDK on the target machine, but still uses the desktop system's native libraries. The output is an application directory, not a system installer.

## Notes and tasks

- Create notes from **File → New note** or the **New note** button. Each open note has a top tab; closing its tab keeps the note in the collection.
- Notes have document icons; tasks have checkbox icons. Their labels, icons, and tabs use two distinct colors from the active theme. Tasks are separate workspace records and can exist without a note.
- Choose **Write**, **Split**, or **Read**. Edit Markdown source or click directly into the rendered text in **Read** or the **Split** preview. Rendered edits are converted back to Markdown and autosaved; equivalent Markdown formatting may be normalized. The formatting toolbar supports headings, emphasis, lists, checklists, quotes, code, and links. Tables and strikethrough are supported.
- Create, rename, and delete collections from the **Collections** menu. The filled dropdown beside search selects the active collection and stays available even when no note is open. New notes belong to that collection. Deleting a collection moves its notes to another collection.
- Only the active collection's notes appear in the sidebar and note tabs. Switching collections restores that collection's open tabs, and the app remembers open notes and the active collection across restarts. Task tabs remain available independently. Choose **All notes** for a combined view.
- Right-click a note in the sidebar or its tab and choose **Move to collection…** to move it. **Collections → Move note to collection…** moves the selected note. Open notes keep their text and editor state when moved; closed notes stay closed in the destination collection.
- Search note titles and contents from the compact header search field. Search follows the collection selected in the dropdown or **Collections** menu; choose **All notes** to search the whole notebook.
- Create tasks from checkboxes in the editor. In the sidebar, check them off, remove individual tasks, or clear completed tasks. Click a task's label, or select it and press Enter, to open its task tab. Edit its Markdown details and use **Done** in the toolbar to change completion; task details are saved independently of note content. Closing the tab keeps the task.
- Linked tasks appear in the sidebar only while their source note's tab is selected. Selecting a different tab hides the previous note's tasks, even when that note remains open; selecting it again restores them. Tasks without a source note remain visible. Checkboxes linked from task details follow whether that task's tab is selected. The task counter and **Clear completed** apply only to the tasks currently shown; hidden tasks and their details remain saved.
- Drag a note or task tab left or right to reorder it. The app keeps the new order when switching collections and after restarting, including the order of tabs hidden in other collections.
- The editor starts with the formatting toolbar, without a separate title panel. **Double-click a note or task tab** to rename it. You can also use **File → Rename…**, **F2**, or right-click the tab and choose **Rename…**. The new name appears in the tab and sidebar; linked task names also update their source checklist row.
- Use **File → Delete…** to delete the active note or task after confirmation. Deleting a task closes its tab and keeps the source checkbox; tasks created from its details become independent tasks.
- To turn a note row into a task, place the caret anywhere in that row and click the **☐ checklist toolbar button**. It keeps the whole row as the task title and creates the linked task immediately. This works in **Write**, **Read**, and **Split**, including checklists inside task details. Applying the toolbar again removes the checkbox and deletes its linked task, keeping the row text, formatting, and nested list items. If the task has details, a confirmation appears first; Cancel keeps the checkbox, task, and details. Deleting the task also closes its tab; tasks created from its details become independent tasks.
- Clicking an existing Markdown checkbox in **Read** or the **Split** preview also creates a linked task if needed. Linked titles and completion changes synchronize in both directions between the note and task. Typing a checklist alone does not create a task. Deleting the source checklist item or its note keeps the task and its details as an independent task.
- Task rows display just the checkbox and text, without a list bullet. The Markdown source keeps the standard `- [ ]` checklist syntax; ordinary lists still display their bullets.
- Use **Images → Insert image…** or **Ctrl+Shift+I** to insert a PNG, JPEG, or GIF (up to 16 MB) in a note or task. New images are **Inline with text**. In **Read** or the **Split** preview, select an image and drag its bottom-right handle to resize it while preserving its proportions.
- Choose **Images → Floating** to move an image independently of the text. Drag the image or its top handle to reposition it. **Bring to front**, **Send to back**, and **Delete image** are available in the Images menu and by right-clicking the image, alongside both placement modes. Front/back commands switch an inline image to floating so it can overlap text and other images. **Images → Select image** lets you select an image even when it is covered by another image or text.
- In **Write** and **Split**, embedded images appear as short references such as `![photo.png](image:1)`. The image data and layout metadata stay hidden from the writing view, including for existing notes. Edit the label or move/delete the whole reference like ordinary Markdown; undo restores a deleted image.
- Image bytes, size, placement mode, position, and layer are still embedded in saved Markdown and autosaved. Copying or cutting an image reference includes its full Markdown, so pasting into another note keeps the image. Copying the workspace or exporting Markdown also includes the images; the original image files are no longer needed. Reference numbers belong to the current editor and may change after reopening or changing image layout. Other Markdown viewers can display the embedded image but may ignore the app's layout metadata.
- The sidebar contains Notes and Tasks. Use **View → Sidebar on right** to switch sides. The sidebar defaults to one-sixth of the combined content width, with compact text, rows, icons, controls, and spacing at half their original size. Drag the divider to adjust its width; the chosen proportion follows window resizing and side changes. Drag the horizontal handle between Notes and Tasks up or down to adjust their heights. Open tabs, the active note, and the sidebar side are restored on launch.
- Open **View → Sidebar size…** to adjust the sidebar width (10–70% of the window) and its text size (6–18 px at the default zoom). Changes preview immediately; **Apply** keeps them, **Cancel** restores the previous settings, and **Reset** previews the original compact size. Sidebar width and text size are saved with the notebook and restored on launch. These controls leave the editor text size unchanged; the existing View text zoom still scales the whole interface.
- The layout adapts to different window sizes: headers become compact, formatting controls wrap, lists scroll, and Split mode stacks the editor and preview when space is limited. In narrow tiled windows, **☰** or **Ctrl+L** switches between the notebook and sidebar; in wider windows it hides or shows the sidebar. **F11** toggles full screen.
- Use **View → Larger text**, **Smaller text**, or **Reset text size** to adjust interface and preview text from 80% to 160%. The layout responds to the selected text size too.
- Choose **Text size** in the formatting toolbar to change the main Markdown and preview text for notes and tasks (8–40 px, default 14 px). This setting applies across editors and is saved with the notebook. It leaves menu, tab, toolbar, and sidebar text unchanged; the View zoom setting still scales the whole interface. Narrow margins leave most of each tab available for content, and the preview fills the available width.
- Dialogs temporarily leave fullscreen so their buttons receive mouse input, then restore fullscreen when closed. Normal windows stay windowed.
- Use **File → Import Markdown** for `.md`, `.markdown`, or `.txt` files, and **Export Markdown** to save the active note's source as a UTF-8 `.md` file. Import creates a copy in the notebook; it does not keep the original file synchronized.

Raw HTML and remote images are disabled in the preview. Its Content Security Policy blocks page scripts and external content; application-controlled DOM commands remain enabled for rendered editing. **Ctrl+click** a supported web or email link to open it in the desktop's external application.

### Keyboard shortcuts

| Shortcut on Linux | Action |
| --- | --- |
| Ctrl+N | New note |
| Ctrl+Shift+N | New collection |
| Ctrl+K | Find notes |
| Ctrl+L | Toggle notes and tasks |
| Ctrl+= / Ctrl+- | Larger / smaller text |
| Ctrl+0 | Reset text size |
| F2 | Rename active note or task |
| F11 | Toggle full screen |
| Ctrl+O | Import Markdown |
| Ctrl+Shift+S | Export active note |
| Ctrl+Shift+I | Insert image |
| Ctrl+S | Save now |
| Ctrl+W | Close active tab |
| Ctrl+Q | Quit |
| Ctrl+B / Ctrl+I | Bold / italic in source or rendered text |
| Ctrl+Z / Ctrl+Shift+Z | Undo / redo |

Standard cut, copy, paste, and select-all shortcuts are also available. The **Help** menu includes a Markdown reference.

## Local storage

Edits autosave after a short pause, and closing the application waits for a successful save. The status bar shows whether changes are saved or a save needs retrying.

With no storage overrides, the app stores the workspace under the user's home directory:

| System | Default workspace folder |
| --- | --- |
| Linux | `/home/<username>/.local/share/javafx-notes/` |
| Windows | `C:\Users\<username>\.local\share\javafx-notes\` |
| macOS | `/Users/<username>/.local/share/javafx-notes/` |

These examples assume the usual home directory locations. If `XDG_DATA_HOME` is set, the default is its `javafx-notes` subfolder on any platform. Launch with `--data-dir=...` to choose a custom folder. **Help → About JavaFX Notes** shows the actual path.

| File | Purpose |
| --- | --- |
| `workspace.xml` | Notes, collections, tasks, and saved UI state |
| `workspace.xml.bak` | Previous saved workspace, replaced on each save |
| `workspace.lock` | Prevents two running instances from writing to the same workspace |

The workspace is stored locally without a cloud service. Back up the workspace directory while the app is closed. To recover from a damaged workspace, close the app, preserve the damaged file separately, and copy `workspace.xml.bak` to `workspace.xml`. The app reports an unreadable workspace instead of silently replacing it.

Use a separate workspace, including for experimentation, with a packaged launcher argument:

```bash
./target/native/JavaFXNotes/bin/JavaFXNotes --data-dir=/tmp/javafx-notes-demo
```

Each workspace allows one running instance. Different workspace directories can be open at the same time.

## Omarchy appearance

The app reads `omarchy/current/theme/colors.toml` from `$XDG_STATE_HOME` first, then `$XDG_CONFIG_HOME`. These default to `~/.local/state` and `~/.config`. The terminal font is read from `$XDG_CONFIG_HOME/foot/foot.ini`, then `$XDG_CONFIG_HOME/alacritty/alacritty.toml`.

Theme changes are checked every four seconds. Use **View → Reload Omarchy theme** to refresh immediately. Notes and tasks use distinct theme colors, adjusted for readability on the background and sidebar. If a palette has no suitable second color, the app derives one so the two remain different. Task checkboxes in the preview use the same task color. Without a readable Omarchy palette, the app uses a light neutral palette with a blue accent and `JetBrainsMono Nerd Font` (subject to the fonts installed on the system). It only reads desktop configuration.

Application styling lives in `src/main/resources/dev/gorgi/notes/app.css`; palette and font loading live in `src/main/java/dev/gorgi/notes/theme/Theme.java`. Maven dependencies and packaging settings are in `pom.xml`.

## Source organization

Application classes live under `src/main/java/dev/gorgi/notes/`:

| Package | Classes and responsibilities |
| --- | --- |
| `dev.gorgi.notes` | `Launcher`, `NotesApp`: application startup, menus, and view coordination |
| `model` | `Workspace`: immutable note, task, collection, and UI snapshots |
| `storage` | `WorkspaceStore`: persistence, backups, and workspace locking |
| `editor` | `NoteEditor`, `ImageMarkdownArea`, `ImageReferences`, `PreviewImages`, `EmbeddedImage`: JavaFX editing and image interaction |
| `markdown` | `Markdown`, `RenderedMarkdown`, `CheckboxEdit`, `TaskLinks`, `ImageMarkup`: rendering, conversion, and task/image markup |
| `theme` | `Theme`: palette, fonts, and preview styles |

Tests mirror these packages under `src/test/java/dev/gorgi/notes/`; the full application smoke test stays in the root package. The trusted image controller script lives at `src/main/resources/dev/gorgi/notes/editor/images.js`, beside the package that loads it.

`NotesApp` uses image actions exposed by `NoteEditor`; `PreviewImages` and the compact image-reference helpers remain internal to the editor package. The application entry points and Maven launch commands remain the same.

## Verification

`./mvnw test` runs persistence, Markdown rendering and editing, task-linking, and theme tests. The real-window smoke test is opt-in:

```bash
./mvnw -Dnotes.uiTest=true test
```

For the focused image-reference window check (copy/cut/paste, undo/redo, note and task images, preview layout, and save/reopen), run `./mvnw -Dnotes.imageUiTest=true test` separately.

For collection filtering, collection menus, right-click note moves, pending draft preservation, and restoring open tabs across restarts, run `./mvnw -Dtest=CollectionsUiTest -Dnotes.collectionsUiTest=true test` separately. This check uses application controls without Robot mouse gestures and writes only a temporary notebook.

It temporarily opens a full-screen window (requires a desktop at least 900 pixels wide), exercises rendered editing, toolbar task creation, task tabs and details, linked completion, task deletion and cancellation, save/reopen, zoom, and compact navigation. Image checks cover insertion, inline/floating modes, dragging, resizing, layering, context-menu deletion, and persistence. It also checks sidebar proportions across resizing and side changes, previews/applies/cancels/resets sidebar settings, verifies their restoration after reopening, drags the divider between notes and tasks, moves the mouse to click dialog buttons, and verifies fullscreen restoration. It writes `target/ui-initial.png`, `target/ui-edited.png`, `target/ui-images.png`, `target/ui-compact.png`, `target/ui-dialog.png`, `target/ui-sidebar-settings.png`, and `target/ui-sidebar-narrow.png`. It requests a 420×460 window and reports the actual size because tiling window managers may override it. It uses a temporary notebook, separate from your notes.

Surefire runs tests on the classpath, so the UI test may print JavaFX's unnamed-module warning. Development and native application launches use the module path; the fat JAR uses the classpath. Packaged launchers are checked separately.
