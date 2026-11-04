package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.theme.Theme;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

class MermaidMarkdownTest {
  @Test
  void recognizesOnlyMermaidFencesAndKeepsNoteScriptsBlocked() {
    String html =
        Markdown.render(
            """
            ```mermaid
            flowchart LR
                A[Start] --> B[Done]
            ```

            ```java
            System.out.println("code");
            ```

            Inline `mermaid` stays inline.
            """,
            Theme.fallback());
    assertEquals(1, html.split("class=\"mermaid-block\"", -1).length - 1);
    assertTrue(html.contains("contenteditable=\"false\""));
    assertTrue(html.contains("role=\"figure\""));
    assertTrue(html.contains("class=\"language-mermaid\""));
    assertTrue(html.contains("class=\"language-java\""));
    assertTrue(html.contains("default-src 'none'"));
    assertFalse(html.contains("<script"));
    assertFalse(html.contains("script-src 'unsafe-inline'"));
  }

  @Test
  void preservesDiagramSourceAfterRenderingAndEditingSurroundingText() throws Exception {
    String source =
        """
        Before

        ```mermaid
        flowchart LR
            A[Белешка ☕] --> B[Done]
        ```

        After
        """;
    var document = parse(source);
    var block = document.getElementsByTagName("pre").item(0);
    var ui = document.createElement("div");
    ui.setAttribute("data-diagram-ui", "true");
    var svg = document.createElement("svg");
    svg.setTextContent("Generated diagram labels, never stored as note text");
    ui.appendChild(svg);
    var button = document.createElement("button");
    button.setTextContent("Edit diagram");
    ui.appendChild(button);
    block.appendChild(ui);
    assertEquals(source, RenderedMarkdown.fromDocument(document));
    document.getElementsByTagName("p").item(0).setTextContent("Edited before");
    assertEquals(
        source.replace("Before", "Edited before"), RenderedMarkdown.fromDocument(document));
    document.getElementsByTagName("code").item(0).setTextContent("flowchart TD\n  A --> C\n");
    assertTrue(
        RenderedMarkdown.fromDocument(document)
            .contains("```mermaid\nflowchart TD\n  A --> C\n```"));
    assertFalse(RenderedMarkdown.fromDocument(document).contains("Generated diagram labels"));
  }

  @Test
  void keepsMalformedAndHtmlLikeDiagramSourceAsEscapedCode() throws Exception {
    String source =
        """
        ```mermaid
        flowchart ???
        <img src="https://example.invalid" onerror="attack()">
        </code><script>attack()</script>
        ```
        """;
    var document = parse(source);
    assertEquals(0, document.getElementsByTagName("script").getLength());
    assertEquals(0, document.getElementsByTagName("img").getLength());
    assertEquals(source, RenderedMarkdown.fromDocument(document));
  }

  private static Document parse(String source) throws Exception {
    String html = Markdown.render(source, Theme.fallback());
    String body = html.substring(html.indexOf("<body"), html.indexOf("</html>"));
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new InputSource(new StringReader("<html>" + body + "</html>")));
  }
}
