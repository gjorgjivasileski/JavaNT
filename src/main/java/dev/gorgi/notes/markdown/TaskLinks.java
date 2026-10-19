package dev.gorgi.notes.markdown;

import dev.gorgi.notes.model.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Stable links between independent task records and real Markdown checklist items. */
public final class TaskLinks {
  private TaskLinks() {}

  private static final Parser PARSER =
      Parser.builder()
          .extensions(
              List.of(
                  TablesExtension.create(),
                  StrikethroughExtension.create(),
                  TaskListItemsExtension.create()))
          .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
          .build();
  private static final Pattern MARKER =
      Pattern.compile(
          "<!-- task:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
              + " -->(?=[ \\t]*$)");
  private static final Pattern CHECKBOX = Pattern.compile("^\\[[xX\\s]]\\s+");
  private static final Pattern ORDERED_PREFIX = Pattern.compile("^[0-9]{1,9}([.)])(?:[ \\t]+|$)");

  /**
   * Offsets are UTF-16, from the checkbox '[' through its first line, excluding the line ending.
   */
  public record Entry(int ordinal, String text, boolean checked, String id, int start, int end) {}

  public record Change(String markdown, String id, String text, boolean checked) {}

  record Removal(Change change, int caret) {}

  private record Item(Entry entry, int labelStart, int labelEnd, int markerStart, int markerEnd) {}

  record RenderingMetadata(List<Entry> entries, Set<Integer> markerOffsets) {}

  static RenderingMetadata renderingMetadata(String markdown) {
    var items = parse(markdown);
    var markers = new HashSet<Integer>();
    for (var item : items) {
      if (item.markerStart() >= 0) {
        markers.add(item.markerStart());
      }
    }
    return new RenderingMetadata(items.stream().map(Item::entry).toList(), Set.copyOf(markers));
  }

  public static List<Entry> entries(String markdown) {
    return parse(markdown).stream().map(Item::entry).toList();
  }

  /** A click links exactly one checkbox, even when another item has the same label. */
  public static Change toggle(String markdown, int ordinal, boolean checked) {
    var items = parse(markdown);
    if (ordinal < 0 || ordinal >= items.size()) {
      throw new IllegalArgumentException("Unknown checklist item");
    }
    var item = items.get(ordinal);
    String id = item.entry().id().isEmpty() ? Workspace.id() : item.entry().id();
    String result = rewrite(markdown, item, checked, null, id);
    return new Change(result, id, item.entry().text(), checked);
  }

  /** Removes one checkbox and its app metadata, preserving the list item and its contents. */
  public static Change removeCheckbox(String markdown, int ordinal) {
    return removeCheckbox(markdown, ordinal, 0).change();
  }

  /** Maps a source caret through the same deletions without moving it across retained text. */
  static Removal removeCheckbox(String markdown, int ordinal, int position) {
    var items = parse(markdown);
    if (ordinal < 0 || ordinal >= items.size()) {
      throw new IllegalArgumentException("Unknown checklist item");
    }
    var item = items.get(ordinal);
    var entry = item.entry();
    var result = new StringBuilder(markdown);
    int caret = Math.clamp(position, 0, markdown.length());
    // Delete backwards so the original source offsets remain valid.
    if (item.markerStart() >= 0) {
      int start = item.markerStart();
      if (start > item.labelStart() && markdown.charAt(start - 1) == ' ') {
        start--;
      }
      result.delete(start, item.markerEnd());
      caret = afterDeletion(caret, start, item.markerEnd());
    }
    result.delete(entry.start(), item.labelStart());
    caret = afterDeletion(caret, entry.start(), item.labelStart());
    int protection = blockStartProtection(markdown.substring(item.labelStart(), item.labelEnd()));
    if (protection >= 0) {
      int insertion = entry.start() + protection;
      result.insert(insertion, '\\');
      if (caret >= insertion) {
        caret++;
      }
    }
    return new Removal(
        new Change(result.toString(), entry.id(), entry.text(), entry.checked()), caret);
  }

