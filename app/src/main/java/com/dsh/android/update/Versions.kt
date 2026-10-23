package com.dsh.android.update

/** Semantic-version ordering for the harness (npm) and app (GitHub tag) update checks. */
object Versions {
    private val CORE = Regex("^\\d+(\\.\\d+)*$")

    /** Strips `v`, `npm:`, a `-gecko` flavor suffix and `+build` metadata. */
    fun normalise(raw: String): String =
        raw.trim().removePrefix("npm:").removePrefix("v").removePrefix("V").substringBefore('+').removeSuffix("-gecko")

    /** Negative, zero or positive like [Comparator]; unparsable versions sort lowest. */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(normalise(a)) ?: return if (split(normalise(b)) == null) 0 else -1
        val (coreB, preB) = split(normalise(b)) ?: return 1
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0L }.compareTo(coreB.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        if (preA.isEmpty() || preB.isEmpty()) return preB.size.compareTo(0) - preA.size.compareTo(0)
        for (i in 0 until minOf(preA.size, preB.size)) {
            val x = preA[i]; val y = preB[i]
            val nx = x.all(Char::isDigit); val ny = y.all(Char::isDigit)
            val c = when {
                nx && ny -> x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
                nx -> -1
                ny -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return preA.size.compareTo(preB.size)
    }

    fun isNewer(candidate: String, current: String): Boolean =
        split(normalise(candidate)) != null && compare(candidate, current) > 0

    /** `MainActivity.assetStamp` is `<harness version>-<asset revision>`: "0.2.1-rc.2-1" → "0.2.1-rc.2". */
    fun harnessOfAssetStamp(stamp: String): String? {
        val s = stamp.trim().ifEmpty { return null }
        val last = s.substringAfterLast('-', "")
        return if (s.contains('-') && last.isNotEmpty() && last.all { it.isDigit() } && s.substringBeforeLast('-').let { split(it) != null })
            s.substringBeforeLast('-') else s
    }

    private fun split(v: String): Pair<List<Long>, List<String>>? {
        val core = v.substringBefore('-')
        if (!CORE.matches(core)) return null
        val pre = if (v.contains('-')) v.substringAfter('-').split('.') else emptyList()
        if (pre.any { !it.matches(Regex("[0-9A-Za-z-]+")) || (it.length > 1 && it.all(Char::isDigit) && it.startsWith('0')) }) return null
        return core.split('.').map { it.toLongOrNull() ?: return null } to pre
    }
}
