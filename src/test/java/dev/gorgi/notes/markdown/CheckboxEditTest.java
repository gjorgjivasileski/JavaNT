package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CheckboxEditTest {
    @Test void usesTheWholeRowWhenCaretIsInTheMiddle() {
        String markdown = "Before\nPrepare **tea** for everyone\nAfter\n";
        var edit = CheckboxEdit.at(markdown, markdown.indexOf("tea") + 1).orElseThrow();
        assertEquals("Before\n- [ ] Prepare **tea** for everyone\nAfter\n", TaskLinks.stripMarkers(edit.change().markdown()));
        assertEquals("Prepare tea for everyone", edit.change().text());
        assertFalse(edit.change().checked());
        assertFalse(edit.removal());
        assertFalse(edit.change().id().isBlank());
        assertEquals(markdown.indexOf("tea") + 1 + 6, edit.caret());
    }

    @Test void retainsBulletNumberNestingAndQuotePrefixes() {
        for (String markdown : List.of("- Do this\n", "+ Do this\n", "4. Do this\n", "2) Do this\n",
                "- Parent\n  * Do this\n", "> 1. Do this\n", "> > - Do this\n")) {
            var change = CheckboxEdit.at(markdown, markdown.indexOf("this")).orElseThrow().change();
            assertEquals(markdown.replace("Do this", "[ ] Do this"), TaskLinks.stripMarkers(change.markdown()));
            assertEquals("Do this", change.text());
        }
    }

    @Test void anExistingCheckboxIsRemovedWithItsLinkedIdentityAndCompletion() {
        var linked = TaskLinks.toggle("- [x] Already **done**\n", 0, true);
        var edit = CheckboxEdit.at(linked.markdown(), 10).orElseThrow();
        assertTrue(edit.removal());
        assertEquals("- Already **done**\n", edit.change().markdown());
        assertEquals(linked.id(), edit.change().id());
        assertEquals("Already done", edit.change().text());
        assertTrue(edit.change().checked());

        var addedAgain = CheckboxEdit.at(edit.change().markdown(), 6).orElseThrow();
        assertFalse(addedAgain.removal());
        assertNotEquals(linked.id(), addedAgain.change().id());
        assertEquals("- [ ] Already **done**\n", TaskLinks.stripMarkers(addedAgain.change().markdown()));
    }

    @Test void anUnlinkedCheckboxIsRemovedWithoutCreatingAnIdentity() {
        for (String flag : List.of(" ", "x", "X")) {
            var edit = CheckboxEdit.at("- [" + flag + "] Remember\n", 8).orElseThrow();
            assertTrue(edit.removal());
            assertEquals("", edit.change().id());
            assertEquals("- Remember\n", edit.change().markdown());
        }
    }

    @Test void removalKeepsPrefixNestingFormattingUnicodeAndLineEndings() {
        for (String prefix : List.of("- ", "+ ", "4. ", "2) ", "> 1. ", "> > - ")) {
            String original = prefix + "[ ] Подготви **чај** ☕\r\n";
            var linked = TaskLinks.toggle(original, 0, false);
            var edit = CheckboxEdit.at(linked.markdown(), linked.markdown().indexOf("чај")).orElseThrow();
            assertEquals(original.replace("[ ] ", ""), edit.change().markdown());
            assertEquals(linked.markdown().indexOf("чај") - 4, edit.caret());
        }
        String nested = "- [ ] Parent\n  * [x] Child\n    continuation\n    - [ ] Grandchild\n";
        var edit = CheckboxEdit.at(nested, nested.indexOf("Child")).orElseThrow();
        assertEquals(nested.replace("[x] Child", "Child"), edit.change().markdown());
    }

    @Test void removalMapsCaretsInPrefixCheckboxLabelMarkerAndTrailingWhitespace() {
        var linked = TaskLinks.toggle("- [ ] **Label**  ", 0, false);
        String markdown = linked.markdown();
        int markerStart = markdown.indexOf("<!--");
        // Use source positions rather than rendered-label positions.
        assertEquals(0, CheckboxEdit.at(markdown, -9).orElseThrow().caret());
        assertEquals(1, CheckboxEdit.at(markdown, 1).orElseThrow().caret());
        assertEquals(2, CheckboxEdit.at(markdown, 4).orElseThrow().caret());
        assertEquals(6, CheckboxEdit.at(markdown, 10).orElseThrow().caret());
        assertEquals("- **Label**".length(), CheckboxEdit.at(markdown, markerStart + 8).orElseThrow().caret());
        assertEquals("- **Label**  ".length(), CheckboxEdit.at(markdown, Integer.MAX_VALUE).orElseThrow().caret());
        assertEquals("- **Label** ".length(), CheckboxEdit.at(markdown, markdown.length() - 1).orElseThrow().caret());
    }

    @Test void removalCaretIncludesTheEscapeNeededForLiteralBlockPunctuation() {
        String source = "- [ ] [x] literal label";
        int caret = source.indexOf("literal") + 3;
        var edit = CheckboxEdit.at(source, caret).orElseThrow();
        assertEquals("- \\[x] literal label", edit.change().markdown());
        assertEquals(edit.change().markdown().indexOf("literal") + 3, edit.caret());

        source = "- [ ] 12. literal label";
        caret = source.indexOf("literal") + 3;
        edit = CheckboxEdit.at(source, caret).orElseThrow();
        assertEquals("- 12\\. literal label", edit.change().markdown());
        assertEquals(edit.change().markdown().indexOf("literal") + 3, edit.caret());
    }

    @Test void handlesEmptyRowsUnicodeAndWindowsLineEndings() {
        var blank = CheckboxEdit.at("Before\r\n\r\nAfter", 8).orElseThrow();
        assertEquals("Before\r\n- [ ] New task\r\nAfter", TaskLinks.stripMarkers(blank.change().markdown()));
        assertEquals("New task", blank.change().text());
        var unicode = CheckboxEdit.at("Подготви чај ☕\r\n", 5).orElseThrow();
        assertEquals("- [ ] Подготви чај ☕\r\n", TaskLinks.stripMarkers(unicode.change().markdown()));
    }

    @Test void aPlainCheckboxBecomesAListWithoutADuplicateCheckbox() {
        var change = CheckboxEdit.at("[ ] Remember\n", 5).orElseThrow().change();
        assertEquals("- [ ] Remember\n", TaskLinks.stripMarkers(change.markdown()));
        assertEquals("Remember", change.text());
    }

    @Test void doesNotCreateTasksFromCodeExamples() {
        assertTrue(CheckboxEdit.at("```\nExample\n```\n", 7).isEmpty());
        assertTrue(CheckboxEdit.at("    Example\n", 7).isEmpty());
        assertTrue(CheckboxEdit.at("```\n- [ ] Example\n```\n", 12).isEmpty());
        assertTrue(CheckboxEdit.at("    - [ ] Example\n", 12).isEmpty());
    }
}
