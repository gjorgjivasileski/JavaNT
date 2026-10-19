# JavaNT

**JavaNT** means **Java Notes and Tasks**.

## 1. Prerequisites

| System | Requirements |
| --- | --- |
| Linux | JDK 26 and a graphical desktop with GTK 3 and X11/XWayland libraries. |
| Windows | JDK 26 for Windows and a desktop session. |
| macOS | JDK 26 matching your Mac's Intel or Apple Silicon processor. |

Set `JAVA_HOME` to your JDK installation and make `java` available on `PATH`. To build from source, install **Apache Maven 3.9 or newer** and make `mvn` available on `PATH`. The first build needs internet access to download dependencies, including JavaFX 26.0.2; a separate JavaFX installation is unnecessary.

## 2. Build and start the app

Open a terminal in the project folder containing `pom.xml`, then run these commands on Linux, Windows, or macOS:

```sh
mvn clean verify
mvn javafx:run
```

## 3. Build a runnable JAR

```sh
mvn -Pfat-jar clean package
```

The output is `target/javafx-notes-1.0.0-all.jar`, including JavaFX and the application's other dependencies. Build separately for each target OS and processor architecture because JavaFX includes native libraries.

## 4. Start the JAR

From the project folder:

```sh
java -jar target/javafx-notes-1.0.0-all.jar
```

You can copy the JAR anywhere and use its new path in the command. Java 26 must be installed; Maven is unnecessary when running the JAR.

The shaded JAR can print JavaFX's unsupported-configuration warning because it loads JavaFX from the classpath. [JavaFX officially requires named modules](https://openjfx.io/javadoc/26/javafx.graphics/javafx/application/Application.html); `mvn javafx:run` uses the module path.

## 5. Features

Organize Markdown notes into collections, search their contents, and work in Write, Read, or Split mode. Notes and tasks open in tabs that you can rename and drag to reorder. Add images, resize or reposition them, change their layering, and import or export Markdown files.

Create linked tasks from note checkboxes, keep their names and completion states synchronized, and add Markdown details in each task's own editor. Linked tasks appear in the sidebar when their source tab is selected. Changes autosave locally with a previous-save backup. Adjust the sidebar and text sizes, and use the active Omarchy theme when available or the default appearance on other desktops.