  /** A label was inline after its checkbox; keep newly exposed block punctuation literal. */
  private static int blockStartProtection(String label) {
    if (label.isEmpty()) {
      return -1;
    }
    if (CHECKBOX.matcher(label).find()) {
      return 0;
    }
    Node first = PARSER.parse(label).getFirstChild();
    if (first == null || first instanceof Paragraph) {
      return -1;
    }
    var ordered = ORDERED_PREFIX.matcher(label);
    return ordered.find() ? ordered.start(1) : 0;
  }

  private static int afterDeletion(int caret, int start, int end) {
    return caret <= start ? caret : caret < end ? start : caret - (end - start);
  }

  /** Changes only the completion flag, preserving all label formatting and surrounding source. */
  public static String update(String markdown, String itemId, boolean checked) {
    return update(markdown, itemId, checked, null);
  }

  /** A non-null label is plain task text; its Markdown punctuation is escaped when inserted. */
  public static String update(String markdown, String itemId, boolean checked, String text) {
    if (itemId == null || itemId.isEmpty()) {
      return markdown;
    }
    for (var item : parse(markdown)) {
      if (item.entry().id().equals(itemId)) {
        return rewrite(markdown, item, checked, text, itemId);
      }
    }
    return markdown;
  }

  /** Removes only recognized app markers on task lines, never matching examples in code blocks. */
  public static String stripMarkers(String markdown) {
    var items = parse(markdown);
    var result = new StringBuilder(markdown);
    for (int i = items.size() - 1; i >= 0; i--) {
      var item = items.get(i);
      if (item.markerStart() >= 0) {
        int start = item.markerStart();
        if (start > item.labelStart() && markdown.charAt(start - 1) == ' ') {
          start--;
        }
        result.delete(start, item.markerEnd());
      }
    }
    return result.toString();
  }

  /**
   * Existing tasks survive deleted source items as independent tasks. Typing a checkbox does not
   * create a task.
   */
  public static List<Workspace.Todo> reconcile(
      String noteId, String markdown, List<Workspace.Todo> tasks) {
    var byId = new HashMap<String, Entry>();
    for (var entry : entries(markdown)) {
      if (!entry.id().isEmpty()) {
        byId.putIfAbsent(entry.id(), entry);
      }
    }
    var result = new ArrayList<Workspace.Todo>(tasks.size());
    for (var task : tasks) {
      if (!task.sourceNoteId().equals(noteId)) {
        result.add(task);
        continue;
      }
      var entry = byId.get(task.sourceItemId());
      result.add(
          entry == null
              ? task.withoutLink()
              : new Workspace.Todo(
                  task.id(), entry.text(), entry.checked(), noteId, entry.id(), task.markdown()));
    }
    return List.copyOf(result);
  }

  /** Creates one separate task or updates the already linked one without changing its identity. */
  public static List<Workspace.Todo> link(
      String noteId, Change change, List<Workspace.Todo> tasks) {
    var result = new ArrayList<Workspace.Todo>(tasks.size() + 1);
    boolean found = false;
    for (var task : tasks) {
      if (task.sourceNoteId().equals(noteId) && task.sourceItemId().equals(change.id())) {
        result.add(
            new Workspace.Todo(
                task.id(), change.text(), change.checked(), noteId, change.id(), task.markdown()));
        found = true;
      } else {
        result.add(task);
      }
    }
    if (!found) {
      result.add(
          new Workspace.Todo(Workspace.id(), change.text(), change.checked(), noteId, change.id()));
    }
    return List.copyOf(result);
  }

