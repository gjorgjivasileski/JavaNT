package dev.gorgi.notes.markdown;

import dev.gorgi.notes.theme.Theme;
import java.net.URI;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.UrlSanitizer;

public final class Markdown {
  private Markdown() {}

  public static String render(String text, Theme theme) {
    List<Extension> extensions =
        List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create());
    var metadata = TaskLinks.renderingMetadata(text);
    var byOffset = new HashMap<Integer, TaskLinks.Entry>();
    for (var entry : metadata.entries()) {
      byOffset.put(entry.start(), entry);
    }
    var parser =
        Parser.builder()
            .extensions(extensions)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();
    // Keep the original AST: removing a metadata-only label before parsing would remove its
    // checkbox too.
    var document = parser.parse(text);
    var entriesByMarker = new IdentityHashMap<Node, TaskLinks.Entry>();
    document.accept(
        new AbstractVisitor() {
          @Override
          public void visit(ListItem item) {
            if (item.getFirstChild() instanceof TaskListItemMarker marker
                && marker.getNext() instanceof Paragraph paragraph
                && !paragraph.getSourceSpans().isEmpty()) {
              var entry = byOffset.get(paragraph.getSourceSpans().getFirst().getInputIndex());
              if (entry != null) {
                entriesByMarker.put(marker, entry);
              } else {
                // The extension treats escaped checkbox punctuation as a task. Restore it as
                // literal text.
                String literal = marker.isChecked() ? "[x] " : "[ ] ";
                if (paragraph.getFirstChild() instanceof Text first) {
                  first.setLiteral(literal + first.getLiteral());
                } else {
                  paragraph.prependChild(new Text(literal));
                }
                marker.unlink();
              }
            }
            visitChildren(item);
          }
        });
    var renderer =
        HtmlRenderer.builder()
            .extensions(extensions)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .nodeRendererFactory(
                context ->
                    new NodeRenderer() {
                      @Override
                      public Set<Class<? extends Node>> getNodeTypes() {
                        return Set.of(HtmlInline.class);
                      }

                      @Override
                      public void render(Node node) {
                        boolean metadataMarker =
                            !node.getSourceSpans().isEmpty()
                                && metadata
                                    .markerOffsets()
                                    .contains(node.getSourceSpans().getFirst().getInputIndex());
                        if (!metadataMarker) {
                          context.getWriter().text(((HtmlInline) node).getLiteral());
                        }
                      }
                    })
            .attributeProviderFactory(
                context ->
                    (node, tag, attributes) -> {
                      if ((tag.equals("ul") || tag.equals("ol")) && node.getFirstChild() != null) {
                        boolean tasksOnly = true;
                        for (Node item = node.getFirstChild();
                            item != null;
                            item = item.getNext()) {
                          if (!(item instanceof ListItem)
                              || !(item.getFirstChild() instanceof TaskListItemMarker marker)
                              || !entriesByMarker.containsKey(marker)) {
                            tasksOnly = false;
                            break;
                          }
                        }
                        if (tasksOnly) {
                          attributes.put("class", "task-list");
                        }
                      }
                      if (tag.equals("li")
                          && node.getFirstChild() instanceof TaskListItemMarker marker
                          && entriesByMarker.containsKey(marker)) {
                        attributes.put("class", "task-list-item");
                      }
                      if (tag.equals("img")
                          && node instanceof Image image
                          && ImageMarkup.allowedSource(image.getDestination())) {
                        ImageMarkup.parse(image.getTitle())
                            .ifPresent(
                                layout -> {
                                  attributes.put("class", "note-image");
                                  attributes.put("data-image-id", layout.id());
                                  attributes.put("data-image-x", Integer.toString(layout.x()));
                                  attributes.put("data-image-y", Integer.toString(layout.y()));
                                  attributes.put(
                                      "data-image-width", Integer.toString(layout.width()));
                                  attributes.put(
                                      "data-image-height", Integer.toString(layout.height()));
                                  attributes.put("data-image-z", Integer.toString(layout.z()));
                                  attributes.put(
                                      "data-image-mode", layout.floating() ? "floating" : "inline");
                                  attributes.put("style", layout.style());
                                  attributes.put("contenteditable", "false");
                                  attributes.put("draggable", "false");
                                  attributes.put("title", attributes.getOrDefault("alt", "Image"));
                                });
                      }
                      if (tag.equals("input") && "checkbox".equals(attributes.get("type"))) {
                        var entry = entriesByMarker.get(node);
                        if (entry == null) {
                          return;
                        }
                        attributes.remove("disabled");
                        attributes.put("data-task-index", Integer.toString(entry.ordinal()));
                        attributes.put("contenteditable", "false");
                        attributes.put("aria-label", "Create or update linked task");
                        if (!entry.id().isBlank()) {
                          attributes.put("data-task-id", entry.id());
                        }
                      }
                    })
            .urlSanitizer(
                new UrlSanitizer() {
                  @Override
                  public String sanitizeLinkUrl(String url) {
                    return isExternalLink(url) || url.startsWith("#") ? url : "";
                  }

                  @Override
                  public String sanitizeImageUrl(String url) {
                    return ImageMarkup.allowedSource(url) ? url.replaceAll("\\s+", "") : "";
                  }
                })
            .build();
    return "<!doctype html><html><head><meta charset='UTF-8'>"
        + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src"
        + " 'unsafe-inline'; img-src data:;\">"
        + "<style>"
        + theme.webCss()
        + "</style></head><body contenteditable='true' role='textbox'"
        + " aria-label='Editable note preview' spellcheck='false'>"
        + renderer.render(document)
        + "</body></html>";
  }

  /** Returns whether a URL uses an external scheme the editor may open. */
  public static boolean isExternalLink(String url) {
    if (url == null) {
      return false;
    }
    try {
      String scheme = URI.create(url).getScheme();
      return scheme != null
          && List.of("https", "http", "mailto").contains(scheme.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
