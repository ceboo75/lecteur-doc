package fr.ceb.lecteur

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Convertit un .docx en page HTML lisible : texte, titres, gras/italique/souligné, couleurs,
 * listes numérotées ou à puces, tableaux, images, liens et zones de texte.
 * La mise en page exacte de Word n'est pas reproduite : c'est une vue de lecture.
 */
object DocxToHtml {

    fun convert(file: File): String = ZipFile(file).use { zip -> Converter(zip).run() }

    private class LevelDef(var fmt: String = "decimal", var text: String = "%1.", var start: Int = 1)

    private class Converter(private val zip: ZipFile) {
        private val rels = HashMap<String, Pair<String, Boolean>>()
        private val styleNames = HashMap<String, String>()
        private val numToAbstract = HashMap<String, String>()
        private val abstractLevels = HashMap<String, HashMap<Int, LevelDef>>()
        private val counters = HashMap<String, IntArray>()

        fun run(): String {
            readRels()
            readStyles()
            readNumbering()
            val entry = zip.getEntry("word/document.xml") ?: throw IOException("Pas un document Word")
            val body = StringBuilder()
            zip.getInputStream(entry).use { input ->
                val p = parser(input)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "w:body") body.append(blocks(p))
                }
            }
            return page(body.toString())
        }

        // ---------- Fichiers annexes ----------

        private fun readRels() = withEntry("word/_rels/document.xml.rels") { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.attr("Id") ?: continue
                    val target = p.attr("Target") ?: continue
                    rels[id] = target to (p.attr("TargetMode") == "External")
                }
            }
        }

        private fun readStyles() = withEntry("word/styles.xml") { p ->
            var current: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "w:style" -> current = p.attr("w:styleId")
                    "w:name" -> {
                        val id = current
                        val v = p.attr("w:val")
                        if (id != null && v != null) styleNames[id] = v
                    }
                }
            }
        }

        private fun readNumbering() = withEntry("word/numbering.xml") { p ->
            var abs: String? = null
            var num: String? = null
            var level: LevelDef? = null
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "w:abstractNum" -> abs = null
                        "w:num" -> num = null
                        "w:lvl" -> level = null
                    }
                    continue
                }
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "w:abstractNum" -> abs = p.attr("w:abstractNumId")
                    "w:num" -> num = p.attr("w:numId")
                    "w:abstractNumId" -> {
                        val n = num
                        val v = p.attr("w:val")
                        if (n != null && v != null) numToAbstract[n] = v
                    }
                    "w:lvl" -> {
                        val a = abs
                        val ilvl = p.attr("w:ilvl")?.toIntOrNull()
                        level = if (a != null && ilvl != null) {
                            LevelDef().also { abstractLevels.getOrPut(a) { HashMap() }[ilvl] = it }
                        } else {
                            null
                        }
                    }
                    "w:numFmt" -> p.attr("w:val")?.let { level?.fmt = it }
                    "w:lvlText" -> p.attr("w:val")?.let { level?.text = it }
                    "w:start" -> p.attr("w:val")?.toIntOrNull()?.let { level?.start = it }
                }
            }
        }

        private inline fun withEntry(name: String, block: (XmlPullParser) -> Unit) {
            val entry = zip.getEntry(name) ?: return
            try {
                zip.getInputStream(entry).use { block(parser(it)) }
            } catch (e: Exception) {
                // Fichier annexe illisible : on continue sans.
            }
        }

        // ---------- Corps du document ----------

        private fun blocks(p: XmlPullParser): String {
            val sb = StringBuilder()
            each(p) { name ->
                when (name) {
                    "w:p" -> sb.append(paragraph(p))
                    "w:tbl" -> sb.append(table(p))
                    "w:sectPr", "w:del", "w:moveFrom", "mc:Fallback" -> skip(p)
                }
            }
            return sb.toString()
        }

        private fun paragraph(p: XmlPullParser): String {
            val depth = p.depth
            var styleId: String? = null
            var jc: String? = null
            var numId: String? = null
            var ilvl = 0
            val sb = StringBuilder()
            val links = ArrayDeque<Boolean>()

            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.END_TAG) {
                    if (p.depth == depth) break
                    if (p.name == "w:hyperlink" && links.isNotEmpty()) {
                        if (links.removeAt(links.lastIndex)) sb.append("</a>")
                    }
                    continue
                }
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "w:pPr" -> each(p) { name ->
                        when (name) {
                            "w:pStyle" -> styleId = p.attr("w:val")
                            "w:jc" -> jc = p.attr("w:val")
                            "w:numId" -> numId = p.attr("w:val")
                            "w:ilvl" -> ilvl = p.attr("w:val")?.toIntOrNull() ?: 0
                            "w:rPr", "w:pPrChange", "w:sectPr" -> skip(p)
                        }
                    }
                    "w:r" -> sb.append(run(p))
                    "w:hyperlink" -> {
                        val href = p.attr("r:id")?.let { rels[it] }?.takeIf { it.second }?.first
                        if (href != null) sb.append("<a href=\"").append(esc(href)).append("\">")
                        links.addLast(href != null)
                    }
                    "w:tbl" -> sb.append(table(p))
                    "w:del", "w:moveFrom", "mc:Fallback", "w:rPr" -> skip(p)
                }
            }
            while (links.isNotEmpty()) {
                if (links.removeAt(links.lastIndex)) sb.append("</a>")
            }

            val content = sb.toString()
            val styleName = styleId?.let { styleNames[it] ?: it }?.lowercase()
            val align = when (jc) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute" -> "justify"
                else -> null
            }
            val alignCss = if (align != null) " style=\"text-align:$align\"" else ""

            val id = numId
            if (id != null && id != "0" && content.isNotBlank()) {
                val level = ilvl.coerceIn(0, 8)
                val marker = listMarker(id, level)
                return "<div class=\"li\" style=\"margin-left:${level * 1.4}em\">" +
                    "<span class=\"mk\">$marker</span><div class=\"lc\"$alignCss>$content</div></div>"
            }
            val heading = headingLevel(styleName, styleId)
            if (heading != null && content.isNotBlank()) return "<h$heading$alignCss>$content</h$heading>"
            if (styleName == "subtitle" && content.isNotBlank()) return "<div class=\"subtitle\"$alignCss>$content</div>"
            if (content.isBlank()) return "<div class=\"empty\"></div>"
            return "<div class=\"p\"$alignCss>$content</div>"
        }

        private fun run(p: XmlPullParser): String {
            var bold = false
            var italic = false
            var underline = false
            var strike = false
            var sup = false
            var sub = false
            var caps = false
            var color: String? = null
            var highlight: String? = null
            val sb = StringBuilder()

            each(p) { name ->
                when (name) {
                    "w:rPr" -> each(p) { prop ->
                        when (prop) {
                            "w:b" -> bold = p.on()
                            "w:i" -> italic = p.on()
                            "w:u" -> underline = (p.attr("w:val") ?: "single") != "none"
                            "w:strike", "w:dstrike" -> strike = p.on()
                            "w:caps" -> caps = p.on()
                            "w:vertAlign" -> when (p.attr("w:val")) {
                                "superscript" -> sup = true
                                "subscript" -> sub = true
                            }
                            "w:color" -> color = p.attr("w:val")?.takeIf { HEX.matches(it) && it != "000000" }
                            "w:highlight" -> highlight = p.attr("w:val")?.takeIf { it != "none" }
                            "w:rPrChange" -> skip(p)
                        }
                    }
                    "w:t" -> sb.append(esc(p.nextText()))
                    "w:tab", "w:ptab" -> sb.append("<span class=\"tab\"></span>")
                    "w:br" -> if (p.attr("w:type") != "page") sb.append("<br>")
                    "w:cr" -> sb.append("<br>")
                    "w:noBreakHyphen" -> sb.append("-")
                    "w:softHyphen" -> sb.append("&shy;")
                    "w:drawing", "w:pict", "w:object" -> sb.append(graphic(p))
                    "w:instrText", "w:delText", "w:footnoteReference", "w:endnoteReference",
                    "w:commentReference", "mc:Fallback" -> skip(p)
                }
            }
            if (sb.isEmpty()) return ""

            var out = sb.toString()
            if (sup) out = "<sup>$out</sup>"
            if (sub) out = "<sub>$out</sub>"
            if (strike) out = "<s>$out</s>"
            if (underline) out = "<u>$out</u>"
            if (italic) out = "<i>$out</i>"
            if (bold) out = "<b>$out</b>"
            val css = buildList {
                color?.let { add("color:#$it") }
                highlight?.let { add("background:${highlightColor(it)}") }
                if (caps) add("text-transform:uppercase")
            }
            if (css.isNotEmpty()) out = "<span style=\"${css.joinToString(";")}\">$out</span>"
            return out
        }

        private fun graphic(p: XmlPullParser): String {
            val sb = StringBuilder()
            var widthPx: Int? = null
            each(p) { name ->
                when (name) {
                    "wp:extent" -> widthPx = p.attr("cx")?.toLongOrNull()?.let { (it / 9525).toInt() }
                    "a:blip" -> p.attr("r:embed")?.let { image(it, widthPx) }?.let { sb.append(it) }
                    "v:imagedata" -> p.attr("r:id")?.let { image(it, widthPx) }?.let { sb.append(it) }
                    "w:txbxContent" -> sb.append("<div class=\"txbx\">").append(blocks(p)).append("</div>")
                    "mc:Fallback" -> skip(p)
                }
            }
            return sb.toString()
        }

        private fun image(relId: String, widthPx: Int?): String? {
            val (target, external) = rels[relId] ?: return null
            if (external) return null
            val path = resolve(target)
            val entry = zip.getEntry(path) ?: return null
            if (entry.size > MAX_IMAGE_BYTES) return null
            val mime = when (path.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> return null // EMF/WMF : non affichables
            }
            val data = zip.getInputStream(entry).use { it.readBytes() }
            val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val size = widthPx?.takeIf { it > 0 }?.let { " style=\"width:${it}px\"" } ?: ""
            return "<img src=\"data:$mime;base64,$b64\"$size>"
        }

        private fun table(p: XmlPullParser): String {
            val depth = p.depth
            val sb = StringBuilder("<div class=\"tw\"><table>")
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.END_TAG) {
                    if (p.depth == depth) break
                    if (p.name == "w:tr") sb.append("</tr>")
                    continue
                }
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "w:tr" -> sb.append("<tr>")
                    "w:tc" -> sb.append(cell(p))
                    "w:tblPr", "w:tblGrid", "w:trPr", "w:tblPrEx", "w:del" -> skip(p)
                }
            }
            return sb.append("</table></div>").toString()
        }

        private fun cell(p: XmlPullParser): String {
            var span = 1
            var mergedContinue = false
            var shade: String? = null
            val sb = StringBuilder()
            each(p) { name ->
                when (name) {
                    "w:tcPr" -> each(p) { prop ->
                        when (prop) {
                            "w:gridSpan" -> span = p.attr("w:val")?.toIntOrNull() ?: 1
                            "w:vMerge" -> mergedContinue = p.attr("w:val").let { it == null || it == "continue" }
                            "w:shd" -> shade = p.attr("w:fill")?.takeIf { HEX.matches(it) && !it.equals("FFFFFF", true) }
                            "w:tcPrChange" -> skip(p)
                        }
                    }
                    "w:p" -> sb.append(paragraph(p))
                    "w:tbl" -> sb.append(table(p))
                    "w:del", "mc:Fallback" -> skip(p)
                }
            }
            val attrs = StringBuilder()
            if (span > 1) attrs.append(" colspan=\"$span\"")
            if (mergedContinue) attrs.append(" class=\"vm\"")
            shade?.let { attrs.append(" style=\"background:#$it\"") }
            return "<td$attrs>$sb</td>"
        }

        // ---------- Listes ----------

        private fun listMarker(numId: String, level: Int): String {
            val levels = numToAbstract[numId]?.let { abstractLevels[it] }
            val def = levels?.get(level) ?: LevelDef("bullet", "•", 1)
            val c = counters.getOrPut(numId) { IntArray(9) }
            c[level] = if (c[level] == 0) def.start else c[level] + 1
            for (k in level + 1 until 9) c[k] = 0

            if (def.fmt == "none") return ""
            if (def.fmt == "bullet") return bullet(def.text, level)

            var text = def.text
            for (k in 0..8) {
                val token = "%${k + 1}"
                if (text.contains(token)) {
                    val d = levels?.get(k) ?: def
                    val v = if (c[k] == 0) d.start else c[k]
                    text = text.replace(token, formatNumber(v, d.fmt))
                }
            }
            return esc(text)
        }

        private fun bullet(text: String, level: Int): String = when (text.firstOrNull()) {
            'o' -> "◦"
            '-', '–' -> "–"
            '', '▪', '■' -> "▪"
            '', '', '✓', '➢', '►', '>' -> "›"
            else -> listOf("•", "◦", "▪")[level % 3]
        }

        private fun formatNumber(v: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> letters(v).lowercase()
            "upperLetter" -> letters(v)
            "lowerRoman" -> roman(v).lowercase()
            "upperRoman" -> roman(v)
            "decimalZero" -> v.toString().padStart(2, '0')
            else -> v.toString()
        }

        private fun letters(v: Int): String {
            if (v <= 0) return v.toString()
            val ch = 'A' + ((v - 1) % 26)
            return ch.toString().repeat((v - 1) / 26 + 1)
        }

        private fun roman(v: Int): String {
            if (v <= 0 || v > 3999) return v.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var n = v
            val sb = StringBuilder()
            for (i in values.indices) {
                while (n >= values[i]) {
                    sb.append(symbols[i])
                    n -= values[i]
                }
            }
            return sb.toString()
        }

        private fun headingLevel(name: String?, id: String?): Int? {
            if (name != null) {
                Regex("^heading (\\d)").find(name)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
                if (name == "title") return 1
            }
            if (id != null) {
                Regex("^(heading|titre)(\\d)$", RegexOption.IGNORE_CASE).find(id)?.let {
                    return it.groupValues[2].toInt().coerceIn(1, 6)
                }
            }
            return null
        }

        private fun resolve(target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = mutableListOf("word")
            for (segment in target.split('/')) {
                when (segment) {
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    ".", "" -> {}
                    else -> parts.add(segment)
                }
            }
            return parts.joinToString("/")
        }
    }

    // ---------- Outils XML ----------

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.on(): Boolean {
        val v = attr("w:val") ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    /** Parcourt les descendants de l'élément courant jusqu'à sa balise fermante. */
    private inline fun each(p: XmlPullParser, onStart: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev == XmlPullParser.START_TAG) onStart(p.name)
        }
    }

    private fun skip(p: XmlPullParser) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun highlightColor(name: String): String = when (name) {
        "yellow" -> "#fff59d"
        "green", "darkGreen" -> "#c8e6c9"
        "cyan", "darkCyan" -> "#b2ebf2"
        "magenta", "darkMagenta" -> "#f8bbd0"
        "blue", "darkBlue" -> "#bbdefb"
        "red", "darkRed" -> "#ffcdd2"
        "lightGray", "darkGray" -> "#e0e0e0"
        else -> "#fff59d"
    }

    private val HEX = Regex("[0-9A-Fa-f]{6}")
    private const val MAX_IMAGE_BYTES = 10_000_000L

    private fun page(body: String): String = """<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=6, user-scalable=yes">
<style>
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:22px 20px 110px;font-family:Roboto,'Noto Sans',sans-serif;font-size:16px;line-height:1.6;color:#1d1f22;background:#fff;overflow-wrap:break-word}
h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.1em 0 .45em;color:#14323d;font-weight:600}
h1{font-size:1.55em}h2{font-size:1.3em}h3{font-size:1.15em}h4,h5,h6{font-size:1em}
body>h1:first-child,body>h2:first-child{margin-top:.2em}
.p{margin:0 0 .65em}
.empty{height:.5em}
.subtitle{color:#5a5d61;font-size:1.1em;margin:0 0 1em}
.li{display:flex;gap:.55em;margin:0 0 .35em}
.mk{flex:none;min-width:1.1em;text-align:right;color:#1f4e5f}
.lc{flex:1;min-width:0}
.tab{display:inline-block;width:2em}
a{color:#1f6fb2}
img{max-width:100%;height:auto;vertical-align:middle}
.tw{overflow-x:auto;margin:.6em 0 1em}
table{border-collapse:collapse;min-width:100%;font-size:.92em}
td{border:1px solid #d6d3cc;padding:6px 8px;vertical-align:top}
td .p{margin:0 0 .3em}td .p:last-child{margin-bottom:0}td .empty{height:0}
td.vm{border-top-color:transparent}
.txbx{border-left:3px solid #d6d3cc;padding-left:10px;margin:.5em 0}
</style></head><body>${if (body.isBlank()) "<p style=\"color:#888\">Ce document est vide.</p>" else body}</body></html>"""
}
