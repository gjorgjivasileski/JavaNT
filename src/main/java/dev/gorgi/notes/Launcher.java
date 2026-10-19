package dev.gorgi.notes;

/** Plain entry point so java -jar can load the bundled JavaFX runtime from the classpath. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    NotesApp.main(args);
  }
}
