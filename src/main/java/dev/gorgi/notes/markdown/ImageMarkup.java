package dev.gorgi.notes.markdown;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Stores image placement in a standard Markdown image title. */
public final class ImageMarkup {
  public static final int MAX_POSITION = 100_000;
  public static final int MIN_SIZE = 1;
  public static final int MAX_SIZE = 8192;
  public static final int MAX_LAYER = 10_000;

  private static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern ID = Pattern.compile(UUID_PATTERN);
  private static final Pattern TITLE =
      Pattern.compile(
          "javafxnotes-image:("
              + UUID_PATTERN
              + ")"
              + ";x=([0-9]{1,6});y=([0-9]{1,6});width=([0-9]{1,4});height=([0-9]{1,4})"
              + ";z=(-?[0-9]{1,5})(?:;mode=(inline|floating))?");
  private static final Pattern SOURCE =
      Pattern.compile(
          "data:image/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+", Pattern.CASE_INSENSITIVE);

  private ImageMarkup() {}

  /** Coordinates and dimensions use CSS pixels; zero is the text layer. */
  public record Layout(String id, int x, int y, int width, int height, int z, boolean floating) {
    public Layout(String id, int x, int y, int width, int height, int z) {
      this(id, x, y, width, height, z, true);
    }

    public Layout {
      if (id == null
          || !ID.matcher(id).matches()
          || x < 0
          || x > MAX_POSITION
          || y < 0
          || y > MAX_POSITION
          || width < MIN_SIZE
          || width > MAX_SIZE
          || height < MIN_SIZE
          || height > MAX_SIZE
          || z < -MAX_LAYER
          || z > MAX_LAYER) {
        throw new IllegalArgumentException("Invalid image layout");
      }
      id = id.toLowerCase(Locale.ROOT);
    }

    public String title() {
      return "javafxnotes-image:"
          + id
          + ";x="
          + x
          + ";y="
          + y
          + ";width="
          + width
          + ";height="
          + height
          + ";z="
          + z
          + ";mode="
          + (floating ? "floating" : "inline");
    }

    public String style() {
      if (!floating) {
        return "position:relative;width:"
            + width
            + "px;max-width:100%;height:auto;z-index:"
            + z
            + ";";
      }
      return "position:absolute;left:"
          + x
          + "px;top:"
          + y
          + "px;"
          + "width:"
          + width
          + "px;height:"
          + height
          + "px;z-index:"
          + z
          + ";";
    }
  }

  /** Invalid or ordinary image titles have no managed layout. */
  public static Optional<Layout> parse(String title) {
    if (title == null) {
      return Optional.empty();
    }
    var match = TITLE.matcher(title);
    if (!match.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Layout(
              match.group(1),
              Integer.parseInt(match.group(2)),
              Integer.parseInt(match.group(3)),
              Integer.parseInt(match.group(4)),
              Integer.parseInt(match.group(5)),
              Integer.parseInt(match.group(6)),
              !"inline".equals(match.group(7))));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  /** Only embedded raster images can load in the preview. */
  public static boolean allowedSource(String source) {
    return source != null && SOURCE.matcher(source).matches();
  }

  public record InsertionPoint(int offset, boolean beforeCode) {}

  /** Moves a writing caret before its containing block when inserting inside code. */
  public static InsertionPoint insertionPoint(String markdown, int caret) {
    int position = Math.clamp(caret, 0, markdown.length());
    InsertionPoint[] result = {new InsertionPoint(position, false)};
    var document =
        Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build()
            .parse(markdown);
    document.accept(
        new AbstractVisitor() {
          @Override
          public void visit(Code code) {
            protect(code, false, false);
          }

          @Override
          public void visit(FencedCodeBlock code) {
            protect(code, true, code.getClosingFenceLength() == null);
          }

          @Override
          public void visit(IndentedCodeBlock code) {
            protect(code, true, false);
          }

          private void protect(Node code, boolean block, boolean unclosed) {
            var spans = code.getSourceSpans();
            if (spans.isEmpty()) {
              return;
            }
            int start = spans.getFirst().getInputIndex();
            var last = spans.getLast();
            int end = last.getInputIndex() + last.getLength();
            // Spans omit line separators, including a final newline inside an unclosed fence.
            boolean atUnclosedEnd =
                unclosed && position == markdown.length() && markdown.substring(end).isBlank();
            if (position >= start
                && (position < end || block && position == end || atUnclosedEnd)) {
              // Preserve list markers, quotes, and inline code's original paragraph as a unit.
              Node container = code;
              while (container.getParent() != null
                  && !(container.getParent() instanceof Document)) {
                container = container.getParent();
              }
              var containerSpans = container.getSourceSpans();
              int insertion =
                  containerSpans.isEmpty() ? start : containerSpans.getFirst().getInputIndex();
              result[0] = new InsertionPoint(Math.min(result[0].offset(), insertion), true);
            }
          }
        });
    return result[0];
  }

  public static int safeInsertionOffset(String markdown, int caret) {
    return insertionPoint(markdown, caret).offset();
  }

  public static String markdown(String alt, String dataUrl, Layout layout) {
    if (!allowedSource(dataUrl)) {
      throw new IllegalArgumentException("An embedded raster image is required");
    }
    String label = alt == null ? "" : alt.replaceAll("\\s+", " ");
    var escaped = new StringBuilder();
    for (int i = 0; i < label.length(); i++) {
      char character = label.charAt(i);
      // CommonMark backslash escapes preserve punctuation and entity-looking filenames literally.
      if ((character >= '!' && character <= '/')
          || (character >= ':' && character <= '@')
          || (character >= '[' && character <= '`')
          || (character >= '{' && character <= '~')) {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return "!["
        + escaped
        + "](<"
        + dataUrl.replaceAll("\\s+", "")
        + "> \""
        + layout.title()
        + "\")";
  }
}
