package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.theme.Theme;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

class RenderedMarkdownTest {
    private static final String FIRST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECOND_ID = "123e4567-e89b-12d3-a456-426614174001";

    @Test void managedImageLayoutSurvivesMarkdownHtmlAndBrowserDomRoundTrips() throws Exception {
        var layout = new ImageMarkup.Layout(FIRST_ID, 12, 34, 320, 240, -1);
        String source = ImageMarkup.markdown("[Photo] &copy;", "data:image/png;base64,AAAA", layout);
        String html = Markdown.render(source, Theme.fallback());
        String body = html.substring(html.indexOf("<body"), html.indexOf("</html>"));
        var document = (Document) browserAttributes(parse("<html>" + body + "</html>"));

        assertEquals(source + "\n", RenderedMarkdown.fromDocument(document));
        Element image = (Element) document.getElementsByTagName("img").item(0);
        image.setAttribute("data-image-width", "480");
        image.setAttribute("data-image-height", "360");
        image.setAttribute("data-image-x", "120");
        image.setAttribute("data-image-y", "160");
        image.setAttribute("data-image-z", "3");

        var changed = new ImageMarkup.Layout(FIRST_ID, 120, 160, 480, 360, 3);
        assertEquals(ImageMarkup.markdown("[Photo] &copy;", "data:image/png;base64,AAAA", changed) + "\n",
                RenderedMarkdown.fromDocument(document));
        image.setAttribute("data-image-mode", "inline");
        var inline = new ImageMarkup.Layout(FIRST_ID, 120, 160, 480, 360, 3, false);
        assertEquals(ImageMarkup.markdown("[Photo] &copy;", "data:image/png;base64,AAAA", inline) + "\n",
                RenderedMarkdown.fromDocument(document));
    }

    @Test void malformedImageAttributesCannotWriteUnsafeLayoutOrDestinations() throws Exception {
        assertEquals("![photo](<data:image/png;base64,AAAA> \"Photo\")\n", convert("""
                <p><img alt="photo" title="Photo" src="data:image/png;base64,AAAA"
                  data-image-id="%s" data-image-x="0" data-image-y="0"
                  data-image-width="100;left:999px" data-image-height="100" data-image-z="1"
                  style="width:200px;position:fixed"/></p>
                """.formatted(FIRST_ID)));
        assertEquals("remote\n", convert("""
                <p><img alt="remote" src="https://example.com/tracker"
                  data-image-id="%s" data-image-x="0" data-image-y="0"
                  data-image-width="100" data-image-height="100" data-image-z="1"/></p>
                """.formatted(FIRST_ID)));
    }

    @Test void excludesImageSelectionHandlesAndMenusFromStoredMarkdown() throws Exception {
        assertEquals("Before\n\nAfter\n", convert("""
                <p>Before<span data-image-ui="true">Resize</span></p>
                <div data-image-ui="true"><p>Bring to front</p><span>Delete</span></div>
                <div>After</div>
                """));
    }

    @Test void convertsUnlinkedCheckboxWhenTheBrowserReturnsNullForItsMissingId() throws Exception {
        var document = (Document) browserAttributes(parse("""
                <html><body><ul><li><input type="checkbox"/> Unlinked task</li></ul></body></html>
                """));
        assertEquals("- [ ] Unlinked task\n", RenderedMarkdown.fromDocument(document));
    }

    @Test void convertsFormattingWhenOptionalBrowserAttributesAreNull() throws Exception {
        var document = (Document) browserAttributes(parse("""
                <html><body>
                <pre><code>plain code</code></pre>
                <p><a>Label</a><img/><img src="data:image/png;base64,AAAA"/></p>
                <table><tr><th>Heading</th></tr><tr><td>Value</td></tr></table>
                <ol><li>First</li></ol>
                </body></html>
                """));
        assertEquals("""
                ```
                plain code
                ```

                Label![](<data:image/png;base64,AAAA>)

                | Heading |
                | --- |
                | Value |

                1. First
                """, RenderedMarkdown.fromDocument(document));
    }

    @Test void convertsHeadingsParagraphsAndInlineStylesToEditableMarkdown() throws Exception {
        assertEquals("""
                # Weekly notes

                **Important** and *useful*, with ~~old plans~~.

                ## Белешки ☕

                A `code` fragment.
                """, convert("""
                <h1>Weekly notes</h1>
                <p><strong>Important</strong> and <em>useful</em>, with <del>old plans</del>.</p>
                <h2>Белешки ☕</h2>
                <p>A <code>code</code> fragment.</p>
                """));
    }

