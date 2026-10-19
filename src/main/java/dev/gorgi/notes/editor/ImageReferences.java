package dev.gorgi.notes.editor;

import dev.gorgi.notes.markdown.ImageMarkup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** A lossless editor projection; saved/exported Markdown still contains the embedded images. */
final class ImageReferences {
  private static final Pattern REFERENCE = Pattern.compile("\\bimage:[1-9][0-9]*\\b");
  private static final Pattern TARGET_REFERENCE =
      Pattern.compile("(\\]\\(\\s*)<?(image:[1-9][0-9]*)>?(?=[\\s)]|$)");
  private static final Pattern DATA = Pattern.compile("data:image/", Pattern.CASE_INSENSITIVE);
  private static final Parser PARSER =
      Parser.builder()
          .extensions(List.of(TablesExtension.create()))
          .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
          .build();

  // Keep old references for undo/redo. Layout revisions share the same large image string.
  private final Map<String, String> sources = new HashMap<>();
  private final Map<Payload, String> names = new HashMap<>();
  private final Map<String, Payload> images = new HashMap<>();
  private final Set<String> reserved = new HashSet<>();
  private int nextId = 1;

  private record Payload(String before, String source, String after) {
    String target() {
      return before + source + after;
    }
  }

  record Replacement(int start, int end, String text) {}

  record Projection(String text, List<Replacement> replacements) {
    /** Positions after an image follow its reference; positions inside move to its end. */
    int position(int original) {
      int shift = 0;
      for (var replacement : replacements) {
        if (original <= replacement.start()) {
          break;
        }
        if (original < replacement.end()) {
          return replacement.start() + shift + replacement.text().length();
        }
        shift += replacement.text().length() - (replacement.end() - replacement.start());
      }
      return original + shift;
    }
  }

  Projection compact(String markdown) {
    // Never assign an existing, unresolved reference to an unrelated newly inserted image.
    REFERENCE.matcher(markdown).results().forEach(match -> reserved.add(match.group()));
    if (!DATA.matcher(markdown).find()) {
      return new Projection(markdown, List.of());
    }
    var replacements = new ArrayList<Replacement>();
    PARSER
        .parse(markdown)
        .accept(
            new AbstractVisitor() {
              @Override
              public void visit(Image image) {
                if (!ImageMarkup.allowedSource(image.getDestination())) {
                  return;
                }
                var spans = image.getSourceSpans();
                // The app emits single-line inline images. Leave other Markdown forms intact.
                if (spans.size() != 1) {
                  return;
                }
                int start = spans.getFirst().getInputIndex();
                int end = start + spans.getFirst().getLength();
                String raw = markdown.substring(start, end);
                if (!raw.endsWith(")")) {
                  return;
                }
                String destination = image.getDestination();
                for (int at = raw.indexOf(destination);
                    at >= 0;
                    at = raw.indexOf(destination, at + destination.length())) {
                  int opening = at - 1;
                  if (opening >= 0 && raw.charAt(opening) == '<') {
                    opening--;
                  }
                  while (opening >= 0 && Character.isWhitespace(raw.charAt(opening))) {
                    opening--;
                  }
                  if (opening < 1 || raw.charAt(opening) != '(' || raw.charAt(opening - 1) != ']') {
                    continue;
                  }
                  String source = sources.computeIfAbsent(destination, value -> value);
                  var payload =
                      new Payload(
                          raw.substring(opening + 1, at),
                          source,
                          raw.substring(at + destination.length(), raw.length() - 1));
                  String name = names.get(payload);
                  if (name == null) {
                    do {
                      name = "image:" + nextId++;
                    } while (reserved.contains(name));
                    reserved.add(name);
                    names.put(payload, name);
                    images.put(name, payload);
                  }
                  replacements.add(new Replacement(start + opening + 1, end - 1, name));
                  return;
                }
              }
            });
    if (replacements.isEmpty()) {
      return new Projection(markdown, List.of());
    }
    var result = new StringBuilder(markdown.length());
    int previous = 0;
    for (var replacement : replacements) {
      result.append(markdown, previous, replacement.start()).append(replacement.text());
      previous = replacement.end();
    }
    result.append(markdown, previous, markdown.length());
    return new Projection(result.toString(), List.copyOf(replacements));
  }

  String expand(String markdown) {
    if (images.isEmpty() || !markdown.contains("image:")) {
      return markdown;
    }
    // Also expand temporarily incomplete syntax and code: autosave must never lose the bytes
    // when someone removes a bracket or wraps an existing image in backticks while editing.
    return TARGET_REFERENCE
        .matcher(markdown)
        .replaceAll(
            match -> {
              var payload = images.get(match.group(2));
              return java.util.regex.Matcher.quoteReplacement(
                  payload == null ? match.group() : match.group(1) + payload.target());
            });
  }
}
