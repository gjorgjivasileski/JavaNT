package dev.gorgi.notes.markdown;

import java.util.Optional;
import java.util.regex.Pattern;

/** Toggles the current source row's checkbox without replacing its text. */
public final class CheckboxEdit {
  private static final Pattern ROW =
      Pattern.compile("^([ \\t]*(?:>[ \\t]*)*)((?:[-+*]|[0-9]{1,9}[.)])[ \\t]+)?(.*)$");
  private static final Pattern CHECKBOX = Pattern.compile("^\\[[ xX]](?:[ \\t]+|$)");

  /** Carries the task update, mapped caret position, and whether a checkbox was removed. */
  public record Edit(TaskLinks.Change change, int caret, boolean removal) {}

  private CheckboxEdit() {}

  /** Toggles the checkbox in the source row containing the given caret position. */
  public static Optional<Edit> at(String markdown, int position) {
    int caret = Math.clamp(position, 0, markdown.length());
    int start = markdown.lastIndexOf('\n', caret - 1) + 1;
    int end = markdown.indexOf('\n', caret);
    if (end < 0) {
      end = markdown.length();
    }
    if (end > start && markdown.charAt(end - 1) == '\r') {
      end--;
    }
    for (var entry : TaskLinks.entries(markdown)) {
      if (entry.start() >= start && entry.start() <= end) {
        var removal = TaskLinks.removeCheckbox(markdown, entry.ordinal(), caret);
        return Optional.of(new Edit(removal.change(), removal.caret(), true));
      }
    }

    String row = markdown.substring(start, end);
    var match = ROW.matcher(row);
    if (!match.matches()) {
      return Optional.empty();
    }
    String text = match.group(3);
    if (text.isBlank()) {
      text = "New task";
    }
    String replacement =
        match.group(1)
            + (match.group(2) == null ? "- " : match.group(2))
            + (CHECKBOX.matcher(text).find() ? "" : "[ ] ")
            + text;
    String updated = markdown.substring(0, start) + replacement + markdown.substring(end);
    int rowEnd = start + replacement.length();
    for (var entry : TaskLinks.entries(updated)) {
      if (entry.start() >= start && entry.start() <= rowEnd) {
        var change = TaskLinks.toggle(updated, entry.ordinal(), entry.checked());
        return Optional.of(
            new Edit(
                change,
                Math.clamp(caret + replacement.length() - row.length(), start, rowEnd),
                false));
      }
    }
    // A checklist-looking line inside fenced/indented code is still code. Leave it intact.
    return Optional.empty();
  }
}
