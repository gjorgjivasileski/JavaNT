package dev.gorgi.notes.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.gorgi.notes.model.Workspace;
import dev.gorgi.notes.theme.Theme;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskLinksTest {
    private static final String FIRST_ID = "954d99a7-25cf-47ec-9b1d-8cf059c028e0";
    private static final String SECOND_ID = "69201c72-a85c-472a-b14c-ce3904ea169e";
    private static String marker(String id) { return "<!-- task:" + id + " -->"; }

    @Test void findsOnlyRenderedCheckboxesIncludingNestedOrderedAndQuotedItems() {
        var markdown = """
                ```markdown
                - [ ] fenced example
                ```

                    - [ ] indented code

                [ ] ordinary paragraph

                - [ ] **First** with [a link](https://example.com)
                  - [x] Nested `code`

                > 1. [X] Quoted

                <div>
                - [ ] raw HTML
                </div>
                """;
        var entries = TaskLinks.entries(markdown);
        assertEquals(List.of("First with a link", "Nested code", "Quoted"), entries.stream().map(TaskLinks.Entry::text).toList());
        assertEquals(List.of(false, true, true), entries.stream().map(TaskLinks.Entry::checked).toList());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            assertEquals(i, entry.ordinal());
            assertEquals('[', markdown.charAt(entry.start()));
            assertEquals("", entry.id());
        }
    }

    @Test void clickCreatesOneIndependentTaskAndSubsequentClicksReuseItsIdentity() {
        String original = "# Notes\n\n- [ ] Do something\n";
        var first = TaskLinks.toggle(original, 0, true);
        var tasks = TaskLinks.link("note-id", first, List.of());
        assertTrue(first.markdown().contains("- [x] Do something " + marker(first.id())));
        assertEquals("Do something", tasks.getFirst().text());
        assertTrue(tasks.getFirst().done());
        assertEquals("note-id", tasks.getFirst().sourceNoteId());
        assertNotEquals(first.id(), tasks.getFirst().id());

        var second = TaskLinks.toggle(first.markdown(), 0, false);
        var updated = TaskLinks.link("note-id", second, tasks);
        assertEquals(first.id(), second.id());
        assertEquals(1, updated.size());
        assertEquals(tasks.getFirst().id(), updated.getFirst().id());
        assertFalse(updated.getFirst().done());
        assertEquals(original, TaskLinks.stripMarkers(second.markdown()));
    }

    @Test void duplicateLabelsAndReorderingKeepTheirOwnTaskIdentities() {
        var first = TaskLinks.toggle("- [ ] Same\n- [ ] Same\n", 0, false);
        var second = TaskLinks.toggle(first.markdown(), 1, true);
        var tasks = TaskLinks.link("n", second, TaskLinks.link("n", first, List.of()));
        String reordered = "- [x] Renamed " + marker(second.id()) + "\n- [ ] Same " + marker(first.id()) + "\n";
        var reconciled = TaskLinks.reconcile("n", reordered, tasks);
        assertEquals(tasks.get(0).id(), reconciled.get(0).id());
        assertEquals("Same", reconciled.get(0).text());
        assertEquals(tasks.get(1).id(), reconciled.get(1).id());
        assertEquals("Renamed", reconciled.get(1).text());
        assertTrue(reconciled.get(1).done());
        String completed = TaskLinks.update(reordered, first.id(), true);
        assertTrue(TaskLinks.entries(completed).stream().allMatch(TaskLinks.Entry::checked));
    }

    @Test void deletingSourceDetachesTaskAndDoesNotAffectIndependentOrOtherNoteTasks() {
        var linked = new Workspace.Todo("task", "Keep me", true, "note", FIRST_ID);
        var independent = new Workspace.Todo("independent", "Own task", false);
        var other = new Workspace.Todo("other", "Different note", true, "other-note", SECOND_ID);
        var tasks = TaskLinks.reconcile("note", "The checklist was deleted.", List.of(linked, independent, other));
        assertEquals(new Workspace.Todo("task", "Keep me", true), tasks.get(0));
        assertEquals(independent, tasks.get(1));
        assertEquals(other, tasks.get(2));
    }

    @Test void linkedTaskDetailsSurviveCheckboxClicksRenamesAndSourceDeletion() {
        var first = TaskLinks.toggle("- [ ] Original label\n", 0, false);
        var initial = TaskLinks.link("note", first, List.of()).getFirst()
                .withMarkdown("# Details\n\nKeep **my separate content**.\n\n- [ ] Substep\n");

        var clicked = TaskLinks.toggle(first.markdown(), 0, true);
        var afterClick = TaskLinks.link("note", clicked, List.of(initial)).getFirst();
        assertEquals(initial.id(), afterClick.id());
        assertEquals(initial.markdown(), afterClick.markdown());
        assertTrue(afterClick.done());

        var renamed = TaskLinks.update(clicked.markdown(), first.id(), false, "Edited row");
        var reconciled = TaskLinks.reconcile("note", renamed, List.of(afterClick)).getFirst();
        assertEquals("Edited row", reconciled.text());
        assertFalse(reconciled.done());
        assertEquals(initial.markdown(), reconciled.markdown());

        var detached = TaskLinks.reconcile("note", "The source row was removed.", List.of(reconciled)).getFirst();
        assertEquals(reconciled.withoutLink(), detached);
        assertEquals(initial.id(), detached.id());
        assertEquals(initial.markdown(), detached.markdown());
        assertFalse(detached.linked());
    }

    @Test void plainMarkdownCheckboxesDoNotCreateTasksUntilClicked() {
        var independent = new Workspace.Todo("independent", "My task", false);
        assertEquals(List.of(independent), TaskLinks.reconcile("note", "- [ ] New checklist item", List.of(independent)));
    }

    @Test void sidebarCompletionPreservesFormattingLineEndingsAndNestedContent() {
        String original = "> 1. [ ] **Buy** [tea](https://example.com) " + marker(FIRST_ID) + "  \r\n"
                + ">    continued text\r\n>    - [ ] child\r\n";
        String updated = TaskLinks.update(original, FIRST_ID, true);
        assertEquals(original.replace("1. [ ]", "1. [x]"), updated);
        assertEquals("Buy tea", TaskLinks.entries(updated).getFirst().text());
    }

    @Test void sidebarRenameIsLiteralAndPreservesContinuationAndLink() {
        String original = "- [ ] **Original** " + marker(FIRST_ID) + "  \n  continuation\n  - [ ] nested\n";
        String updated = TaskLinks.update(original, FIRST_ID, true, "New *literal* [label] & <tag>");
        assertEquals("New *literal* [label] & <tag>", TaskLinks.entries(updated).getFirst().text());
        assertTrue(updated.contains("New \\*literal\\* \\[label\\] \\& \\<tag\\> " + marker(FIRST_ID)));
        assertTrue(updated.endsWith("  \n  continuation\n  - [ ] nested\n"));
        assertEquals(FIRST_ID, TaskLinks.entries(updated).getFirst().id());
    }

    @Test void unchangedSidebarLabelRetainsMarkdownFormatting() {
        String original = "- [ ] **Original** " + marker(FIRST_ID) + "\n";
        assertEquals(original.replace("[ ]", "[x]"), TaskLinks.update(original, FIRST_ID, true, "Original"));
    }

    @Test void markerInsertionPreservesHardBreaksAndUnicodeOffsets() {
        String original = "# 🌍\r\n\r\n- [ ] Подготви чај ☕  \r\n  and rest\r\n";
        var change = TaskLinks.toggle(original, 0, false);
        assertEquals("Подготви чај ☕", change.text());
        assertTrue(change.markdown().contains(marker(change.id()) + "  \r\n"));
        assertEquals(original, TaskLinks.stripMarkers(change.markdown()));
    }

    @Test void stripsOnlyAppMetadataOnActualTaskLines() {
        String example = "```markdown\n- [ ] Example " + marker(FIRST_ID) + "\n```\n\n"
                + "A paragraph " + marker(FIRST_ID) + "\n\n"
                + "- [ ] Literal \\" + marker(SECOND_ID) + "\n"
                + "- [ ] Actual " + marker(FIRST_ID) + "\n";
        assertEquals(example.substring(0, example.lastIndexOf("- [ ] Actual")) + "- [ ] Actual\n", TaskLinks.stripMarkers(example));
        assertEquals("", TaskLinks.entries(example).getFirst().id());
    }

    @Test void copiedMarkerCreatesASeparateIdentityWhenClicked() {
        String source = "- [ ] Original " + marker(FIRST_ID) + "\n- [ ] Copy " + marker(FIRST_ID) + "\n";
        assertEquals("", TaskLinks.entries(source).get(1).id());
        var change = TaskLinks.toggle(source, 1, true);
        assertNotEquals(FIRST_ID, change.id());
        assertEquals(FIRST_ID, TaskLinks.entries(change.markdown()).getFirst().id());
        assertEquals(change.id(), TaskLinks.entries(change.markdown()).get(1).id());
    }

    @Test void labelsUseInlineMarkdownRules() {
        assertEquals("# chapter & !important", TaskLinks.entries("- [ ] # chapter &amp; !important\n").getFirst().text());
        assertEquals("", TaskLinks.entries("- [ ] " + marker(FIRST_ID) + "\n").getFirst().text());
    }

    @Test void staleReferencesDoNotModifyOtherChecklistItems() {
        String source = "- [ ] Still here " + marker(FIRST_ID) + "\n";
        assertEquals(source, TaskLinks.update(source, SECOND_ID, true));
        assertEquals(source, TaskLinks.update(source, "", true));
        assertThrows(IllegalArgumentException.class, () -> TaskLinks.toggle(source, 1, true));
        assertThrows(IllegalArgumentException.class, () -> TaskLinks.toggle(source, -1, true));
    }

    @Test void removingCheckboxPreservesLabelFormattingAndDescendants() {
        String source = "> 1. [X] **Buy** [tea](https://example.com) " + marker(FIRST_ID) + "  \r\n"
                + ">    continued text\r\n>    - [ ] child " + marker(SECOND_ID) + "\r\n";
        var change = TaskLinks.removeCheckbox(source, 0);
        assertEquals("> 1. **Buy** [tea](https://example.com)  \r\n"
                + ">    continued text\r\n>    - [ ] child " + marker(SECOND_ID) + "\r\n", change.markdown());
        assertEquals(FIRST_ID, change.id());
        assertEquals("Buy tea", change.text());
        assertTrue(change.checked());
        assertEquals(SECOND_ID, TaskLinks.entries(change.markdown()).getFirst().id());
    }

    @Test void removingOneOfDuplicateLabelsUsesItsOrdinalAndOriginalIdentity() {
        String source = "- [ ] Same " + marker(FIRST_ID) + "\n- [x] Same " + marker(SECOND_ID) + "\n";
        var change = TaskLinks.removeCheckbox(source, 1);
        assertEquals("- [ ] Same " + marker(FIRST_ID) + "\n- Same\n", change.markdown());
        assertEquals(SECOND_ID, change.id());
        assertTrue(change.checked());
        assertEquals(FIRST_ID, TaskLinks.entries(change.markdown()).getFirst().id());
    }

    @Test void removingCopiedMarkerDoesNotReturnTheOriginalTasksIdentity() {
        String source = "- [ ] Original " + marker(FIRST_ID) + "\n- [ ] Copy " + marker(FIRST_ID) + "\n";
        var change = TaskLinks.removeCheckbox(source, 1);
        assertEquals("- [ ] Original " + marker(FIRST_ID) + "\n- Copy\n", change.markdown());
        assertEquals("", change.id());
        assertEquals("Copy", change.text());
    }

    @Test void removingUnlinkedCheckboxPreservesLiteralMarkersAndCodeExamples() {
        String examples = "```markdown\n- [ ] Example " + marker(FIRST_ID) + "\n```\n\n"
                + "    - [ ] Indented example\n\n";
        String source = examples + "- [ ] Literal \\" + marker(SECOND_ID) + "\n"
                + "- [ ] `" + marker(FIRST_ID) + "`\n";
        var first = TaskLinks.removeCheckbox(source, 0);
        assertEquals(examples + "- Literal \\" + marker(SECOND_ID) + "\n"
                + "- [ ] `" + marker(FIRST_ID) + "`\n", first.markdown());
        assertEquals("", first.id());
        var second = TaskLinks.removeCheckbox(first.markdown(), 0);
        assertEquals(examples + "- Literal \\" + marker(SECOND_ID) + "\n"
                + "- `" + marker(FIRST_ID) + "`\n", second.markdown());
        assertEquals("", second.id());
    }

    @Test void removingBlankLabelOrSpacedCheckboxDoesNotConsumeOtherSource() {
        assertEquals("- \r\nNext", TaskLinks.removeCheckbox("- [ ] " + marker(FIRST_ID) + "\r\nNext", 0).markdown());
        assertEquals("- Label\t \n", TaskLinks.removeCheckbox("- [ ]\t Label\t \n", 0).markdown());
        String source = "- [ ] Existing\n";
        assertThrows(IllegalArgumentException.class, () -> TaskLinks.removeCheckbox(source, -1));
        assertThrows(IllegalArgumentException.class, () -> TaskLinks.removeCheckbox(source, 1));
        assertThrows(IllegalArgumentException.class, () -> TaskLinks.removeCheckbox("```\n- [ ] Code\n```", 0));
    }

    @Test void removingCheckboxDoesNotRevealAnotherCheckboxOrBlockStructure() {
        for (var labels : List.of(
                List.of("[ ] literal label", "\\[ ] literal label"),
                List.of("[x] literal label", "\\[x] literal label"),
                List.of("# heading", "\\# heading"),
                List.of("> quote", "\\> quote"),
                List.of("- bullet", "\\- bullet"),
                List.of("* bullet", "\\* bullet"),
                List.of("12. numbered", "12\\. numbered"),
                List.of("9) numbered", "9\\) numbered"),
                List.of("---", "\\---"),
                List.of("***", "\\***"),
                List.of("```java", "\\```java"),
                List.of("~~~", "\\~~~"),
                List.of("<div>", "\\<div>"),
                List.of("[key]: /target", "\\[key]: /target"))) {
            String source = "- [ ] " + labels.get(0) + " " + marker(FIRST_ID) + "\n";
            var removed = TaskLinks.removeCheckbox(source, 0);
            assertEquals("- " + labels.get(1) + "\n", removed.markdown(), labels.get(0));
            assertTrue(TaskLinks.entries(removed.markdown()).isEmpty(), labels.get(0));
            String rendered = Markdown.render(removed.markdown(), Theme.fallback());
            assertFalse(rendered.contains("data-task-index="), labels.get(0));
            assertFalse(rendered.contains("<input"), labels.get(0));
        }
    }

    @Test void removalPreservesGenuineInlineFormatting() {
        for (String label : List.of("**bold**", "*italic*", "[link](https://example.com)", "`code`",
                "```inline code```", "~~struck~~", "<https://example.com>")) {
            var removed = TaskLinks.removeCheckbox("- [ ] " + label + " " + marker(FIRST_ID) + "\n", 0);
            assertEquals("- " + label + "\n", removed.markdown(), label);
        }
    }
}
