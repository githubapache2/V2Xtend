package org.opentrafficmap.shared

/**
 * Tiny shared entry point for M1 wiring checks.
 * Real ITS-G5 decoder / API clients move here starting with M2.
 */
object SharedFacade {
    const val VERSION: String = "0.1.0-kmp-m1"

    fun hello(): String = "V2Xtend shared $VERSION (${Platform.name})"
}
