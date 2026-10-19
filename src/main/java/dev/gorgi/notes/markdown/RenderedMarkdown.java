package dev.gorgi.notes.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.html.HTMLInputElement;

/** Converts the editable preview's DOM back into the Markdown stored with a note. */
public final class RenderedMarkdown {
  private static final Set<String> BLOCKS =
      Set.of(
          "p",
          "div",
          "section",
          "article",
          "main",
          "header",
          "footer",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "ul",
          "ol",
          "blockquote",
          "pre",
          "table",
          "hr");
  private static final Set<String> IGNORED =
      Set.of(
          "head",
          "script",
          "style",
          "title",
          "meta",
          "link",
          "base",
          "iframe",
          "object",
          "embed",
          "svg",
          "math",
          "noscript",
          "template",
          "button",
          "select",
          "textarea");
  private static final Pattern TASK_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern HTML_ENTITY =
      Pattern.compile("&(?:#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);");

  private RenderedMarkdown() {}

  /** Returns canonical Markdown with a final newline, or an empty string for an empty body. */
  public static String fromDocument(Document document) {
    if (document == null) {
      return "";
    }
    Element body = findBody(document.getDocumentElement());
    if (body == null) {
      return "";
    }
    String result = blocks(body, false).strip();
    return result.isEmpty() ? "" : result + "\n";
  }

  private static Element findBody(Node node) {
    if (node == null) {
      return null;
    }
    if (name(node).equals("body")) {
      return (Element) node;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      Element body = findBody(child);
      if (body != null) {
        return body;
      }
    }
    return null;
  }

