package org.opentrafficmap.receiver

import MQTTClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import mqtt.MQTTVersion
import mqtt.packets.Qos
import mqtt.packets.mqttv5.ReasonCode
import socket.tls.TLSClientSettings

/**
 * iOS actual: KMQTT 0.4.8.
 *
 * Pass [tlsCaPem] (PEM text or file path) for local/self-signed brokers.
 * When empty, falls back to bundled ISRG roots — KMQTT/OpenSSL on iOS
 * cannot use the system trust store (unlike Android Paho).
 */
actual class MqttBridge actual constructor(
    private val nodeId: String,
    brokerUri: String,
    clientIdPrefix: String,
    private val tlsCaPem: String?,
) {
    private val packetTopic = "its/$nodeId/packet"
    private val statusTopic = "its/$nodeId/status"
    private val endpoint = parseMqttBroker(brokerUri)
    private val clientId = "$clientIdPrefix-$nodeId"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var client: MQTTClient? = null
    private var connected = false
    private var wantRunning = false
    private var loopJob: Job? = null

    actual fun isConnected(): Boolean = connected

    actual fun start() {
        if (wantRunning) return
        wantRunning = true
        loopJob = scope.launch {
            while (isActive && wantRunning) {
                try {
                    runSession()
                } catch (e: Exception) {
                    connected = false
                    println("[MqttBridge] session error: ${e.message}")
                }
                if (!wantRunning) break
                delay(RECONNECT_MS)
            }
        }
    }

    actual fun publish(payload: ByteArray) {
        if (!connected) return
        val c = client ?: return
        try {
            c.publish(
                retain = false,
                qos = Qos.AT_MOST_ONCE,
                topic = packetTopic,
                payload = payload.toUByteArray(),
            )
        } catch (e: Exception) {
            println("[MqttBridge] publish error: ${e.message}")
        }
    }

    actual fun stop() {
        wantRunning = false
        val c = client
        client = null
        connected = false
        loopJob?.cancel()
        loopJob = null
        if (c == null) return
        scope.launch {
            try {
                c.publish(
                    retain = true,
                    qos = Qos.AT_MOST_ONCE,
                    topic = statusTopic,
                    payload = OFFLINE,
                )
                c.disconnect(ReasonCode.SUCCESS)
            } catch (_: Exception) {
            }
        }
    }

    private fun tlsSettings(): TLSClientSettings? {
        if (!endpoint.tls) return null
        val pem = tlsCaPem?.trim()?.takeIf { it.isNotEmpty() }
            ?: PublicTlsRoots.LETS_ENCRYPT_ISRG
        return TLSClientSettings(serverCertificate = pem, checkServerCertificate = true)
    }

    private suspend fun runSession() {
        lateinit var session: MQTTClient
        mutex.withLock {
            if (!wantRunning) return
            val tls = tlsSettings()
            val customCa = !tlsCaPem.isNullOrBlank()
            println(
                "[MqttBridge] KMQTT connect ${endpoint.describe()} " +
                    "tlsObj=${tls != null} caPem=${if (customCa) "custom" else "public-roots"}"
            )
            session = MQTTClient(
                mqttVersion = MQTTVersion.MQTT3_1_1,
                address = endpoint.host,
                port = endpoint.port,
                tls = tls,
                keepAlive = 60,
                cleanStart = true,
                clientId = clientId,
                willTopic = statusTopic,
                willPayload = OFFLINE,
                willRetain = true,
                willQos = Qos.AT_MOST_ONCE,
                connectTimeout = 15,
                connackTimeout = 15,
                onConnected = {
                    connected = true
                    println("[MqttBridge] connected ${endpoint.describe()} → $statusTopic online")
                    try {
                        // QoS0: same as packet path — QoS1 status often never
                        // completes under flaky TLS and leaves UI "connecting…".
                        session.publish(
                            retain = true,
                            qos = Qos.AT_MOST_ONCE,
                            topic = statusTopic,
                            payload = ONLINE,
                        )
                    } catch (e: Exception) {
                        println("[MqttBridge] status online error: ${e.message}")
                    }
                },
                onDisconnected = {
                    connected = false
                    println("[MqttBridge] disconnected ${endpoint.describe()}")
                },
            ) { }
            client = session
        }

        try {
            while (wantRunning && session.isRunning()) {
                session.step()
                yield()
            }
        } finally {
            if (connected) {
                println("[MqttBridge] session ended ${endpoint.describe()}")
            }
            connected = false
            mutex.withLock {
                if (client === session) client = null
            }
        }
    }

    private companion object {
        val ONLINE = "online".encodeToByteArray().toUByteArray()
        val OFFLINE = "offline".encodeToByteArray().toUByteArray()
        const val RECONNECT_MS = 5_000L
    }
}
