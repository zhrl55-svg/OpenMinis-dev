package com.openminis.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德地点查询（Web API 通道）。
 *
 * ## 为什么是 Web API 而不是驱动高德 App
 * 实测（2026-11-12/13）：
 *  - 高德搜索页是**自绘**的（`ajx3.widget`），a11y 读不到任何文字（74 节点全空），
 *    只能盲点坐标。
 *  - URI 通道有**反滥用限流**：连续调会弹「访问被拒绝 / 你的操作过于频繁」。
 *  - uiautomator 报的 bounds 与实际点击位置**差 57px**（状态栏偏移），点击常落空。
 * 走 API 一个坑都没有，而且返回结构化数据。
 *
 * ## 两个必须记住的坑（pilot 已在生产验证）
 * 1. **city 参数在 SDK 通道不生效**，Web 通道才生效（这里用 `citylimit=false` 当偏置）。
 * 2. **距离安全阀**：高德会返回 1500 公里外的同名 POI。超过 [MAX_SANE_DISTANCE_M]
 *    的候选直接丢；全被丢掉时报「附近没有这个地点」，而不是把用户指到天边。
 *    （pilot 的真实教训：搜「水勘院超市」命中石家庄，一路 1517 公里。）
 *
 * ## key
 * 高德 Web Key 从两个位置找（先到先得）：
 *  1. `getExternalFilesDir(null)/amap_key.txt` —— App 私有外，adb 可推（部署脚本写这里）
 *  2. `filesDir/shared/amap-nav/amap_key.txt` —— 沙箱里的那份（`/var/minis` 的直译）
 *
 * key 是敏感值：本工具只读、不打印、不外传。
 */
object AmapSearchTool {

    const val NAME = "amap_search"

    /** 超过这个距离的候选直接丢（防止 city 偏置失效导致指到天边）。 */
    private const val MAX_SANE_DISTANCE_M = 50_000

    private const val BASE = "https://restapi.amap.com/v3/place"
    private const val KEY_FILE_NAME = "amap_key.txt"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search places near the device with AMap (Gaode). " +
            "Use mode=\"near\" (default) for \"around me\" queries and mode=\"poi\" for " +
            "city-wide search by name. Returns names, addresses and distance in meters.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "What to search for, e.g. \"超市\", \"咖啡\", \"加油站\". Use the word the user used."),
            "mode" to AgentToolParam(
                "string",
                "\"near\" (default) — search around the device's current location; " +
                    "\"poi\" — search the whole city by name.",
                enumValues = listOf("near", "poi"),
            ),
            "radius" to AgentToolParam("integer", "Search radius in meters for mode=\"near\" (default 3000, max 50000)."),
            "city" to AgentToolParam("string", "City name for mode=\"poi\" (e.g. \"张掖\"). Only used as a bias, never a hard filter."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "mode", "radius", "city"),
    )

    // ------------------------------------------------------------------ key

    /** 找 Web Key。找不到返回空串。 */
    fun readKey(context: Context): String {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, KEY_FILE_NAME) },
            File(context.filesDir, "shared/amap-nav/$KEY_FILE_NAME"),
        )
        for (f in candidates) {
            runCatching {
                if (f.isFile) {
                    val t = f.readText().trim()
                    if (t.isNotEmpty()) return t
                }
            }
        }
        return ""
    }

    // ------------------------------------------------------------- location

    /**
     * 设备当前定位。优先 GPS、网络次之。
     *
     * 拿不到就返回 null —— 上层会退化成「按城市搜」而不是编一个坐标。
     */
    fun currentLocation(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try {
            lm.getProviders(true)
        } catch (_: SecurityException) {
            emptyList<String>()
        }
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    // ----------------------------------------------------------------- http

    private fun httpJson(url: String, timeoutMs: Int = 12_000): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Minis/amap-web/1.0")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) null else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun haversineM(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ---------------------------------------------------------------- exec

    /**
     * 执行查询。
     *
     * 返回给人看的文本（不是 JSON）—— 这是给用户看的工具结果，
     * 不是给模型再解析的中间产物。
     */
    fun execute(context: Context, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolExecutionResult("缺少 query", false)

        val key = readKey(context)
        if (key.isEmpty()) {
            return ToolExecutionResult(
                "高德 Web Key 未配置（缺少 amap_key.txt），地点查询不可用。",
                false,
            )
        }

        val mode = args.optString("mode", "near").trim().lowercase().ifEmpty { "near" }
        val radius = args.optInt("radius", 3000).coerceIn(100, 50_000)

        val loc = if (mode == "near") currentLocation(context) else null
        if (mode == "near" && loc == null) {
            return ToolExecutionResult(
                "拿不到设备定位，无法做周边搜索。可以改用「在${args.optString("city").ifBlank { "当前城市" }}找…」这种城市级查询。",
                false,
            )
        }

        val url = if (loc != null) {
            "$BASE/around?key=$key&keywords=${enc(query)}" +
                "&location=${loc.longitude},${loc.latitude}&radius=$radius" +
                "&sortrule=distance&offset=10"
        } else {
            val city = args.optString("city").trim()
            val cityPart = if (city.isEmpty()) "" else "&city=${enc(city)}"
            "$BASE/text?key=$key&keywords=${enc(query)}$cityPart&citylimit=false&offset=10"
        }

        val body = httpJson(url)
            ?: return ToolExecutionResult("请求高德接口失败（网络或超时）", false)
        if (body.optString("status") != "1") {
            return ToolExecutionResult(
                "高德接口返回异常：status=${body.optString("status")} info=${body.optString("info")}",
                false,
            )
        }

        val pois = body.optJSONArray("pois") ?: org.json.JSONArray()
        if (pois.length() == 0) {
            return ToolExecutionResult(
                if (loc != null) "附近 ${radius}m 内没有找到「$query」" else "没有找到「$query」",
                true,
            )
        }

        val sb = StringBuilder()
        var dropped = 0
        var shown = 0
        for (i in 0 until pois.length()) {
            val p = pois.optJSONObject(i) ?: continue
            val name = p.optString("name")
            val address = p.optString("address")
            val dist = p.optString("distance")
            // 距离安全阀：接口有时会给出远得离谱的结果。
            val distM = dist.toDoubleOrNull()
            if (distM != null && distM > MAX_SANE_DISTANCE_M) {
                dropped++
                continue
            }
            if (shown >= 8) break
            shown++
            sb.append("• ").append(name)
            if (distM != null) sb.append("（").append(distM.toInt()).append("m）")
            if (address.isNotBlank()) sb.append(" — ").append(address)
            sb.append('\n')
        }
        if (shown == 0) {
            return ToolExecutionResult(
                "找到的「$query」都在 ${MAX_SANE_DISTANCE_M / 1000}km 之外，已全部丢弃。",
                true,
            )
        }
        val header = if (loc != null) {
            "附近 ${radius}m 内的「$query」：\n"
        } else {
            "「$query」的结果：\n"
        }
        val tail = if (dropped > 0) "\n（已丢弃 $dropped 个距离过远的结果）" else ""
        return ToolExecutionResult(header + sb.toString().trimEnd() + tail, true)
    }
}
