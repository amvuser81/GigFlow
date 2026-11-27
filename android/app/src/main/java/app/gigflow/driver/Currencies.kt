package app.gigflow.driver

/**
 * Currency registry — every currency the picker offers, with the display
 * rules each one needs:
 *
 *  - [suffix]: "1 850 Ft" instead of "Ft1850" (most CEE/Nordic/Arabic
 *    currencies trail the amount).
 *  - [zeroDecimal]: nobody writes "¥1,234.00" or "1 850.00 Ft" — whole units.
 *  - [symbolSpace]: "CHF 12.50", "Rp 50.000" — lettered symbols get a space.
 *  - [spoken]: TTS name so voice alerts say "forints", not "F T".
 *
 * The parser still matches the built-in symbol set on screen; anything not
 * built in is matched dynamically from the user's configured currency
 * (see OfferParser.extraMoney).
 */
object Currencies {

    data class Entry(
        val code: String,        // ISO 4217
        val symbol: String,      // display symbol
        val name: String,
        val spoken: String,
        val suffix: Boolean = false,
        val zeroDecimal: Boolean = false,
        val symbolSpace: Boolean = false,
    )

    val ALL: List<Entry> = listOf(
        // Americas
        Entry("USD", "$", "US Dollar", "dollars"),
        Entry("CAD", "CA$", "Canadian Dollar", "dollars"),
        Entry("MXN", "MX$", "Mexican Peso", "pesos"),
        Entry("BRL", "R$", "Brazilian Real", "reais"),
        Entry("ARS", "AR$", "Argentine Peso", "pesos", zeroDecimal = true),
        Entry("CLP", "CL$", "Chilean Peso", "pesos", zeroDecimal = true),
        Entry("COP", "CO$", "Colombian Peso", "pesos", zeroDecimal = true),
        Entry("PEN", "S/", "Peruvian Sol", "soles"),
        Entry("UYU", "UY$", "Uruguayan Peso", "pesos"),
        Entry("PYG", "₲", "Paraguayan Guaraní", "guaraníes", suffix = true, zeroDecimal = true),
        Entry("BOB", "Bs", "Bolivian Boliviano", "bolivianos", symbolSpace = true),
        Entry("XCD", "EC$", "East Caribbean Dollar", "dollars"),
        Entry("JMD", "J$", "Jamaican Dollar", "dollars"),
        Entry("TTD", "TT$", "Trinidad & Tobago Dollar", "dollars"),
        Entry("GTQ", "Q", "Guatemalan Quetzal", "quetzales"),
        Entry("DOP", "RD$", "Dominican Peso", "pesos"),
        Entry("CRC", "₡", "Costa Rican Colón", "colones", zeroDecimal = true),
        // Europe
        Entry("EUR", "€", "Euro", "euros"),
        Entry("GBP", "£", "British Pound", "pounds"),
        Entry("CHF", "CHF", "Swiss Franc", "francs", symbolSpace = true),
        Entry("HUF", "Ft", "Hungarian Forint", "forints", suffix = true, zeroDecimal = true),
        Entry("PLN", "zł", "Polish Złoty", "zloty", suffix = true),
        Entry("CZK", "Kč", "Czech Koruna", "koruna", suffix = true),
        Entry("RON", "lei", "Romanian Leu", "lei", suffix = true),
        Entry("BGN", "лв", "Bulgarian Lev", "leva", suffix = true),
        Entry("RSD", "дин", "Serbian Dinar", "dinars", suffix = true),
        Entry("HRK", "kn", "Croatian Kuna", "kuna", suffix = true),
        Entry("SEK", "kr", "Swedish Krona", "kronor", suffix = true),
        Entry("NOK", "kr", "Norwegian Krone", "kroner", suffix = true),
        Entry("DKK", "kr", "Danish Krone", "kroner", suffix = true),
        Entry("ISK", "kr", "Icelandic Króna", "krónur", suffix = true, zeroDecimal = true),
        Entry("UAH", "₴", "Ukrainian Hryvnia", "hryvnia", suffix = true),
        Entry("RUB", "₽", "Russian Ruble", "rubles", suffix = true),
        Entry("TRY", "₺", "Turkish Lira", "lira", suffix = true),
        Entry("MDL", "L", "Moldovan Leu", "lei", suffix = true),
        Entry("MKD", "ден", "Macedonian Denar", "denars", suffix = true),
        Entry("ALL", "L", "Albanian Lek", "lekë", suffix = true),
        Entry("BAM", "KM", "Bosnian Convertible Mark", "convertible marks", suffix = true),
        Entry("GEL", "₾", "Georgian Lari", "lari", suffix = true),
        Entry("AMD", "֏", "Armenian Dram", "dram", suffix = true),
        Entry("AZN", "₼", "Azerbaijani Manat", "manat", suffix = true),
        Entry("KZT", "₸", "Kazakhstani Tenge", "tenge", suffix = true),
        Entry("UZS", "soʻm", "Uzbekistani Som", "som", suffix = true, zeroDecimal = true),
        Entry("KGS", "сом", "Kyrgyzstani Som", "som", suffix = true),
        // Middle East & Africa
        Entry("ILS", "₪", "Israeli Shekel", "shekels"),
        Entry("AED", "د.إ", "UAE Dirham", "dirhams", suffix = true),
        Entry("SAR", "﷼", "Saudi Riyal", "riyals", suffix = true),
        Entry("QAR", "QR", "Qatari Riyal", "riyals", suffix = true),
        Entry("KWD", "KD", "Kuwaiti Dinar", "dinars", suffix = true),
        Entry("BHD", "BD", "Bahraini Dinar", "dinars", suffix = true),
        Entry("JOD", "JD", "Jordanian Dinar", "dinars", suffix = true),
        Entry("LBP", "L£", "Lebanese Pound", "pounds", suffix = true),
        Entry("EGP", "E£", "Egyptian Pound", "pounds"),
        Entry("MAD", "DH", "Moroccan Dirham", "dirhams", symbolSpace = true),
        Entry("DZD", "DA", "Algerian Dinar", "dinars", suffix = true),
        Entry("TND", "DT", "Tunisian Dinar", "dinars", suffix = true),
        Entry("ZAR", "R", "South African Rand", "rand"),
        Entry("NGN", "₦", "Nigerian Naira", "naira"),
        Entry("KES", "KSh", "Kenyan Shilling", "shillings", symbolSpace = true),
        Entry("GHS", "₵", "Ghanaian Cedi", "cedis"),
        Entry("UGX", "USh", "Ugandan Shilling", "shillings", symbolSpace = true, zeroDecimal = true),
        Entry("TZS", "TSh", "Tanzanian Shilling", "shillings", symbolSpace = true, zeroDecimal = true),
        Entry("XOF", "CFA", "West African CFA Franc", "CFA francs", suffix = true, zeroDecimal = true),
        Entry("XAF", "FCFA", "Central African CFA Franc", "CFA francs", suffix = true, zeroDecimal = true),
        // Asia-Pacific
        Entry("INR", "₹", "Indian Rupee", "rupees"),
        Entry("PKR", "₨", "Pakistani Rupee", "rupees"),
        Entry("BDT", "৳", "Bangladeshi Taka", "taka"),
        Entry("LKR", "Rs", "Sri Lankan Rupee", "rupees", symbolSpace = true),
        Entry("NPR", "रू", "Nepalese Rupee", "rupees"),
        Entry("JPY", "¥", "Japanese Yen", "yen", zeroDecimal = true),
        Entry("CNY", "¥", "Chinese Yuan", "yuan"),
        Entry("KRW", "₩", "South Korean Won", "won", zeroDecimal = true),
        Entry("HKD", "HK$", "Hong Kong Dollar", "dollars"),
        Entry("TWD", "NT$", "New Taiwan Dollar", "dollars"),
        Entry("SGD", "S$", "Singapore Dollar", "dollars"),
        Entry("MYR", "RM", "Malaysian Ringgit", "ringgit", symbolSpace = true),
        Entry("THB", "฿", "Thai Baht", "baht"),
        Entry("VND", "₫", "Vietnamese Dong", "dong", suffix = true, zeroDecimal = true),
        Entry("PHP", "₱", "Philippine Peso", "pesos"),
        Entry("IDR", "Rp", "Indonesian Rupiah", "rupiah", symbolSpace = true, zeroDecimal = true),
        Entry("AUD", "A$", "Australian Dollar", "dollars"),
        Entry("NZD", "NZ$", "New Zealand Dollar", "dollars"),
        Entry("MNT", "₮", "Mongolian Tögrög", "tugrik", suffix = true),
    )