  /** Group consecutive inline nodes without adding spaces around formatting boundaries. */
  private static String blocks(Node parent, boolean inListItem) {
    var result = new StringBuilder();
    var pending = new StringBuilder();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      String tag = name(node);
      if (IGNORED.contains(tag) || isImageUi(node)) {
        continue;
      }
      if (BLOCKS.contains(tag)) {
        appendBlock(result, protectBlockStart(pending.toString().strip()), false);
        pending.setLength(0);
        appendBlock(result, block((Element) node), inListItem && isList(tag));
      } else {
        pending.append(inline(node));
      }
    }
    appendBlock(result, protectBlockStart(pending.toString().strip()), false);
    return result.toString();
  }

  private static void appendBlock(StringBuilder result, String block, boolean nestedList) {
    if (block.isBlank()) {
      return;
    }
    if (!result.isEmpty()) {
      result.append(nestedList ? "\n" : "\n\n");
    }
    result.append(block);
  }

  private static String block(Element element) {
    String tag = name(element);
    return switch (tag) {
      case "h1", "h2", "h3", "h4", "h5", "h6" ->
          "#".repeat(tag.charAt(1) - '0') + " " + inlineChildren(element).strip();
      case "ul", "ol" -> list(element);
      case "blockquote" -> quote(blocks(element, false));
      case "pre" -> fencedCode(element);
      case "table" -> table(element);
      case "hr" -> "---";
      default -> blocks(element, false);
    };
  }

  private static String inline(Node node) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      return escapeText(node.getNodeValue());
    }
    if (!(node instanceof Element element)) {
      return "";
    }
    String tag = name(element);
    if (IGNORED.contains(tag) || isImageUi(node)) {
      return "";
    }
    return switch (tag) {
      case "br" -> "  \n";
      case "strong", "b" -> wrapped(inlineChildren(element), "**");
      case "em", "i" -> wrapped(inlineChildren(element), "*");
      case "del", "s", "strike" -> wrapped(inlineChildren(element), "~~");
      case "code" -> inlineCode(element.getTextContent());
      case "a" -> link(element);
      case "img" -> image(element);
      case "input" -> ""; // A list item's checkbox is emitted by list(), exactly once.
      default -> inlineChildren(element);
    };
  }

  private static String inlineChildren(Node parent) {
    var result = new StringBuilder();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      result.append(inline(node));
    }
    return result.toString();
  }

  private static String wrapped(String content, String delimiter) {
    String text = content.strip();
    if (text.isEmpty()) {
      return content;
    }
    int start = content.indexOf(text);
    return content.substring(0, start)
        + delimiter
        + text
        + delimiter
        + content.substring(start + text.length());
  }

  private static String list(Element list) {
    var result = new StringBuilder();
    boolean ordered = name(list).equals("ol");
    int number = positiveInteger(attribute(list, "start"), 1);
    for (Node child = list.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element item) || !name(item).equals("li") || isImageUi(child)) {
        continue;
      }
      if (ordered && item.hasAttribute("value")) {
        number = positiveInteger(attribute(item, "value"), number);
      }
      String prefix = ordered ? number++ + ". " : "- ";
      String content = blocks(item, true).strip();
      Element checkbox = ownCheckbox(item);
      if (checkbox != null) {
        boolean checked =
            checkbox instanceof HTMLInputElement input
                ? input.getChecked()
                : checkbox.hasAttribute("checked");
        String id = attribute(checkbox, "data-task-id");
        if (TASK_ID.matcher(id).matches()) {
          int newline = content.indexOf('\n');
          if (newline < 0) {
            content += " <!-- task:" + id + " -->";
          } else {
            String firstLine = content.substring(0, newline);
            String label = firstLine.stripTrailing();
            content =
                label
                    + " <!-- task:"
                    + id
                    + " -->"
                    + firstLine.substring(label.length())
                    + content.substring(newline);
          }
        }
        content = (checked ? "[x] " : "[ ] ") + content;
      }
      if (!result.isEmpty()) {
        result.append('\n');
      }
      String[] lines = content.split("\n", -1);
      result.append(prefix).append(lines[0]);
      String indentation = " ".repeat(prefix.length());
      for (int i = 1; i < lines.length; i++) {
        result.append('\n');
        if (!lines[i].isEmpty()) {
          result.append(indentation).append(lines[i]);
        }
      }
    }
    return result.toString();
  }

  /** A nested list's checkbox belongs to its own item, never to the parent item. */
  private static Element ownCheckbox(Node parent) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element element)) {
        continue;
      }
      String tag = name(element);
      if (isList(tag) || tag.equals("li") || IGNORED.contains(tag) || isImageUi(child)) {
        continue;
      }
      if (tag.equals("input") && attribute(element, "type").equalsIgnoreCase("checkbox")) {
        return element;
      }
      Element found = ownCheckbox(element);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static String quote(String content) {
    if (content.isEmpty()) {
      return ">";
    }
    var result = new StringBuilder();
    for (String line : content.split("\n", -1)) {
      if (!result.isEmpty()) {
        result.append('\n');
      }
      result.append(line.isEmpty() ? ">" : "> " + line);
    }
    return result.toString();
  }

  private static String fencedCode(Element pre) {
    Element code = null;
    for (Node child = pre.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && name(element).equals("code")) {
        code = element;
        break;
      }
    }
    String language = "";
    if (code != null) {
      for (String cssClass : attribute(code, "class").split("\\s+")) {
        if (cssClass.matches("language-[a-zA-Z0-9_+.#-]+")) {
          language = cssClass.substring("language-".length());
          break;
        }
      }
    }
    String content = normalizeNewlines((code == null ? pre : code).getTextContent());
    String fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
    return fence + language + "\n" + content + (content.endsWith("\n") ? "" : "\n") + fence;
  }

  private static String inlineCode(String content) {
    content = normalizeNewlines(content).replace('\n', ' ');
    if (content.isEmpty()) {
      return "";
    }
    String delimiter = "`".repeat(longestBacktickRun(content) + 1);
    boolean padding =
        content.startsWith("`")
            || content.endsWith("`")
            || (content.startsWith(" ") && content.endsWith(" ") && !content.isBlank());
    return delimiter + (padding ? " " : "") + content + (padding ? " " : "") + delimiter;
  }

  private static int longestBacktickRun(String text) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < text.length(); i++) {
      run = text.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return longest;
  }

  private static String link(Element element) {
    String label = inlineChildren(element);
    String url = attribute(element, "href");
    if (!Markdown.isExternalLink(url) && !url.startsWith("#")) {
      return label;
    }
    return "[" + label + "](<" + destination(url) + ">" + title(element) + ")";
  }

  private static String image(Element element) {
    String label = escapeText(attribute(element, "alt"));
    String url = attribute(element, "src");
    if (!ImageMarkup.allowedSource(url)) {
      return label;
    }
    String mode = attribute(element, "data-image-mode");
    var layout =
        ImageMarkup.parse(
            "javafxnotes-image:"
                + attribute(element, "data-image-id")
                + ";x="
                + attribute(element, "data-image-x")
                + ";y="
                + attribute(element, "data-image-y")
                + ";width="
                + attribute(element, "data-image-width")
                + ";height="
                + attribute(element, "data-image-height")
                + ";z="
                + attribute(element, "data-image-z")
                + (mode.isEmpty() ? "" : ";mode=" + mode));
    if (layout.isPresent()) {
      return ImageMarkup.markdown(attribute(element, "alt"), url, layout.get());
    }
    return "!["
        + label
        + "](<"
        + destination(url.replaceAll("\\s+", ""))
        + ">"
        + title(element)
        + ")";
  }

  private static boolean isImageUi(Node node) {
    return node instanceof Element element && attribute(element, "data-image-ui").equals("true");
  }

  private static String destination(String url) {
    return url.replace("\\", "%5C")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
        .replace("\t", "%09")
        .replace(" ", "%20");
  }

  private static String title(Element element) {
    String title = attribute(element, "title");
    if (title.isEmpty()) {
      return "";
    }
    return " \"" + title.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\s+", " ") + "\"";
  }

  private static String table(Element table) {
    List<Element> rows = new ArrayList<>();
    collectRows(table, rows);
    if (rows.isEmpty()) {
      return "";
    }
    List<List<Element>> cells = rows.stream().map(RenderedMarkdown::cells).toList();
    int width = cells.stream().mapToInt(List::size).max().orElse(0);
    if (width == 0) {
      return "";
    }
    var result = new StringBuilder(tableRow(cells.getFirst(), width));
    result.append('\n').append('|');
    for (int i = 0; i < width; i++) {
      Element cell = i < cells.getFirst().size() ? cells.getFirst().get(i) : null;
      String align = cell == null ? "" : attribute(cell, "align").toLowerCase(Locale.ROOT);
      if (cell != null && align.isEmpty()) {
        var matcher =
            Pattern.compile(
                    "(?:^|;)\\s*text-align\\s*:\\s*(left|right|center)\\b",
                    Pattern.CASE_INSENSITIVE)
                .matcher(attribute(cell, "style"));
        if (matcher.find()) {
          align = matcher.group(1).toLowerCase(Locale.ROOT);
        }
      }
      result
          .append(' ')
          .append(
              switch (align) {
                case "left" -> ":---";
                case "right" -> "---:";
                case "center" -> ":---:";
                default -> "---";
              })
          .append(" |");
    }
    for (int i = 1; i < cells.size(); i++) {
      result.append('\n').append(tableRow(cells.get(i), width));
    }
    return result.toString();
  }

  private static void collectRows(Node parent, List<Element> rows) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element element) || isImageUi(child)) {
        continue;
      }
      switch (name(element)) {
        case "tr" -> rows.add(element);
        case "thead", "tbody", "tfoot" -> collectRows(element, rows);
        default -> {}
      }
    }
  }

  private static List<Element> cells(Element row) {
    List<Element> cells = new ArrayList<>();
    for (Node child = row.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element
          && Set.of("th", "td").contains(name(element))
          && !isImageUi(child)) {
        cells.add(element);
      }
    }
    return cells;
  }

  private static String tableRow(List<Element> cells, int width) {
    var row = new StringBuilder("|");
    for (int i = 0; i < width; i++) {
      String content =
          i < cells.size() ? blocks(cells.get(i), false).replaceAll("\\s*\\n\\s*", " ") : "";
      // Pipes delimit GFM cells even inside code spans.
      var safe = new StringBuilder();
      int slashes = 0;
      for (int j = 0; j < content.length(); j++) {
        char ch = content.charAt(j);
        if (ch == '|' && slashes % 2 == 0) {
          safe.append('\\');
        }
        safe.append(ch);
        slashes = ch == '\\' ? slashes + 1 : 0;
      }
      row.append(' ').append(safe).append(" |");
    }
    return row.toString();
  }

  private static String escapeText(String text) {
    text = text.replace('\u00a0', ' ').replaceAll("[\\t\\n\\r\\f ]+", " ");
    var result = new StringBuilder();
    var entity = HTML_ENTITY.matcher(text);
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if ("\\`*_{}[]<>#|~".indexOf(ch) >= 0
          || ch == '&' && entity.region(i, text.length()).lookingAt()) {
        result.append('\\');
      }
      result.append(ch);
    }
    return result.toString();
  }

  private static String protectBlockStart(String text) {
    return text.replaceAll("(?m)^([+-])(?= |$)", "\\\\$1")
        .replaceAll("(?m)^(-)(?=[ -]*$)", "\\\\$1")
        .replaceAll("(?m)^(=)(?==*[ \\t]*$)", "\\\\$1")
        .replaceAll("(?m)^(\\d{1,9})([.)])(?= |$)", "$1\\\\$2");
  }

  /** WebKit can return null for absent HTML attributes; XML DOM fixtures return an empty string. */
  private static String attribute(Element element, String name) {
    String value = element.getAttribute(name);
    return value == null ? "" : value;
  }

  private static int positiveInteger(String value, int fallback) {
    try {
      return Math.max(0, Integer.parseInt(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean isList(String tag) {
    return tag.equals("ul") || tag.equals("ol");
  }

  private static String normalizeNewlines(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String name(Node node) {
    return node.getNodeType() == Node.ELEMENT_NODE
        ? node.getNodeName().toLowerCase(Locale.ROOT)
        : "";
  }
}
