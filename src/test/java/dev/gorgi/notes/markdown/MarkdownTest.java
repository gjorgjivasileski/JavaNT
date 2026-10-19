package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.theme.Theme;
import org.junit.jupiter.api.Test;

class MarkdownTest {
    @Test void onlyActualCheckboxItemsGetTheBulletHidingClass() {
        String html = Markdown.render("""
                - Plain bullet
                - [ ] Checkbox
                - \\[ ] Literal checkbox
                - Parent bullet
                  - [x] Nested checkbox
                """, Theme.fallback());
        assertTrue(html.contains("<li>Plain bullet</li>"));
        assertTrue(html.contains("<li>[ ] Literal checkbox</li>"));
        assertTrue(html.contains("<li>Parent bullet"));
        assertEquals(2, java.util.regex.Pattern.compile("<li class=\"task-list-item\">")
                .matcher(html).results().count());
        assertTrue(html.substring(html.indexOf("<body")).contains("<ul>\n<li>Plain bullet</li>"));
        assertEquals(1, java.util.regex.Pattern.compile("<ul class=\"task-list\">")
                .matcher(html).results().count());
    }

    @Test void taskOnlyListsGetIndentationControlWithoutChangingOrdinaryOrMixedLists() {
        String html = Markdown.render("""
                - [ ] Checkbox
                - [x] Done

                Paragraph

                1. Ordinary numbered item
                2. Another numbered item

                Paragraph

                1. [ ] Numbered checkbox
                2. [x] Numbered done
                """, Theme.fallback());
        assertTrue(html.contains("<ul class=\"task-list\">"));
        assertTrue(html.contains("<ol>\n<li>Ordinary numbered item</li>"));
        assertTrue(html.contains("<ol class=\"task-list\">"));
    }

    @Test void managedImagesRenderValidatedPlacementAndSafeHtmlAttributes() {
        var layout = new ImageMarkup.Layout("123e4567-e89b-12d3-a456-426614174000", 10, 20, 300, 200, -2);
        String html = Markdown.render(ImageMarkup.markdown("A photo", "data:image/png;base64,AAAA", layout),
                Theme.fallback());
        assertTrue(html.contains("class=\"note-image\""));
        assertTrue(html.contains("data-image-x=\"10\""));
        assertTrue(html.contains("data-image-y=\"20\""));
        assertTrue(html.contains("data-image-width=\"300\""));
        assertTrue(html.contains("data-image-height=\"200\""));
        assertTrue(html.contains("data-image-z=\"-2\""));
        assertTrue(html.contains("style=\"" + layout.style() + "\""));
        assertTrue(html.contains("contenteditable=\"false\""));
        assertTrue(html.contains("draggable=\"false\""));
        assertTrue(html.contains("title=\"A photo\""));
        assertFalse(html.contains("javafxnotes-image:"));
    }

    @Test void malformedImageTitlesAndBlockedUrlsNeverBecomePositionedImages() {
        String title = new ImageMarkup.Layout("123e4567-e89b-12d3-a456-426614174000", 0, 0, 100, 100, 1).title();
        String html = Markdown.render("![bad](data:image/png;base64,AAAA \"" + title.replace("width=100", "width=0")
                + "\")\n\n![remote](https://example.com/image.png \"" + title + "\")", Theme.fallback());
        assertFalse(html.contains("data-image-id="));
        assertFalse(html.contains("class=\"note-image\""));
        assertFalse(html.contains("src=\"https://"));
        assertTrue(html.contains("alt=\"bad\""));
    }

    @Test void emptyLinkedLabelsKeepTheirOwnCheckboxAndDoNotShiftFollowingTaskIds() {
        String id = "954d99a7-25cf-47ec-9b1d-8cf059c028e0";
        String source = "- [ ] <!-- task:" + id + " -->\n- [ ] Actual\n";
        String html = Markdown.render(source, Theme.fallback());
        var inputs = java.util.regex.Pattern.compile("<input\\b[^>]*>").matcher(html).results().map(r -> r.group()).toList();
        assertEquals(2, inputs.size());
        assertTrue(inputs.get(0).contains("data-task-index=\"0\""));
        assertTrue(inputs.get(0).contains("data-task-id=\"" + id + "\""));
        assertTrue(inputs.get(1).contains("data-task-index=\"1\""));
        assertFalse(inputs.get(1).contains("data-task-id="));
        assertFalse(html.contains("&lt;!-- task:"));
        assertEquals("Actual", TaskLinks.toggle(source, 1, true).text());
    }

    @Test void escapedCheckboxesStayLiteralAndDoNotShiftInteractiveCheckboxOrdinals() {
        String source = "- \\[ ] Escaped\n- [ ] Actual\n- \\[x] Escaped checked\n";
        String html = Markdown.render(source, Theme.fallback());
        var inputs = java.util.regex.Pattern.compile("<input\\b[^>]*>").matcher(html).results().map(r -> r.group()).toList();
        assertEquals(TaskLinks.entries(source).size(), inputs.size());
        assertEquals(1, inputs.size());
        assertTrue(inputs.getFirst().contains("data-task-index=\"0\""));
        assertTrue(html.contains("<li>[ ] Escaped</li>"));
        assertTrue(html.contains("<li>[x] Escaped checked</li>"));
        var change = TaskLinks.toggle(source, 0, true);
        assertEquals("Actual", change.text());
        assertTrue(change.markdown().startsWith("- \\[ ] Escaped\n- [x] Actual"));
    }

