package org.opentrafficmap.receiver

/**
 * KMP MQTT publisher for OpenTrafficMap (`its/<nodeId>/packet|status`).
 *
 * Android: Eclipse Paho (TLS via system trust store).
 * iOS: KMQTT 0.4.8 (Kotlin-1.9-compatible; 1.0.0 needs Kotlin 2.1).
 *
 * Accepted broker forms: `mqtts://`, `ssl://`, `mqtt://`, `tcp://`, or bare
 * `host:port` (bare defaults to TLS).
 *
 * @param tlsCaPem optional PEM (or path) for a custom CA — used on iOS/KMQTT
 *   for local/self-signed TLS brokers. Android ignores this (system trust).
 */
expect class MqttBridge(
    nodeId: String,
    brokerUri: String = "ssl://cits1.opentrafficmap.org:8883",
    clientIdPrefix: String = "v2xtend",
    tlsCaPem: String? = null,
) {
    fun isConnected(): Boolean
    fun start()
    fun publish(payload: ByteArray)
    fun stop()
}

data class MqttBrokerEndpoint(val host: String, val port: Int, val tls: Boolean) {
    /** Human-readable for UI/debug: `plain host:1883` / `tls host:8883`. */
    fun describe(): String = "${if (tls) "tls" else "plain"} $host:$port"
}

/**
 * Parse broker URL. Scheme decides TLS (mqtt/tcp = plain, mqtts/ssl = TLS).
 * Bare `host:port` (no scheme): TLS inferred from port — 8883/8884 → TLS,
 * 1883/1884 → plain; other ports default TLS (legacy bare-host behaviour).
 */
fun parseMqttBroker(raw: String): MqttBrokerEndpoint {
    val s = raw.trim()
    val lower = s.lowercase()
    val (rest, tlsHint, defaultPort) = when {
        lower.startsWith("mqtts://") -> Triple(s.drop(8), true, 8883)
        lower.startsWith("ssl://") -> Triple(s.drop(6), true, 8883)
        lower.startsWith("mqtt://") -> Triple(s.drop(7), false, 1883)
        lower.startsWith("tcp://") -> Triple(s.drop(6), false, 1883)
        else -> Triple(s, null, null) // bare — infer below
    }
    val hostPort = rest.substringBefore('/').substringBefore('?')
    val host = hostPort.substringBefore(':').ifBlank { "cits1.opentrafficmap.org" }
    val explicitPort = hostPort.substringAfter(':', missingDelimiterValue = "")
        .toIntOrNull()
    val port = explicitPort
        ?: defaultPort
        ?: 8883
    val tls = tlsHint ?: when (port) {
        1883, 1884 -> false
        8883, 8884 -> true
        else -> true
    }
    return MqttBrokerEndpoint(host, port, tls)
}

/**
 * Detects classic misconfig: plain MQTT (`mqtt://`) pointed at a TLS listener.
 * That yields mosquitto `wrong version number` on 8883.
 */
fun mqttPlainOnTlsPort(endpoint: MqttBrokerEndpoint): Boolean =
    !endpoint.tls && endpoint.port in setOf(8883, 8884)

/** Paho-style URI (`ssl://host:port` / `tcp://host:port`). */
fun mqttBrokerToPahoUri(endpoint: MqttBrokerEndpoint): String {
    val scheme = if (endpoint.tls) "ssl" else "tcp"
    return "$scheme://${endpoint.host}:${endpoint.port}"
}
