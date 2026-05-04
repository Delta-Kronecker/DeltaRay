package xyz.zarazaex.olc.handler

import android.util.Log
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.util.HttpUtil
import xyz.zarazaex.olc.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object CountryDetector {

    const val UNKNOWN = "??"

    // ── Emoji flag → ISO 2-letter country code ────────────────────────────────

    /** Extract first flag emoji found in [text] and return its ISO country code (e.g. "RU"). */
    fun extractFlagCode(text: String): String? {
        val codePoints = text.codePoints().toArray()
        var i = 0
        while (i < codePoints.size - 1) {
            val cp1 = codePoints[i]
            val cp2 = codePoints[i + 1]
            if (cp1 in 0x1F1E6..0x1F1FF && cp2 in 0x1F1E6..0x1F1FF) {
                val c1 = ('A'.code + (cp1 - 0x1F1E6)).toChar()
                val c2 = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                return "$c1$c2"
            }
            i++
        }
        return null
    }

    /** Get best country code for a server (emoji first, then cache). */
    fun getCountryCode(remarks: String, serverIp: String?): String {
        extractFlagCode(remarks)?.let { return it }
        if (!serverIp.isNullOrBlank() && !isPrivateIp(serverIp)) {
            MmkvManager.getCountryCache(serverIp)?.let { return it }
        }
        return UNKNOWN
    }

    // ── Flag emoji rendering ──────────────────────────────────────────────────

    /** ISO code → flag emoji string */
    fun codeToFlag(code: String): String {
        if (code.length != 2 || code == UNKNOWN) return "🌍"
        return try {
            val first  = 0x1F1E6 + (code[0].uppercaseChar().code - 'A'.code)
            val second = 0x1F1E6 + (code[1].uppercaseChar().code - 'A'.code)
            String(intArrayOf(first, second), 0, 2)
        } catch (e: Exception) { "🌍" }
    }

    /** ISO code → human-readable country name (or code if unknown) */
    fun codeToName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code

    // ── Background lookup ─────────────────────────────────────────────────────

    private val semaphore = Semaphore(5)

    /**
     * Looks up countries for all [ips] not yet cached via ip-api.com/batch.
     * Saves results to MmkvManager cache. Called from IO coroutine.
     */
    suspend fun lookupAndCacheAll(ips: List<String>) {
        val uncached = ips
            .filter { !it.isNullOrBlank() && !isPrivateIp(it) }
            .distinct()
            .filter { MmkvManager.getCountryCache(it) == null }

        if (uncached.isEmpty()) return

        // ip-api.com/batch: max 100 per request, returns [{query, countryCode}]
        uncached.chunked(100).forEach { chunk ->
            semaphore.withPermit {
                try {
                    lookupBatch(chunk)
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Country batch lookup failed: ${e.message}")
                }
            }
        }
    }

    private data class IpApiRequest(val query: String, val fields: String = "countryCode")

    private suspend fun lookupBatch(ips: List<String>) = withContext(Dispatchers.IO) {
        val body = JsonUtil.toJson(ips.map { IpApiRequest(it) })
        val response = HttpUtil.postJson("http://ip-api.com/batch", body, 10000) ?: return@withContext
        try {
            val arr = com.google.gson.JsonParser.parseString(response).asJsonArray
            arr.forEach { el ->
                val obj = el.asJsonObject
                val ip = obj.get("query")?.asString ?: return@forEach
                val code = obj.get("countryCode")?.asString?.uppercase()
                    ?.takeIf { it.length == 2 } ?: return@forEach
                MmkvManager.setCountryCache(ip, code)
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Country batch parse failed: ${e.message}")
        }
    }

    // ── Private IP check ─────────────────────────────────────────────────────

    fun isPrivateIp(ip: String): Boolean {
        if (ip.contains('.').not()) return false // IPv6 skip for now
        return try {
            val parts = ip.split('.').map { it.toInt() }
            if (parts.size != 4) return false
            val a = parts[0]; val b = parts[1]
            a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
        } catch (e: Exception) { false }
    }

    // ── Country names map ─────────────────────────────────────────────────────

    val COUNTRY_NAMES: Map<String, String> = mapOf(
        "AF" to "Афганистан", "AL" to "Албания", "DZ" to "Алжир",
        "AD" to "Андорра", "AO" to "Ангола", "AG" to "Антигуа и Барбуда",
        "AR" to "Аргентина", "AM" to "Армения", "AU" to "Австралия",
        "AT" to "Австрия", "AZ" to "Азербайджан", "BS" to "Багамы",
        "BH" to "Бахрейн", "BD" to "Бангладеш", "BB" to "Барбадос",
        "BY" to "Беларусь", "BE" to "Бельгия", "BZ" to "Белиз",
        "BJ" to "Бенин", "BT" to "Бутан", "BO" to "Боливия",
        "BA" to "Босния и Герцеговина", "BW" to "Ботсвана",
        "BR" to "Бразилия", "BN" to "Бруней", "BG" to "Болгария",
        "BF" to "Буркина-Фасо", "BI" to "Бурунди", "CV" to "Кабо-Верде",
        "KH" to "Камбоджа", "CM" to "Камерун", "CA" to "Канада",
        "CF" to "ЦАР", "TD" to "Чад", "CL" to "Чили",
        "CN" to "Китай", "CO" to "Колумбия", "KM" to "Коморы",
        "CG" to "Конго", "CD" to "ДР Конго", "CR" to "Коста-Рика",
        "HR" to "Хорватия", "CU" to "Куба", "CY" to "Кипр",
        "CZ" to "Чехия", "DK" to "Дания", "DJ" to "Джибути",
        "DM" to "Доминика", "DO" to "Доминикана", "EC" to "Эквадор",
        "EG" to "Египет", "SV" to "Сальвадор", "GQ" to "Экв. Гвинея",
        "ER" to "Эритрея", "EE" to "Эстония", "SZ" to "Эсватини",
        "ET" to "Эфиопия", "FJ" to "Фиджи", "FI" to "Финляндия",
        "FR" to "Франция", "GA" to "Габон", "GM" to "Гамбия",
        "GE" to "Грузия", "DE" to "Германия", "GH" to "Гана",
        "GR" to "Греция", "GD" to "Гренада", "GT" to "Гватемала",
        "GN" to "Гвинея", "GW" to "Гвинея-Бисау", "GY" to "Гайана",
        "HT" to "Гаити", "HN" to "Гондурас", "HU" to "Венгрия",
        "IS" to "Исландия", "IN" to "Индия", "ID" to "Индонезия",
        "IR" to "Иран", "IQ" to "Ирак", "IE" to "Ирландия",
        "IL" to "Израиль", "IT" to "Италия", "JM" to "Ямайка",
        "JP" to "Япония", "JO" to "Иордания", "KZ" to "Казахстан",
        "KE" to "Кения", "KI" to "Кирибати", "KP" to "Сев. Корея",
        "KR" to "Юж. Корея", "KW" to "Кувейт", "KG" to "Киргизия",
        "LA" to "Лаос", "LV" to "Латвия", "LB" to "Ливан",
        "LS" to "Лесото", "LR" to "Либерия", "LY" to "Ливия",
        "LI" to "Лихтенштейн", "LT" to "Литва", "LU" to "Люксембург",
        "MG" to "Мадагаскар", "MW" to "Малави", "MY" to "Малайзия",
        "MV" to "Мальдивы", "ML" to "Мали", "MT" to "Мальта",
        "MH" to "Маршалловы о-ва", "MR" to "Мавритания",
        "MU" to "Маврикий", "MX" to "Мексика", "FM" to "Микронезия",
        "MD" to "Молдова", "MC" to "Монако", "MN" to "Монголия",
        "ME" to "Черногория", "MA" to "Марокко", "MZ" to "Мозамбик",
        "MM" to "Мьянма", "NA" to "Намибия", "NR" to "Науру",
        "NP" to "Непал", "NL" to "Нидерланды", "NZ" to "Нов. Зеландия",
        "NI" to "Никарагуа", "NE" to "Нигер", "NG" to "Нигерия",
        "MK" to "Сев. Македония", "NO" to "Норвегия", "OM" to "Оман",
        "PK" to "Пакистан", "PW" to "Палау", "PA" to "Панама",
        "PG" to "Папуа — Нов. Гвинея", "PY" to "Парагвай",
        "PE" to "Перу", "PH" to "Филиппины", "PL" to "Польша",
        "PT" to "Португалия", "QA" to "Катар", "RO" to "Румыния",
        "RU" to "Россия", "RW" to "Руанда",
        "KN" to "Сент-Китс и Невис", "LC" to "Сент-Люсия",
        "VC" to "Сент-Винсент", "WS" to "Самоа",
        "SM" to "Сан-Марино", "ST" to "Сан-Томе и Принсипи",
        "SA" to "Саудовская Аравия", "SN" to "Сенегал",
        "RS" to "Сербия", "SC" to "Сейшелы", "SL" to "Сьерра-Леоне",
        "SG" to "Сингапур", "SK" to "Словакия", "SI" to "Словения",
        "SB" to "Соломоновы о-ва", "SO" to "Сомали",
        "ZA" to "ЮАР", "SS" to "Юж. Судан", "ES" to "Испания",
        "LK" to "Шри-Ланка", "SD" to "Судан", "SR" to "Суринам",
        "SE" to "Швеция", "CH" to "Швейцария", "SY" to "Сирия",
        "TW" to "Тайвань", "TJ" to "Таджикистан", "TZ" to "Танзания",
        "TH" to "Таиланд", "TL" to "Восточный Тимор", "TG" to "Того",
        "TO" to "Тонга", "TT" to "Тринидад и Тобаго",
        "TN" to "Тунис", "TR" to "Турция", "TM" to "Туркменистан",
        "TV" to "Тувалу", "UG" to "Уганда", "UA" to "Украина",
        "AE" to "ОАЭ", "GB" to "Великобритания", "US" to "США",
        "UY" to "Уругвай", "UZ" to "Узбекистан", "VU" to "Вануату",
        "VE" to "Венесуэла", "VN" to "Вьетнам", "YE" to "Йемен",
        "ZM" to "Замбия", "ZW" to "Зимбабве",
        "HK" to "Гонконг", "MO" to "Макао", "PS" to "Палестина",
        "XK" to "Косово", "EU" to "Европейский союз",
        "NL" to "Нидерланды"
    )
}
