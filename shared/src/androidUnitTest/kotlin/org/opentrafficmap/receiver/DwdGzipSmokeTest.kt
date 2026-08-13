package org.opentrafficmap.receiver

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live smoke: DWD feed is Content-Encoding: gzip; SharedHttp must decompress
 * to real JSON (Android HttpURLConnection historically did not).
 *
 * Network required — skip offline CI if this becomes flaky.
 */
class DwdGzipSmokeTest {
    @Test
    fun fetchWarnings_returnsDecompressedRealData() = runBlocking {
        val warnings = DwdWarningsApi.fetchWarnings()
        assertTrue(
            warnings.isNotEmpty(),
            "expected real DWD warnings after gzip decode, got empty (silent gzip/parse failure?)",
        )
        val sample = warnings.first()
        assertTrue(sample.headline.isNotBlank(), "headline blank — JSON likely garbage/empty")
        assertTrue(sample.regions.isNotEmpty(), "no regions — polygon parse failed")
        assertTrue(
            sample.regions.first().size >= 3,
            "region ring too small (${sample.regions.first().size})",
        )
        println(
            "DWD gzip OK: count=${warnings.size} sample='${sample.headline.take(60)}' " +
                "level=${sample.level} rings=${sample.regions.size} " +
                "pts=${sample.regions.first().size}",
        )
    }
}
