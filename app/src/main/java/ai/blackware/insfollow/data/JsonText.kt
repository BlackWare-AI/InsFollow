package ai.blackware.insfollow.data

import org.json.JSONObject
import org.json.JSONTokener

/** Strict structural scan; edits only selected string tokens so unrelated numbers/fields stay exact. */
internal object JsonText {
    fun validate(raw: String) { Scanner(raw,emptyMap()).scan() }
    fun rewrite(raw: String, replacements: Map<List<String>,String>): String = Scanner(raw,replacements).scan()
    private class Scanner(val raw: String, val replacements: Map<List<String>,String>) {
        var cursor=0
        val path=mutableListOf<String>()
        val edits=mutableListOf<Triple<Int,Int,String>>()
        fun scan(): String {
            value(0); whitespace(); if(cursor!=raw.length) invalid()
            if(edits.isEmpty()) return raw
            return buildString {
                var copied=0
                edits.forEach { (start,end,value) -> append(raw,copied,start); append(JSONObject.quote(value)); copied=end }
                append(raw,copied,raw.length)
            }
        }
        fun invalid(): Nothing = throw InputProblem("MALFORMED_JSON")
        fun whitespace() { while(cursor<raw.length && raw[cursor] in " \t\r\n") cursor++ }
        fun take(c: Char): Boolean { whitespace(); if(cursor<raw.length && raw[cursor]==c) { cursor++; return true }; return false }
        fun value(depth: Int) {
            if(depth>32) throw InputProblem("INVALID_NESTING")
            whitespace(); if(cursor>=raw.length) invalid()
            when(raw[cursor]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> { val start=cursor; string(); replacements[path]?.let { edits.add(Triple(start,cursor,it)) } }
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val match=number.find(raw,cursor) ?: invalid()
                    if(match.range.first!=cursor) invalid()
                    cursor=match.range.last+1
                }
            }
        }
        fun objectValue(depth: Int) {
            cursor++; if(take('}')) return
            val names=HashSet<String>()
            do {
                whitespace(); if(cursor>=raw.length || raw[cursor]!='"') invalid()
                val key=string(); if(!names.add(key)) throw InputProblem("DUPLICATE_FIELD")
                if(!take(':')) invalid()
                path.add(key); value(depth+1); path.removeAt(path.lastIndex)
                if(take('}')) return
            } while(take(','))
            invalid()
        }
        fun arrayValue(depth: Int) {
            cursor++; if(take(']')) return
            var index=0
            do {
                path.add((index++).toString()); value(depth+1); path.removeAt(path.lastIndex)
                if(take(']')) return
            } while(take(','))
            invalid()
        }
        fun literal(value: String) { if(!raw.startsWith(value,cursor)) invalid(); cursor+=value.length }
        fun string(): String {
            val start=cursor++
            while(cursor<raw.length) {
                val c=raw[cursor++]
                if(c=='"') return JSONTokener(raw.substring(start,cursor)).nextValue() as String
                if(c<' ') invalid()
                if(c=='\\') {
                    if(cursor>=raw.length) invalid()
                    when(raw[cursor++]) {
                        '"','\\','/','b','f','n','r','t' -> Unit
                        'u' -> repeat(4) { if(cursor>=raw.length || raw[cursor++] !in "0123456789abcdefABCDEF") invalid() }
                        else -> invalid()
                    }
                }
            }
            invalid()
        }
    }
    private val number=Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
