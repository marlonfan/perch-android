package app.perch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

/** Parsing stays independent from the UI; all block geometry belongs to Compose. */
internal object MarkdownDocument {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(source: String): Node = parser.parse(source)
    fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }
    fun plain(node: Node): String = when (node) {
        is org.commonmark.node.Text -> node.literal
        is Code -> node.literal
        is SoftLineBreak, is HardLineBreak -> "\n"
        else -> children(node).joinToString("") { plain(it) }
    }
    fun rows(table: TableBlock): List<List<TableCell>> = children(table).flatMap { section ->
        children(section).map { row -> children(row).filterIsInstance<TableCell>() }
    }
    fun safeLink(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("mailto:", true)
}

@Composable internal fun MarkdownBody(body: String, markdown: Boolean) {
    val document = remember(body) { MarkdownDocument.parse(body) }
    val bodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, letterSpacing = .1.sp, color = MaterialTheme.colorScheme.onSurface)
    SelectionContainer {
        if (markdown) MarkdownBlocks(MarkdownDocument.children(document), bodyStyle)
        else Text(body, style = bodyStyle, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun MarkdownBlocks(nodes: List<Node>, bodyStyle: TextStyle, depth: Int = 0, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
        nodes.forEachIndexed { index, node ->
            when (node) {
                is Heading -> {
                    val size = when (node.level) { 1 -> 24; 2 -> 21; 3 -> 18; else -> 16 }
                    InlineText(node, bodyStyle.copy(fontSize = size.sp, lineHeight = (size + 10).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp), Modifier.padding(top = if (index == 0 || compact) 0.dp else 10.dp))
                }
                is org.commonmark.node.Paragraph -> InlineText(node, bodyStyle)
                is BulletList -> MarkdownList(node, bodyStyle, depth)
                is OrderedList -> MarkdownList(node, bodyStyle, depth)
                is BlockQuote -> QuoteBlock(node, bodyStyle, depth)
                is FencedCodeBlock -> CodeBlock(node.literal, node.info.substringBefore(' ').ifBlank { "text" })
                is IndentedCodeBlock -> CodeBlock(node.literal, "text")
                is TableBlock -> MarkdownTable(node)
                is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is HtmlBlock -> Text(node.literal, style = bodyStyle)
                else -> { val children = MarkdownDocument.children(node); if (children.isNotEmpty()) MarkdownBlocks(children, bodyStyle, depth, compact) }
            }
        }
    }
}

@Composable private fun InlineText(node: Node, style: TextStyle, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val rich = remember(node, palette) {
        buildAnnotatedString {
            fun AnnotatedString.Builder.link(destination: String, content: AnnotatedString.Builder.() -> Unit) {
                if (MarkdownDocument.safeLink(destination)) {
                    withLink(LinkAnnotation.Clickable(destination, TextLinkStyles(SpanStyle(color = palette.primary, textDecoration = TextDecoration.Underline)), { runCatching { uri.openUri(destination) } })) { content() }
                } else content()
            }
            fun AnnotatedString.Builder.visit(n: Node) {
                fun descendants() { MarkdownDocument.children(n).forEach { visit(it) } }
                when (n) {
                    is org.commonmark.node.Text -> {
                        // Bare HTTP links remain usable in notification bodies.
                        val pattern = Regex("https?://[^\\s<>]+")
                        var at = 0
                        pattern.findAll(n.literal).forEach { match ->
                            append(n.literal.substring(at, match.range.first))
                            link(match.value) { append(match.value) }; at = match.range.last + 1
                        }
                        append(n.literal.substring(at))
                    }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { descendants() }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { descendants() }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { descendants() }
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = palette.surfaceContainerHigh, color = palette.primary)) { append(" " + n.literal + " ") }
                    is Link -> link(n.destination) { descendants() }
                    is Image -> link(n.destination) { append("[图片：" + MarkdownDocument.plain(n).ifBlank { "查看图片" } + "]") }
                    is HardLineBreak -> append('\n')
                    is SoftLineBreak -> append(' ')
                    is HtmlInline -> append(n.literal)
                    else -> descendants()
                }
            }
            visit(node)
        }
    }
    Text(rich, modifier.fillMaxWidth(), style = style)
}

