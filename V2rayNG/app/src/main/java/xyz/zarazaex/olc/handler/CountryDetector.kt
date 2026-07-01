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
    fun extractFlagCode(text: String): String? = extractAllFlagCodes(text).firstOrNull()

    /** Extract all flag emojis found in [text] and return their ISO country codes. */
    fun extractAllFlagCodes(text: String): List<String> {
        val result = mutableListOf<String>()
        val codePoints = text.codePoints().toArray()
        var i = 0
        while (i < codePoints.size - 1) {
            val cp1 = codePoints[i]
            val cp2 = codePoints[i + 1]
            if (cp1 in 0x1F1E6..0x1F1FF && cp2 in 0x1F1E6..0x1F1FF) {
                val c1 = ('A'.code + (cp1 - 0x1F1E6)).toChar()
                val c2 = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                result.add("$c1$c2")
                i += 2
                continue
            }
            i++
        }
        return result
    }

    /** Get best country code for a server (emoji first, then cache). */
    fun getCountryCode(remarks: String, serverIp: String?): String {
        extractFlagCode(remarks)?.let { return it }
        if (!serverIp.isNullOrBlank() && !isPrivateIp(serverIp)) {
            MmkvManager.getCountryCache(serverIp)?.let { return it }
        }
        return UNKNOWN
    }

    /** Get all country codes for a server (all emojis, then cache fallback). */
    fun getCountryCodes(remarks: String, serverIp: String?): List<String> {
        val flags = extractAllFlagCodes(remarks)
        if (flags.isNotEmpty()) return flags
        if (!serverIp.isNullOrBlank() && !isPrivateIp(serverIp)) {
            MmkvManager.getCountryCache(serverIp)?.let { return listOf(it) }
        }
        return listOf(UNKNOWN)
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
        "AF" to "Afghanistan", "AL" to "Albania", "DZ" to "Algeria",
        "AD" to "Andorra", "AO" to "Angola", "AG" to "Antigua and Barbuda",
        "AR" to "Argentina", "AM" to "Armenia", "AU" to "Australia",
        "AT" to "Austria", "AZ" to "Azerbaijan", "BS" to "Bahamas",
        "BH" to "Bahrain", "BD" to "Bangladesh", "BB" to "Barbados",
        "BY" to "Belarus", "BE" to "Belgium", "BZ" to "Belize",
        "BJ" to "Benin", "BT" to "Bhutan", "BO" to "Bolivia",
        "BA" to "Bosnia and Herzegovina", "BW" to "Botswana",
        "BR" to "Brazil", "BN" to "Brunei", "BG" to "Bulgaria",
        "BF" to "Burkina Faso", "BI" to "Burundi", "CV" to "Cape Verde",
        "KH" to "Cambodia", "CM" to "Cameroon", "CA" to "Canada",
        "CF" to "Central African Republic", "TD" to "Chad", "CL" to "Chile",
        "CN" to "China", "CO" to "Colombia", "KM" to "Comoros",
        "CG" to "Congo", "CD" to "DR Congo", "CR" to "Costa Rica",
        "HR" to "Croatia", "CU" to "Cuba", "CY" to "Cyprus",
        "CZ" to "Czech Republic", "DK" to "Denmark", "DJ" to "Djibouti",
        "DM" to "Dominica", "DO" to "Dominican Republic", "EC" to "Ecuador",
        "EG" to "Egypt", "SV" to "El Salvador", "GQ" to "Equatorial Guinea",
        "ER" to "Eritrea", "EE" to "Estonia", "SZ" to "Eswatini",
        "ET" to "Ethiopia", "FJ" to "Fiji", "FI" to "Finland",
        "FR" to "France", "GA" to "Gabon", "GM" to "Gambia",
        "GE" to "Georgia", "DE" to "Germany", "GH" to "Ghana",
        "GR" to "Greece", "GD" to "Grenada", "GT" to "Guatemala",
        "GN" to "Guinea", "GW" to "Guinea-Bissau", "GY" to "Guyana",
        "HT" to "Haiti", "HN" to "Honduras", "HU" to "Hungary",
        "IS" to "Iceland", "IN" to "India", "ID" to "Indonesia",
        "IR" to "Iran", "IQ" to "Iraq", "IE" to "Ireland",
        "IL" to "Israel", "IT" to "Italy", "JM" to "Jamaica",
        "JP" to "Japan", "JO" to "Jordan", "KZ" to "Kazakhstan",
        "KE" to "Kenya", "KI" to "Kiribati", "KP" to "North Korea",
        "KR" to "South Korea", "KW" to "Kuwait", "KG" to "Kyrgyzstan",
        "LA" to "Laos", "LV" to "Latvia", "LB" to "Lebanon",
        "LS" to "Lesotho", "LR" to "Liberia", "LY" to "Libya",
        "LI" to "Liechtenstein", "LT" to "Lithuania", "LU" to "Luxembourg",
        "MG" to "Madagascar", "MW" to "Malawi", "MY" to "Malaysia",
        "MV" to "Maldives", "ML" to "Mali", "MT" to "Malta",
        "MH" to "Marshall Islands", "MR" to "Mauritania",
        "MU" to "Mauritius", "MX" to "Mexico", "FM" to "Micronesia",
        "MD" to "Moldova", "MC" to "Monaco", "MN" to "Mongolia",
        "ME" to "Montenegro", "MA" to "Morocco", "MZ" to "Mozambique",
        "MM" to "Myanmar", "NA" to "Namibia", "NR" to "Nauru",
        "NP" to "Nepal", "NL" to "Netherlands", "NZ" to "New Zealand",
        "NI" to "Nicaragua", "NE" to "Niger", "NG" to "Nigeria",
        "MK" to "North Macedonia", "NO" to "Norway", "OM" to "Oman",
        "PK" to "Pakistan", "PW" to "Palau", "PA" to "Panama",
        "PG" to "Papua New Guinea", "PY" to "Paraguay",
        "PE" to "Peru", "PH" to "Philippines", "PL" to "Poland",
        "PT" to "Portugal", "QA" to "Qatar", "RO" to "Romania",
        "RU" to "Russia", "RW" to "Rwanda",
        "KN" to "Saint Kitts and Nevis", "LC" to "Saint Lucia",
        "VC" to "Saint Vincent", "WS" to "Samoa",
        "SM" to "San Marino", "ST" to "Sao Tome and Principe",
        "SA" to "Saudi Arabia", "SN" to "Senegal",
        "RS" to "Serbia", "SC" to "Seychelles", "SL" to "Sierra Leone",
        "SG" to "Singapore", "SK" to "Slovakia", "SI" to "Slovenia",
        "SB" to "Solomon Islands", "SO" to "Somalia",
        "ZA" to "South Africa", "SS" to "South Sudan", "ES" to "Spain",
        "LK" to "Sri Lanka", "SD" to "Sudan", "SR" to "Suriname",
        "SE" to "Sweden", "CH" to "Switzerland", "SY" to "Syria",
        "TW" to "Taiwan", "TJ" to "Tajikistan", "TZ" to "Tanzania",
        "TH" to "Thailand", "TL" to "East Timor", "TG" to "Togo",
        "TO" to "Tonga", "TT" to "Trinidad and Tobago",
        "TN" to "Tunisia", "TR" to "Turkey", "TM" to "Turkmenistan",
        "TV" to "Tuvalu", "UG" to "Uganda", "UA" to "Ukraine",
        "AE" to "UAE", "GB" to "United Kingdom", "US" to "USA",
        "UY" to "Uruguay", "UZ" to "Uzbekistan", "VU" to "Vanuatu",
        "VE" to "Venezuela", "VN" to "Vietnam", "YE" to "Yemen",
        "ZM" to "Zambia", "ZW" to "Zimbabwe",
        "HK" to "Hong Kong", "MO" to "Macau", "PS" to "Palestine",
        "XK" to "Kosovo", "EU" to "European Union"
    )
}