    @Test void hidesOnlyRecognizedTaskMetadataAndKeepsOtherHtmlLiteral() {
        String marker = "<!-- task:954d99a7-25cf-47ec-9b1d-8cf059c028e0 -->";
        String source = "- [ ] Linked " + marker + "\n- [ ] Copy " + marker + "\n\n"
                + "Paragraph " + marker + "\n\n- [ ] Escaped \\" + marker + "\n\n"
                + "- [ ] <em>literal</em>\n\n```\n- [ ] Code " + marker + "\n```\n";
        String html = Markdown.render(source, Theme.fallback());
        assertEquals(3, java.util.regex.Pattern.compile("&lt;!-- task:").matcher(html).results().count());
        assertTrue(html.contains("&lt;em&gt;literal&lt;/em&gt;"));
        assertFalse(html.contains("<em>literal</em>"));
        assertEquals(1, java.util.regex.Pattern.compile("data-task-id=").matcher(html).results().count());
    }

    @Test void onlyExplicitExternalSchemesAreOpenedByTheDesktop() {
        assertFalse(Markdown.isExternalLink(null));
        assertFalse(Markdown.isExternalLink(""));
        assertFalse(Markdown.isExternalLink("#heading"));
        assertFalse(Markdown.isExternalLink("other-note.md"));
        assertFalse(Markdown.isExternalLink("file:///etc/passwd"));
        assertFalse(Markdown.isExternalLink("data:text/html,hello"));
        assertTrue(Markdown.isExternalLink("HTTPS://example.com"));
        assertTrue(Markdown.isExternalLink("mailto:notes@example.com"));
    }
    @Test
    void rendersTheMarkdownFeaturesUsedForNotesAndTasks() {
        String html = Markdown.render("""
                # Weekly notes

                **Important** and *useful*, with ~~old plans~~.

                - [ ] Write the notes
                - [x] Make tea

                | Task | Owner |
                | --- | --- |
                | Review | Gorgi |

                ```java
                System.out.println("hello");
                ```
                """, Theme.fallback());

        assertAll(
                () -> assertTrue(html.contains("<h1>Weekly notes</h1>")),
                () -> assertTrue(html.contains("<strong>Important</strong>")),
                () -> assertTrue(html.contains("<em>useful</em>")),
                () -> assertTrue(html.contains("<del>old plans</del>")),
                () -> assertTrue(html.contains("type=\"checkbox\"")),
                () -> assertTrue(html.contains("checked")),
                () -> assertTrue(html.contains("<table>")),
                () -> assertTrue(html.contains("<td>Gorgi</td>")),
                () -> assertTrue(html.contains("<pre><code class=\"language-java\">")));
    }

    @Test
    void importedHtmlStaysLiteralInsteadOfBecomingExecutableMarkup() {
        String html = Markdown.render("""
                <script>alert('unsafe')</script>

                <img src="https://example.com/tracker" onerror="alert(1)">

                <iframe src="file:///etc/passwd"></iframe>
                """, Theme.fallback());

        assertAll(
                () -> assertTrue(html.contains("&lt;script&gt;")),
                () -> assertTrue(html.contains("&lt;img")),
                () -> assertTrue(html.contains("&lt;iframe")),
                () -> assertFalse(html.contains("<script")),
                () -> assertFalse(html.contains("<img")),
                () -> assertFalse(html.contains("<iframe")));
    }

    @Test
    void unsafeLinkSchemesAreRemovedButWebAndMailLinksSurvive() {
        String html = Markdown.render("""
                [script](javascript:alert%281%29)
                [data](data:text/html;base64,PHNjcmlwdD4=)
                [file](file:///etc/passwd)
                [website](https://example.com/notes?q=hello)
                [mail](mailto:notes@example.com)
                """, Theme.fallback());

        assertAll(
                () -> assertFalse(html.contains("href=\"javascript:")),
                () -> assertFalse(html.contains("href=\"data:")),
                () -> assertFalse(html.contains("href=\"file:")),
                () -> assertTrue(html.contains("href=\"https://example.com/notes?q=hello\"")),
                () -> assertTrue(html.contains("href=\"mailto:notes@example.com\"")));
    }

    @Test
    void previewRestrictsExternalResources() {
        String html = Markdown.render("![image](https://example.com/tracker.png)", Theme.fallback());

        assertAll(
                () -> assertTrue(html.contains("Content-Security-Policy")),
                () -> assertTrue(html.contains("default-src 'none'")),
                () -> assertTrue(html.contains("img-src data:")));
    }

    @Test
    void preservesUnicodeAndEscapesCodeAsText() {
        String html = Markdown.render("## Белешки ☕\n\n`<tag> & value`\n", Theme.fallback());

        assertAll(
                () -> assertTrue(html.contains("Белешки ☕")),
                () -> assertTrue(html.contains("<code>&lt;tag&gt; &amp; value</code>")),
                () -> assertFalse(html.contains("<tag>")));
    }
}
