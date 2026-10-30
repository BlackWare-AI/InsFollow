package ai.blackware.insfollow.data

import ai.blackware.insfollow.model.Identity
import ai.blackware.insfollow.model.Status
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class InputProblem(val reason: String) : Exception(reason)
data class FavoriteItem(val username: String, val name: String, val key: String)
data class FavoriteFolder(val name: String, val items: List<FavoriteItem>)
data class Favorites(val raw: String, val folders: List<FavoriteFolder>, val hash: String)
object FavoritesCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ITEMS = 100_000
    fun bounded(text: String, limit: Int = MAX_BYTES) {
        if (text.toByteArray(Charsets.UTF_8).size > limit) throw InputProblem("INPUT_TOO_LARGE")
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '"') quoted = !quoted
            if (!quoted) {
                if (c == '{' || c == '[') depth++
                if (c == '}' || c == ']') depth--
                if (depth > 32 || depth < 0) throw InputProblem("INVALID_NESTING")
            }
        }
        if (depth != 0 || quoted) throw InputProblem("MALFORMED_JSON")
    }
    fun parse(raw: String): Favorites {
        try {
            bounded(raw)
            val outer = JSONObject(raw)
            if (!outer.has("favorite_page_pref")) throw InputProblem("MISSING_FAVORITES")
            val encoded = outer.get("favorite_page_pref") as? String ?: throw InputProblem("INVALID_FAVORITES_TYPE")
            bounded(encoded)
            val array = JSONArray(encoded)
            if (array.length() > 2000) throw InputProblem("TOO_MANY_FOLDERS")
            var count = 0
            val folders = (0 until array.length()).map { f ->
                val folder = array.getJSONObject(f)
                val name = folder.get("name") as? String ?: throw InputProblem("INVALID_FOLDER")
                if (name.length > 512) throw InputProblem("INVALID_FOLDER")
                val items = folder.getJSONArray("items")
                count += items.length()
                if (count > MAX_ITEMS) throw InputProblem("TOO_MANY_ACCOUNTS")
                FavoriteFolder(name, (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    val username = item.get("pageId") as? String ?: throw InputProblem("INVALID_USERNAME")
                    val display = item.get("name") as? String ?: throw InputProblem("INVALID_DISPLAY_NAME")
                    if (display.length > 4096) throw InputProblem("INVALID_DISPLAY_NAME")
                    FavoriteItem(username, display, Identity.normalize(username))
                })
            }
            return Favorites(raw, folders, digest(raw))
        } catch (e: InputProblem) { throw e }
        catch (_: Exception) { throw InputProblem("MALFORMED_JSON") }
    }
    fun digest(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    // Always start from the immutable imported source; never remove a name's original suffix.
    fun annotate(raw: String, statuses: Map<String, Status>, includeFailures: Boolean): String {
        parse(raw)
        val outer = JSONObject(raw)
        val folders = JSONArray(outer.getString("favorite_page_pref"))
        for (f in 0 until folders.length()) {
            val items = folders.getJSONObject(f).getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val status = statuses[Identity.normalize(item.getString("pageId"))]
                val suffix = when {
                    status == Status.SUCCESS || status == Status.ALREADY -> " S"
                    status == Status.FAILURE && includeFailures -> " F"
                    else -> ""
                }
                item.put("name", item.getString("name") + suffix)
            }
        }
        return outer.put("favorite_page_pref", folders.toString()).toString(2)
    }
}
