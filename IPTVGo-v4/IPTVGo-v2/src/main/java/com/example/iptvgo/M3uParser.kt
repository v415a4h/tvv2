package com.example.iptvgo

import org.json.JSONObject

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val licenseType: String?,
    val licenseKey: String?,
    val headers: Map<String, String>
)

/** Parses M3U incl. #KODIPROP (license_type / license_key), #EXTVLCOPT and #EXTHTTP. */
object M3uParser {
    private fun attr(line: String, key: String): String? =
        Regex("""$key="([^"]*)"""").find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name = ""; var logo: String? = null; var group = "Other"
        var lt: String? = null; var lk: String? = null
        var headers = HashMap<String, String>()

        fun reset() {
            name = ""; logo = null; group = "Other"; lt = null; lk = null; headers = HashMap()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",").trim()
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title") ?: "Other"
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_type=") ->
                    lt = line.substringAfter("=").trim()
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") ->
                    lk = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=") ->
                    headers["User-Agent"] = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-referrer=") ->
                    headers["Referer"] = line.substringAfter("=").trim()
                line.startsWith("#EXTHTTP:") -> runCatching {
                    val j = JSONObject(line.substringAfter(":"))
                    j.keys().forEach { k -> headers[k] = j.getString(k) }
                }
                line.startsWith("#") -> {}
                else -> {
                    // KODI style: url|Header=Value&Header2=Value2
                    var url = line
                    if (line.contains("|")) {
                        url = line.substringBefore("|")
                        line.substringAfter("|").split("&").forEach {
                            val kv = it.split("=", limit = 2)
                            if (kv.size == 2) headers[kv[0]] = kv[1]
                        }
                    }
                    out += Channel(name.ifBlank { url }, url, logo, group, lt, lk, headers)
                    reset()
                }
            }
        }
        return out
    }
}
