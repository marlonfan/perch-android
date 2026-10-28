package app.perch

import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun preservesHeadingLevelsAndNestedLists() {
        val blocks = MarkdownDocument.children(MarkdownDocument.parse("## Section\n\n- First\n  - Nested\n- Second\n\n3. Third\n4. Fourth"))
        assertEquals(2, (blocks[0] as Heading).level)
        val list = blocks[1] as BulletList
        assertEquals(2, MarkdownDocument.children(list).size)
        val first = MarkdownDocument.children(list).first()
        assertTrue(MarkdownDocument.children(first).last() is BulletList)
        assertEquals(3, (blocks[2] as OrderedList).startNumber)
    }
    @Test fun preservesTableCellsAlignmentAndInlineFormatting() {
        val table = MarkdownDocument.children(MarkdownDocument.parse("| Name | Count |\n| :--- | ---: |\n| **Build** | 12 |")).single() as TableBlock
        val rows = MarkdownDocument.rows(table)
        assertEquals(2, rows.size)
        assertTrue(rows[0][0].isHeader)
        assertEquals(TableCell.Alignment.RIGHT, rows[1][1].alignment)
        assertEquals("Build", MarkdownDocument.plain(rows[1][0]))
    }
    @Test fun keepsCodeLiteralAndRejectsExecutableLinks() {
        val code = MarkdownDocument.children(MarkdownDocument.parse("```python\nprint(42)\n```")).single() as FencedCodeBlock
        assertEquals("python", code.info)
        assertEquals("print(42)\n", code.literal)
        assertTrue(MarkdownDocument.safeLink("https://example.com"))
        assertFalse(MarkdownDocument.safeLink("javascript:alert(1)"))
        assertFalse(MarkdownDocument.safeLink("file:///etc/passwd"))
    }
}