  private static String rewrite(
      String markdown, Item item, boolean checked, String text, String id) {
    var entry = item.entry();
    var result = new StringBuilder(markdown);
    // Work backwards so offsets are still those of the original document.
    if (item.markerStart() >= 0) {
      result.replace(item.markerStart(), item.markerEnd(), marker(id));
    } else {
      result.insert(item.labelEnd(), " " + marker(id));
    }
    if (text != null && !text.equals(entry.text())) {
      result.replace(item.labelStart(), item.labelEnd(), escapeLabel(text));
    }
    result.setCharAt(entry.start() + 1, checked ? 'x' : ' ');
    return result.toString();
  }

  private static String marker(String id) {
    return "<!-- task:" + id + " -->";
  }

  private static String escapeLabel(String text) {
    var result = new StringBuilder();
    for (char c : text.replace('\n', ' ').replace('\r', ' ').toCharArray()) {
      if ("\\`*_{}[]()#+-.!<>|~&".indexOf(c) >= 0) {
        result.append('\\');
      }
      result.append(c);
    }
    return result.toString().strip();
  }

  private static List<Item> parse(String markdown) {
    var items = new ArrayList<Item>();
    var ids = new HashSet<String>();
    PARSER
        .parse(markdown)
        .accept(
            new AbstractVisitor() {
              @Override
              public void visit(ListItem listItem) {
                if (listItem.getFirstChild() instanceof TaskListItemMarker marker
                    && marker.getNext() instanceof Paragraph paragraph
                    && !paragraph.getSourceSpans().isEmpty()) {
                  int start = paragraph.getSourceSpans().getFirst().getInputIndex();
                  int end = start;
                  while (end < markdown.length()
                      && markdown.charAt(end) != '\n'
                      && markdown.charAt(end) != '\r') {
                    end++;
                  }
                  String line = markdown.substring(start, end);
                  var checkbox = CHECKBOX.matcher(line);
                  if (checkbox.find()) {
                    int labelStart = start + checkbox.end();
                    var match = MARKER.matcher(line);
                    String id = "";
                    int markerStart = -1;
                    int markerEnd = -1;
                    int labelEnd = end;
                    if (match.find() && isInlineMarker(paragraph, start + match.start())) {
                      markerStart = start + match.start();
                      markerEnd = start + match.end();
                      labelEnd = markerStart;
                      // A copied marker cannot accidentally make two separate checkboxes one task.
                      if (ids.add(match.group(1))) {
                        id = match.group(1);
                      }
                    }
                    while (labelEnd > labelStart
                        && Character.isWhitespace(markdown.charAt(labelEnd - 1))) {
                      labelEnd--;
                    }
                    String text = plainText(markdown.substring(labelStart, labelEnd));
                    items.add(
                        new Item(
                            new Entry(items.size(), text, marker.isChecked(), id, start, end),
                            labelStart,
                            labelEnd,
                            markerStart,
                            markerEnd));
                  }
                }
                visitChildren(listItem);
              }
            });
    return items;
  }

  private static boolean isInlineMarker(Paragraph paragraph, int offset) {
    for (Node child = paragraph.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof HtmlInline
          && !child.getSourceSpans().isEmpty()
          && child.getSourceSpans().getFirst().getInputIndex() == offset) {
        return true;
      }
    }
    return false;
  }

  private static String plainText(String markdown) {
    if (markdown.isBlank()) {
      return "";
    }
    var result = new StringBuilder();
    // Force inline interpretation: a task label beginning with '#' is not a Markdown heading.
    PARSER
        .parse("x " + markdown)
        .accept(
            new AbstractVisitor() {
              @Override
              public void visit(Text text) {
                result.append(text.getLiteral());
              }

              @Override
              public void visit(Code code) {
                result.append(code.getLiteral());
              }

              @Override
              public void visit(HtmlInline html) {
                result.append(html.getLiteral());
              }

              @Override
              public void visit(SoftLineBreak lineBreak) {
                result.append(' ');
              }

              @Override
              public void visit(HardLineBreak lineBreak) {
                result.append(' ');
              }
            });
    return result.toString().substring(2).strip();
  }
}