    private val byCode = ALL.associateBy { it.code }
    private val bySymbol = mutableMapOf<String, Entry>().apply {
        ALL.forEach { putIfAbsent(it.symbol, it) }
    }

    fun forCode(code: String): Entry? = byCode[code]
    fun forSymbol(symbol: String): Entry? = bySymbol[symbol]

    /** "United States Dollar (USD)"-style picker label. */
    fun label(c: Entry) = "${c.symbol}  ·  ${c.name}"

    /**
     * Rough units per US dollar, for the parser's plausibility gate only —
     * never for display, scoring or records.
     *
     * A driver app quotes 1 850 Ft for 12 km; without a scale that reads as
     * "154 per km" and the gate throws away a perfectly ordinary offer. The
     * numbers are coarse on purpose: the gate's bounds span two orders of
     * magnitude, so being 20% off a real exchange rate changes nothing, and
     * staying offline beats fetching rates.
     */
    private val PER_USD = mapOf(
        "USD" to 1.0, "CAD" to 1.36, "MXN" to 17.0, "BRL" to 5.0,
        "ARS" to 950.0, "CLP" to 950.0, "COP" to 4000.0, "PEN" to 3.8,
        "UYU" to 40.0, "PYG" to 7500.0, "BOB" to 6.9, "XCD" to 2.7,
        "JMD" to 156.0, "TTD" to 6.8, "GTQ" to 7.8, "DOP" to 58.0,
        "CRC" to 520.0,
        "EUR" to 0.92, "GBP" to 0.79, "CHF" to 0.88, "HUF" to 360.0,
        "PLN" to 4.0, "CZK" to 23.0, "RON" to 4.6, "BGN" to 1.8,
        "RSD" to 108.0, "HRK" to 6.9, "SEK" to 10.5, "NOK" to 10.7,
        "DKK" to 6.9, "ISK" to 138.0, "UAH" to 39.0, "RUB" to 90.0,
        "TRY" to 34.0, "MDL" to 18.0, "MKD" to 57.0, "ALL" to 93.0,
        "BAM" to 1.8, "GEL" to 2.7, "AMD" to 390.0, "AZN" to 1.7,
        "KZT" to 470.0, "UZS" to 12600.0, "KGS" to 89.0,
        "ILS" to 3.7, "AED" to 3.67, "SAR" to 3.75, "QAR" to 3.64,
        "KWD" to 0.31, "BHD" to 0.38, "JOD" to 0.71, "LBP" to 89500.0,
        "EGP" to 48.0, "MAD" to 10.0, "DZD" to 134.0, "TND" to 3.1,
        "ZAR" to 18.0, "NGN" to 1500.0, "KES" to 129.0, "GHS" to 15.0,
        "UGX" to 3700.0, "TZS" to 2600.0, "XOF" to 600.0, "XAF" to 600.0,
        "INR" to 84.0, "PKR" to 278.0, "BDT" to 118.0, "LKR" to 295.0,
        "NPR" to 134.0, "JPY" to 150.0, "CNY" to 7.2, "KRW" to 1350.0,
        "HKD" to 7.8, "TWD" to 32.0, "SGD" to 1.34, "MYR" to 4.5,
        "THB" to 34.0, "VND" to 25000.0, "PHP" to 58.0, "IDR" to 15800.0,
        "AUD" to 1.5, "NZD" to 1.65, "MNT" to 3400.0,
    )

