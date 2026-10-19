package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gorgi.notes.theme.Theme;
import org.junit.jupiter.api.Test;

class ImageMarkupTest {
  private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String SOURCE = "data:image/png;base64,AAAA";

  @Test
  void preservesLayoutIncludingTheLayerBehindText() {
    var layout = new ImageMarkup.Layout(ID, 12, 34, 320, 240, -1);

    assertEquals(layout, ImageMarkup.parse(layout.title()).orElseThrow());
    assertEquals(
        "position:absolute;left:12px;top:34px;width:320px;height:240px;z-index:-1;",
        layout.style());
  }

  @Test
  void retainsInlinePlacementAndAcceptsEarlierFloatingTitles() {
    var inline = new ImageMarkup.Layout(ID, 0, 0, 320, 240, 1, false);
    assertEquals(inline, ImageMarkup.parse(inline.title()).orElseThrow());
    assertEquals(
        "position:relative;width:320px;max-width:100%;height:auto;z-index:1;", inline.style());
    var floating = new ImageMarkup.Layout(ID, 12, 34, 320, 240, -1);
    assertEquals(
        floating, ImageMarkup.parse(floating.title().replace(";mode=floating", "")).orElseThrow());
    assertTrue(ImageMarkup.parse(inline.title().replace("mode=inline", "mode=fixed")).isEmpty());
  }

  @Test
  void preservesVeryThinImageDimensionsAcrossModes() {
    var inline = new ImageMarkup.Layout(ID, 0, 0, 480, 1, 0, false);
    var floating = new ImageMarkup.Layout(ID, 0, 0, 480, 1, 1, true);
    assertEquals(inline, ImageMarkup.parse(inline.title()).orElseThrow());
    assertEquals(floating, ImageMarkup.parse(floating.title()).orElseThrow());
    assertTrue(floating.style().contains("width:480px;height:1px"));
  }

  @Test
  void movesImageInsertionBeforeFencedAndIndentedCodeWithoutChangingSource() {
    String fenced = "Before\n\n```java\nfirst\nsecond\n```\n\nAfter";
    assertEquals(
        fenced.indexOf("```"),
        ImageMarkup.safeInsertionOffset(fenced, fenced.indexOf("second") + 2));
    assertEquals(
        fenced.indexOf("```"),
        ImageMarkup.safeInsertionOffset(fenced, fenced.indexOf("first") + 5));
    String indented = "Before\n\n    first\n    second\n\nAfter";
    assertEquals(
        indented.indexOf("    first"),
        ImageMarkup.safeInsertionOffset(indented, indented.indexOf("second") + 2));
    assertTrue(Markdown.render(fenced, Theme.fallback()).contains("first\nsecond\n</code>"));
  }

  @Test
  void includesEndOfAnUnclosedFenceButNotAnOrdinaryPositionAfterClosedCode() {
    for (String unclosed : new String[] {"```java\ncode", "~~~\ncode\n", "```"}) {
      assertEquals(0, ImageMarkup.safeInsertionOffset(unclosed, unclosed.length()));
    }
    String closed = "```\ncode\n```\n";
    assertEquals(closed.length(), ImageMarkup.safeInsertionOffset(closed, closed.length()));
    String quote = "> ```\n> code\n\nAfter";
    assertEquals(quote.length(), ImageMarkup.safeInsertionOffset(quote, quote.length()));
  }

  @Test
  void movesBeforeInlineCodesParagraphAndLeavesOrdinaryTextAndEscapedDelimitersAlone() {
    String text = "Before `inline code` after";
    assertEquals(0, ImageMarkup.safeInsertionOffset(text, text.indexOf("code") + 2));
    assertEquals(
        text.indexOf(" after"), ImageMarkup.safeInsertionOffset(text, text.indexOf(" after")));
    assertEquals(3, ImageMarkup.safeInsertionOffset(text, 3));
    assertEquals(0, ImageMarkup.safeInsertionOffset(text, -10));
    assertEquals(text.length(), ImageMarkup.safeInsertionOffset(text, text.length() + 10));
    assertEquals(0, ImageMarkup.safeInsertionOffset("", 0));
    String literal = "Before \\`literal\\` after";
    assertEquals(
        literal.indexOf("literal") + 2,
        ImageMarkup.safeInsertionOffset(literal, literal.indexOf("literal") + 2));
  }