@Composable private fun MarkdownList(node: Node, style: TextStyle, depth: Int) {
    val ordered = node is OrderedList
    val start = (node as? OrderedList)?.startNumber ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarkdownDocument.children(node).forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ordered) {
                    Text("${start + index}.", Modifier.widthIn(min = 22.dp).padding(top = 1.dp), style = style.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary), textAlign = TextAlign.End)
                } else {
                    Box(Modifier.width(14.dp).height(28.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (depth == 0) 5.dp else 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = if (depth == 0) .8f else .45f)))
                    }
                }
                Box(Modifier.weight(1f)) { MarkdownBlocks(MarkdownDocument.children(item), style, depth + 1, compact = true) }
            }
        }
    }
}

@Composable private fun QuoteBlock(node: BlockQuote, style: TextStyle, depth: Int) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.primaryContainer.copy(alpha = .38f)) {
        Box(Modifier.fillMaxWidth().padding(16.dp).drawBehind {
            drawRoundRect(c.primary.copy(alpha = .5f), size = Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
        }.padding(start = 16.dp)) {
            MarkdownBlocks(MarkdownDocument.children(node), style.copy(fontSize = 15.sp, lineHeight = 26.sp, color = c.onSurface.copy(alpha = .78f)), depth, compact = true)
        }
    }
}

@Composable private fun CodeBlock(raw: String, language: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember(raw) { mutableStateOf(false) }
    val code = raw.trimEnd('\n', '\r').replace("\t", "    ")
    val highlighted = remember(code, language, c) { highlight(code, language, c) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surfaceContainerLow, border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = .7f))) {
        Column {
            Row(Modifier.fillMaxWidth().background(c.surfaceContainerHigh.copy(alpha = .55f)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.take(24).uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 1.sp, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (copied) Text("已复制", fontSize = 11.sp, color = c.primary)
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }, modifier = Modifier.size(44.dp)) { Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "代码已复制" else "复制代码", Modifier.size(16.dp), tint = c.onSurfaceVariant) }
            }
            HorizontalDivider(color = c.outlineVariant.copy(alpha = .5f))
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp)) {
                Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 23.sp, color = c.onSurface, softWrap = false)
            }
        }
    }
}

private fun highlight(code: String, language: String, palette: ColorScheme): AnnotatedString {
    if (language.lowercase() !in setOf("bash", "sh", "shell", "python", "py", "kotlin", "kt", "java", "javascript", "js", "typescript", "ts", "json", "yaml", "yml")) return AnnotatedString(code)
    val result = AnnotatedString.Builder(code)
    val token = Regex("""("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|((?<!\S)#[^\n]*|(?<!\S)//[^\n]*)|\b(true|false|null|None|True|False|val|var|fun|class|return|if|else|for|while|import|from|def|const|let|async|await|curl|echo|print)\b|\b([0-9]+(?:\.[0-9]+)?)\b""")
    token.findAll(code).forEach { m ->
        val color = when { m.groups[1] != null -> palette.primary; m.groups[2] != null -> palette.onSurfaceVariant; m.groups[3] != null -> palette.tertiary; else -> palette.secondary }
        result.addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
    }
    return result.toAnnotatedString()
}

@Composable private fun MarkdownTable(table: TableBlock) {
    val rows = remember(table) { MarkdownDocument.rows(table) }
    val columns = rows.maxOfOrNull { it.size } ?: return
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, (columns * 136).dp)
            val scrollable = tableWidth > maxWidth
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surfaceContainerLowest, border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = .8f))) {
                Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
                    rows.forEachIndexed { index, cells ->
                        val header = cells.firstOrNull()?.isHeader == true
                        val background = when { header -> c.primaryContainer.copy(alpha = .5f); index % 2 == 0 -> c.surfaceContainerLow.copy(alpha = .55f); else -> Color.Transparent }
                        Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp).height(IntrinsicSize.Min)) {
                            repeat(columns) { column ->
                                val cell = cells.getOrNull(column)
                                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = if (header) 13.dp else 14.dp)) {
                                    if (cell != null) InlineText(cell, TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal, color = if (header) c.onPrimaryContainer else c.onSurface, textAlign = when (cell.alignment) { TableCell.Alignment.RIGHT -> TextAlign.End; TableCell.Alignment.CENTER -> TextAlign.Center; else -> TextAlign.Start }))
                                }
                            }
                        }
                        if (index < rows.lastIndex) HorizontalDivider(color = c.outlineVariant.copy(alpha = if (header) .7f else .35f))
                    }
                }
            }
            if (scrollable) Text("左右滑动查看完整表格", fontSize = 11.sp, lineHeight = 18.sp, color = c.onSurfaceVariant)
            }
        }
    }
}