    @Test void retainsNewParagraphsAndLineBreaksInsertedByContentEditable() throws Exception {
        assertEquals("First\n\nSecond  \nThird\n\nFourth\n", convert(
                "First<div>Second<br/>Third</div><div><span>Fourth</span></div>"));
        assertEquals("A **bold** word\n", convert("<div>A <b>bold</b> word</div>"));
        assertEquals("**bold** word\n", convert("<p><strong>bold </strong>word</p>"));
    }

    @Test void emptyBodiesAndContentEditablePlaceholdersRemainEmpty() throws Exception {
        assertEquals("", convert(""));
        assertEquals("", convert("<div><br/></div>"));
        assertEquals("", convert("<p>  &#160; </p>"));
        assertEquals("", RenderedMarkdown.fromDocument(null));
        assertEquals("", RenderedMarkdown.fromDocument(parse("<html><head><title>Title</title></head></html>")));
    }

    @Test void keepsNestedOrderedAndBulletListsInTheirOriginalHierarchy() throws Exception {
        assertEquals("""
                3. First
                   - Nested
                     - Deeper
                4. Second
                """, convert("""
                <ol start="3">
                  <li>First<ul><li>Nested<ul><li>Deeper</li></ul></li></ul></li>
                  <li>Second</li>
                </ol>
                """));
    }

    @Test void retainsLooseListParagraphs() throws Exception {
        assertEquals("- First paragraph\n\n  Second paragraph\n- Next item\n",
                convert("<ul><li><p>First paragraph</p><p>Second paragraph</p></li><li>Next item</li></ul>"));
    }

    @Test void keepsTaskIdsOnTheirOwnFirstLinesBeforeNestedChildren() throws Exception {
        assertEquals("""
                - [x] **Parent** <!-- task:%s -->
                  - [ ] Child <!-- task:%s -->
                - [ ] Unlinked
                """.formatted(FIRST_ID, SECOND_ID), convert("""
                <ul>
                  <li><input type="checkbox" checked="checked" data-task-id="%s"/> <strong>Parent</strong>
                    <ul><li><input type="checkbox" data-task-id="%s"/> Child</li></ul>
                  </li>
                  <li><input type="checkbox"/> Unlinked</li>
                </ul>
                """.formatted(FIRST_ID, SECOND_ID)));
    }

    @Test void doesNotGiveTheParentItsNestedChildsCheckboxOrIdentity() throws Exception {
        assertEquals("- Parent\n  - [x] Child <!-- task:" + FIRST_ID + " -->\n", convert("""
                <ul><li>Parent<ul><li><input type="checkbox" checked="" data-task-id="%s"/> Child</li></ul></li></ul>
                """.formatted(FIRST_ID)));
    }

    @Test void placesTaskMarkerBeforeSoftWrappedAndLooseContinuationLines() throws Exception {
        String markdown = convert("""
                <ul><li><p><input type="checkbox" data-task-id="%s"/> Task<br/>continued</p><p>Details</p></li></ul>
                """.formatted(FIRST_ID));
        assertEquals("- [ ] Task <!-- task:" + FIRST_ID + " -->  \n  continued\n\n  Details\n", markdown);
    }

    @Test void rejectsNonUuidLinkageInsteadOfWritingArbitraryComments() throws Exception {
        assertEquals("- [ ] Task\n", convert(
                "<ul><li><input type='checkbox' data-task-id='unsafe --&gt; content'/> Task</li></ul>"));
    }

    @Test void preservesBlockquotesFencedCodeLanguagesAndLiteralBackticks() throws Exception {
        assertEquals("""
                > Quoted **text**
                >
                > - First
                > - Second

                ````java
                if (ready) {
                    print("```");
                }
                ````
                """, convert("""
                <blockquote><p>Quoted <strong>text</strong></p><ul><li>First</li><li>Second</li></ul></blockquote>
                <pre><code class="highlight language-java">if (ready) {
                    print("```");
                }
                </code></pre>
                """));
        assertEquals("`` `inside` `` and `  spaces  `\n",
                convert("<p><code>`inside`</code> and <code> spaces </code></p>"));
    }