  @Test
  void preservesCodeContainersAndSignalsSeparatorsAtTheBeginningOfCode() {
    for (String code : new String[] {"```\ncode\n```", "    code", "`code`"}) {
      assertEquals(new ImageMarkup.InsertionPoint(0, true), ImageMarkup.insertionPoint(code, 0));
    }
    String quote = "Before\n\n> ```\n> code\n> ```\n";
    assertEquals(
        new ImageMarkup.InsertionPoint(quote.indexOf('>'), true),
        ImageMarkup.insertionPoint(quote, quote.indexOf("code") + 2));
    String list = "Before\n\n- first\n- ```\n  code\n  ```\n";
    assertEquals(
        new ImageMarkup.InsertionPoint(list.indexOf("- first"), true),
        ImageMarkup.insertionPoint(list, list.indexOf("code") + 2));
    String task = "Before\n\n- [ ] Run `code` now\n";
    assertEquals(
        new ImageMarkup.InsertionPoint(task.indexOf("- [ ]"), true),
        ImageMarkup.insertionPoint(task, task.indexOf("code") + 2));
    assertEquals(
        new ImageMarkup.InsertionPoint(3, false), ImageMarkup.insertionPoint("ordinary", 3));
  }

  @Test
  void rejectsMalformedAndUnboundedMetadata() {
    String title = new ImageMarkup.Layout(ID, 12, 34, 320, 240, 1).title();

    for (String invalid :
        new String[] {
          null,
          "ordinary title",
          title + ";background:url(https://example.com)",
          title.replace(ID, "invalid"),
          title.replace("x=12", "x=-1"),
          title.replace("x=12", "x=100001"),
          title.replace("y=34", "y=9999999999999999999999"),
          title.replace("width=320", "width=0"),
          title.replace("height=240", "height=8193"),
          title.replace("z=1", "z=-10001"),
          title.replace("z=1", "z=10001")
        }) {
      assertTrue(ImageMarkup.parse(invalid).isEmpty(), () -> "Accepted: " + invalid);
    }
    assertThrows(IllegalArgumentException.class, () -> new ImageMarkup.Layout(ID, 0, 0, 0, 240, 1));
  }

  @Test
  void generatedMarkdownPreservesPunctuationAndUnicodeInAlternativeText() {
    var layout = new ImageMarkup.Layout(ID, 0, 0, 120, 80, 1);
    String alt = "Белешки [draft] \"one\" &copy; <img>\\file!.png";
    String html = Markdown.render(ImageMarkup.markdown(alt, SOURCE, layout), Theme.fallback());

    assertTrue(
        html.contains("alt=\"Белешки [draft] &quot;one&quot; &amp;copy; &lt;img&gt;\\file!.png\""));
    assertTrue(html.contains("data-image-id=\"" + ID + "\""));
    assertFalse(html.contains("<img>"));
    assertFalse(html.contains("©"));
  }

  @Test
  void allowsOnlyEmbeddedRasterSources() {
    assertTrue(ImageMarkup.allowedSource(SOURCE));
    assertTrue(ImageMarkup.allowedSource("data:image/jpeg;base64,/9j/AA=="));
    assertTrue(ImageMarkup.allowedSource("data:image/gif;base64,R0lGODlh"));
    assertTrue(ImageMarkup.allowedSource("data:image/webp;base64,UklGRg=="));
    assertTrue(ImageMarkup.allowedSource("DATA:IMAGE/PNG;BASE64,AA\nAA"));

    for (String invalid :
        new String[] {
          null,
          "",
          "https://example.com/image.png",
          "file:///etc/passwd",
          "javascript:alert(1)",
          "data:text/html;base64,AAAA",
          "data:image/svg+xml;base64,AAAA",
          "data:image/png;base64,",
          "data:image/png;base64,AAAA\" onload=\"alert(1)"
        }) {
      assertFalse(ImageMarkup.allowedSource(invalid), () -> "Accepted: " + invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImageMarkup.markdown(
                "unsafe",
                "https://example.com/image.png",
                new ImageMarkup.Layout(ID, 0, 0, 50, 50, 1)));
  }
}
