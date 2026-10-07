package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards our in-repo UI translations: values-XX/strings.xml vs values/strings.xml. */
class TranslationsTest {
    @Test fun passedBadgeFormatsRepeatedClearsInEveryLocaleWithIsolatedMultiplier() {
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            val once = resources.getValue("picker_passed").text!!
            val times = resources.getValue("picker_passed_times").text!!
                .replace("\\u2066", "\u2066").replace("\\u2069", "\u2069")
            for (n in listOf(2, 3, 12)) assertEquals("values$locale", "$once \u2066×$n\u2069",
                String.format(java.util.Locale.ROOT, times, n))
        }
        assertEquals("PASSED", parse(File(resDir(), "values/strings.xml")).getValue("picker_passed").text)
    }


    @Test fun destinationCopyUsesCountriesAndAirportsWithoutFictionalLabel() {
        for (locale in listOf("") + cldr.keys.map { "-$it" }) {
            val resources = parse(File(resDir(), "values$locale/strings.xml"))
            assertTrue("values$locale", "picker_fictional" !in resources)
            for (key in listOf("about_destinations", "languages_destinations", "step_destinations"))
                assertTrue("values$locale/$key", resources.getValue(key).items!!.isNotEmpty())
        }
        val english = parse(File(resDir(), "values/strings.xml"))
        assertEquals("%1\$d countries in %2\$d languages", english.getValue("about_destinations").items!!["other"])
        assertEquals("%1\$s · %2\$d airports", english.getValue("languages_destinations").items!!["other"])
        assertEquals("%1\$d airports", english.getValue("step_destinations").items!!["other"])
    }

    private val fmt = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxX]""")
    private val few = setOf("zero", "one", "two")
    private val cldr = mapOf(
        "de" to setOf("one", "other"), "it" to setOf("one", "other"), "tr" to setOf("one", "other"),
        "fr" to setOf("one", "many", "other"), "es" to setOf("one", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"), "ru" to setOf("one", "few", "many", "other"),
        "uk" to setOf("one", "few", "many", "other"),
        "ar" to setOf("zero", "one", "two", "few", "many", "other"),
        "ja" to setOf("other"), "zh" to setOf("other"),
    )

    private class Res(val translatable: Boolean, val text: String?, val items: Map<String, String>?)

    private fun resDir(): File =
        listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun parse(f: File): Map<String, Res> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = linkedMapOf<String, Res>()
        val kids = doc.documentElement.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? Element ?: continue
            val tr = e.getAttribute("translatable") != "false"
            when (e.tagName) {
                "string" -> out[e.getAttribute("name")] = Res(tr, e.textContent, null)
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val its = e.getElementsByTagName("item")
                    for (j in 0 until its.length) {
                        val it = its.item(j) as Element
                        items[it.getAttribute("quantity")] = it.textContent
                    }
                    out[e.getAttribute("name")] = Res(tr, null, items)
                }
            }
        }
        return out
    }

    private fun tokens(s: String) = fmt.findAll(s).map { it.value }.groupingBy { it }.eachCount()

    /** Returns "lang, name: problem" lines for one translation file. */
    internal fun check(lang: String, en: File, tr: File): List<String> {
        val p = mutableListOf<String>()
        fun add(name: String, msg: String) { p += "$lang, $name: $msg" }
        val e = parse(en)
        val t = try { parse(tr) } catch (ex: Exception) { return listOf("$lang, (file): unparseable XML: ${ex.message}") }
        val need = e.filterValues { it.translatable }
        for (n in need.keys - t.keys) add(n, "missing translation")
        for (n in t.keys - need.keys) add(n, if (n in e) "translatable=false in English, must not be translated" else "extra name not in values/strings.xml")
        for (n in t.keys.intersect(need.keys)) {
            val a = need.getValue(n); val b = t.getValue(n)
            if (!b.translatable) add(n, "translatable=false in translation")
            if ((a.items == null) != (b.items == null)) { add(n, "string/plurals kind mismatch"); continue }
            if (a.items == null) {
                if (tokens(a.text!!) != tokens(b.text!!)) add(n, "placeholders ${tokens(b.text)} != English ${tokens(a.text)}")
            } else {
                val want = cldr[lang]
                if (want != null && b.items!!.keys != want) add(n, "plural categories ${b.items.keys} != expected $want")
                val ref = tokens(a.items["other"] ?: a.items.values.first())
                for ((q, s) in b.items!!) {
                    val got = tokens(s)
                    val ok = got == ref || (q in few && ref.all { (k, v) -> (got[k] ?: 0) <= v } && got.keys.all { it in ref })
                    if (!ok) add(n, "item '$q' placeholders $got != English other $ref")
                }
            }
        }
        // Raw text: unescaped apostrophes in bodies (a body fully wrapped in "..." is legal).
        val raw = tr.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        Regex("""<(string|item)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach { m ->
            val body = m.groupValues[3].trim()
            if (Regex("""(?<!\\)'""").containsMatchIn(body) && !(body.startsWith("\"") && body.endsWith("\""))) {
                val name = Regex("""name="([^"]*)"""").find(m.groupValues[2])?.groupValues?.get(1)
                    ?: Regex("""quantity="([^"]*)"""").find(m.groupValues[2])?.groupValues?.get(1) ?: "?"
                add(name, "unescaped apostrophe (use \\')")
            }
        }
        return p
    }

    private fun report(problems: List<String>) {
        if (problems.isNotEmpty()) fail("${problems.size} translation problem(s):\n" + problems.joinToString("\n"))
    }

    @Test
    fun translationsAreComplete() {
        val res = resDir()
        val langs = Regex("""android:name="([^"]+)"""").findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.filter { it != "en" }.toList()
        val problems = mutableListOf<String>()
        for (l in langs) {
            val f = File(res, "values-$l/strings.xml")
            if (!f.isFile) problems += "$l, (folder): missing values-$l/strings.xml"
            else problems += check(l, File(res, "values/strings.xml"), f)
        }
        report(problems)
    }

    // Logic self-check against scratch copies (no res edits).
    @Test
    fun checkerCatchesProblems() {
        val dir = File(System.getProperty("java.io.tmpdir"), "translations-test").apply { mkdirs() }
        val en = File(resDir(), "values/strings.xml")
        val good = src(en).replace(Regex("""<(string|plurals)\b[^>]*translatable="false"[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL), "")
        val goodF = File(dir, "good.xml").apply { writeText(good) }
        assertEquals(emptyList<String>(), check("zz", en, goodF))

        val badF = File(dir, "bad.xml").apply {
            writeText(good.replaceFirst("%1\$d unit broken", "unit broken").replaceFirst("Voice + Vision", "Voice's Vision"))
        }
        val r = check("zz", en, badF).joinToString("\n")
        assertTrue(r, "stat_broken: item 'one'" !in r) // dropping %d in 'one' is allowed
        assertTrue(r, "feature_voice: unescaped apostrophe" in r)
        val badF2 = File(dir, "bad2.xml").apply { writeText(good.replaceFirst("%1\$d units broken", "units broken")) }
        val r2 = check("zz", en, badF2).joinToString("\n")
        assertTrue(r2, "stat_broken: item 'other' placeholders" in r2)
    }

    private fun src(f: File) = f.readText()
}
