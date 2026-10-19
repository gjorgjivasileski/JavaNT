package dev.gorgi.notes.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.markdown.ImageMarkup;
import dev.gorgi.notes.markdown.Markdown;
import dev.gorgi.notes.theme.Theme;
import org.junit.jupiter.api.Test;

class ImageReferencesTest {
  private static final String SOURCE = "data:image/png;base64,AAAA";
  private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

  private static String image(int width) {
    return ImageMarkup.markdown("photo.png", SOURCE,
        new ImageMarkup.Layout(ID, 16, 16, width, 80, 1, false));
  }

  @Test void hidesBothBytesAndLayoutAndRestoresTheExactMarkdown() {
    var references = new ImageReferences();
    String original = "Before\n\n" + image(120) + "\n\nAfter";
    String compact = references.compact(original).text();
    assertEquals("Before\n\n![photo\\.png](image:1)\n\nAfter", compact);
    assertEquals(original, references.expand(compact));
    assertEquals(compact, references.compact(original).text());
    assertEquals(compact, references.compact(compact).text());
    assertEquals(Markdown.render(original, Theme.fallback()),
        Markdown.render(references.expand(compact), Theme.fallback()));
  }

  @Test void retainsImagesAcrossDeletionUndoAndIndependentLayoutRevisions() {
    var references = new ImageReferences();
    String first = references.compact(image(120)).text();
    assertEquals("", references.compact("").text());
    assertEquals(image(120), references.expand(first), "Undo can still resolve a deleted image");
    String resized = references.compact(image(240)).text();
    assertNotEquals(first, resized);
    assertEquals(image(240), references.expand(resized));
    assertEquals(image(120), references.expand(first), "Old references keep their original layout");
  }

  @Test void supportsAltEditsReorderingAndMultipleImagesOnTheSameLine() {
    var references = new ImageReferences();
    String original = image(120) + " and " + image(240);
    String compact = references.compact(original).text();
    assertEquals(original, references.expand(compact));
    assertEquals(original.replace("photo", "Белешки"),
        references.expand(compact.replace("photo", "Белешки")));
    String[] images = compact.split(" and ");
    assertEquals(image(240) + "\n" + image(120), references.expand(images[1] + "\n" + images[0]));
  }

  @Test void leavesLiteralCodeEscapedImagesLinksAndRemoteImagesAlone() {
    var references = new ImageReferences();
    String literal = "```markdown\n" + image(120) + "\n```\n\n"
        + "`" + image(120) + "`\n\n    " + image(120) + "\n\n"
        + "\\![literal](" + SOURCE + ")\n\n"
        + "[link](" + SOURCE + ")\n\n![remote](https://example.org/photo.png)";
    assertEquals(literal, references.compact(literal).text());
    assertEquals(literal, references.expand(literal));
  }

  @Test void handlesPlainImagesTitlesQuotesListsTablesAndEscapedLabels() {
    var references = new ImageReferences();
    String original = "> ![plain [label]](" + SOURCE + " 'A title with ]( punctuation')\n\n"
        + "- ![escaped \\[label\\]](<" + SOURCE + ">)\n\n"
        + "| Photo |\n| --- |\n| ![table](" + SOURCE + ") |\n";
    String compact = references.compact(original).text();
    assertFalse(compact.contains("base64"));
    assertTrue(compact.contains("![plain [label]](image:1)"));
    assertEquals(original, references.expand(compact));
  }

  @Test void neverReassignsUnknownReferencesAndMatchesWholeNumbers() {
    var references = new ImageReferences();
    String original = "![unknown](image:1)\n" + image(120);
    String compact = references.compact(original).text();
    assertTrue(compact.endsWith("(image:2)"));
    assertEquals(original, references.expand(compact));
    assertEquals("image:20 image:999", references.expand("image:20 image:999"));
  }

  @Test void preservesBytesWhenAutosavingIncompleteSyntaxOrNewlyWrappedCode() {
    var references = new ImageReferences();
    String compact = references.compact(image(120)).text();
    assertEquals(image(120).substring(0, image(120).length() - 1),
        references.expand(compact.substring(0, compact.length() - 1)));
    assertEquals("`" + image(120) + "`", references.expand("`" + compact + "`"));
    assertEquals(image(120), references.expand(compact.replace("(image:1)", "(<image:1>)")));
  }

  @Test void doesNotExpandReferenceNamesInLabelsOrOrdinaryText() {
    var references = new ImageReferences();
    String compact = references.compact(image(120)).text();
    String edited = "The reference is image:1. " + compact.replace("photo\\.png", "image:1");
    assertEquals("The reference is image:1. " + image(120).replace("photo\\.png", "image:1"),
        references.expand(edited));
  }

  @Test void mapsCaretAndSelectionAcrossMultipleImages() {
    var references = new ImageReferences();
    String original = "before " + image(120) + " middle " + image(240) + " after";
    var projection = references.compact(original);
    assertEquals(3, projection.position(3));
    assertEquals(projection.text().indexOf(" middle"), projection.position(original.indexOf(" middle")));
    assertEquals(projection.text().length(), projection.position(original.length()));
    assertEquals(projection.text().indexOf(")"), projection.position(original.indexOf("base64")));
  }

  @Test void copyingIntoAFreshEditorKeepsBothBytesAndLayout() {
    var first = new ImageReferences();
    String copied = first.expand(first.compact(image(120)).text());
    var second = new ImageReferences();
    second.compact(image(240));
    String pasted = second.compact(copied).text();
    assertEquals(image(120), second.expand(pasted));
    assertFalse(pasted.contains("base64"));
  }
}