    /** How many units of [symbol]'s currency make a US dollar (rough). Unknown
     *  symbols are treated as USD-scale — the same behaviour as before the
     *  scale existed, which is the safe default for a sanity check. */
    fun usdScale(symbol: String): Double =
        forSymbol(symbol)?.let { PER_USD[it.code] } ?: 1.0

    /**
     * Render cents with the symbol's conventions. Unknown/custom symbols fall
     * back to prefix-with-space-if-lettered ("XYZ 12.50", "€-like" 12.50x).
     */
    fun format(cents: Int, symbol: String): String {
        val c = forSymbol(symbol)
        val v = cents / 100.0
        if (c == null) {
            return if (symbol.firstOrNull()?.isLetter() == true)
                "%s %,.2f".format(symbol, v)
            else "%s%,.2f".format(symbol, v)
        }
        return when {
            c.suffix && c.zeroDecimal -> "%,.0f %s".format(v, symbol)
            c.suffix -> "%,.2f %s".format(v, symbol)
            c.zeroDecimal -> "%s%,.0f".format(symbol, v)
            c.symbolSpace -> "%s %,.2f".format(symbol, v)
            else -> "%s%,.2f".format(symbol, v)
        }
    }

    /** Spoken name for TTS — "forints", "zloty", the symbol if unknown. */
    fun spoken(symbol: String): String = forSymbol(symbol)?.spoken ?: symbol

    /** Voice-ready amount: "12.50 dollars" / "1850 forints". */
    fun spokenAmount(cents: Int, symbol: String): String {
        val c = forSymbol(symbol)
        return if (c?.zeroDecimal == true)
            "%.0f %s".format(cents / 100.0, c.spoken)
        else "%.2f %s".format(cents / 100.0, c?.spoken ?: symbol)
    }
}