    @Test void preservesAllowedLinksAndDropsUnsafeDestinations() throws Exception {
        String markdown = convert("""
                <p><a href="https://example.com/notes?q=hello" title="A title">website</a>,
                <a href="mailto:notes@example.com">mail</a>, <a href="#heading">heading</a>,
                <a href="file:///etc/passwd">file</a>, <a href="javascript:alert(1)">script</a></p>
                """);
        assertEquals("[website](<https://example.com/notes?q=hello> \"A title\"), "
                + "[mail](<mailto:notes@example.com>), [heading](<#heading>), file, script\n", markdown);
        assertFalse(Markdown.render(markdown, Theme.fallback()).contains("href=\"file:"));
    }

    @Test void preservesTableFormattingAlignmentAndLiteralPipes() throws Exception {
        assertEquals("""
                | Task | Owner | Value |
                | :--- | :---: | ---: |
                | **Review** | Gorgi \\| Alex | `a\\|b` |
                | Next | One two |  |
                """, convert("""
                <table>
                  <thead><tr><th align="left">Task</th><th style="text-align: center;">Owner</th><th align="right">Value</th></tr></thead>
                  <tbody><tr><td><strong>Review</strong></td><td>Gorgi | Alex</td><td><code>a|b</code></td></tr>
                  <tr><td>Next</td><td>One<br/>two</td></tr></tbody>
                </table>
                """));
    }

    @Test void keepsLiteralMarkdownPunctuationAsTextWhenPreviewIsEdited() throws Exception {
        String markdown = convert("""
                <p>*stars* [link] &lt;tag&gt; #heading ~~strike~~ &amp; text</p>
                <div>- not a list</div><div>1. not a list</div><div>+ not a list</div>
                """);
        assertEquals("\\*stars\\* \\[link\\] \\<tag\\> \\#heading \\~\\~strike\\~\\~ & text\n\n"
                + "\\- not a list\n\n1\\. not a list\n\n\\+ not a list\n", markdown);
        String rendered = Markdown.render(markdown, Theme.fallback());
        assertFalse(rendered.contains("<ul>"));
        assertFalse(rendered.contains("<ol>"));
        assertFalse(rendered.contains("<em>"));
    }

    @Test void ignoresActiveAndNonContentElementsButRetainsSafeWrapperText() throws Exception {
        assertEquals("Safe wrapped\n\n---\n\nAfter\n", convert("""
                <script>unsafe()</script><style>body { color: red }</style>
                <p onclick="unsafe()">Safe <span class="arbitrary">wrapped</span><button>Ignore</button></p>
                <iframe>Ignore frame</iframe><svg><text>Ignore image</text></svg>
                <!-- Ignore comments -->
                <hr/><div>After</div>
                """));
    }

    @Test void keepsEntityLookingTextAndEmptyListMarkersLiteral() throws Exception {
        String markdown = convert("<p>&amp;copy; &amp;#169; &amp; text</p><p>---</p><p>1.</p><p>+</p><p>===</p>");
        assertEquals("\\&copy; \\&\\#169; & text\n\n\\---\n\n1\\.\n\n\\+\n\n\\===\n", markdown);
        String rendered = Markdown.render(markdown, Theme.fallback());
        assertTrue(rendered.contains("&amp;copy;"));
        assertFalse(rendered.contains("<hr"));
        assertFalse(rendered.contains("<ol>"));
    }

    @Test void supportsUppercaseHtmlDomNamesAndRetainsSafeEmbeddedImages() throws Exception {
        assertEquals("## Title\n\n![pixel](<data:image/png;base64,AAAA>)\n",
                RenderedMarkdown.fromDocument(parse("<HTML><HEAD/><BODY><H2>Title</H2><P>"
                        + "<IMG alt='pixel' src='data:image/png;base64,AAAA'/></P></BODY></HTML>")));
        assertEquals("remote\n", convert("<p><img alt='remote' src='https://example.com/tracker'/></p>"));
    }

    private static String convert(String body) throws Exception {
        return RenderedMarkdown.fromDocument(parse("<html><head><title>Ignored</title></head><body>" + body + "</body></html>"));
    }

    private static Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /** Model WebKit's missing-attribute result; the XML fixture parser normally returns an empty string. */
    private static Node browserAttributes(Node node) {
        Class<?> type = node instanceof Document ? Document.class : node instanceof Element ? Element.class : Node.class;
        return (Node) Proxy.newProxyInstance(RenderedMarkdownTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("getAttribute") && node instanceof Element element && !element.hasAttribute((String) args[0]))
                return null;
            try {
                var result = method.invoke(node, args);
                return result instanceof Node child ? browserAttributes(child) : result;
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
}
