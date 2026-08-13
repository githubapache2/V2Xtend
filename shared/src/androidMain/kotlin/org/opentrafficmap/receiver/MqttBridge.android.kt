package org.opentrafficmap.receiver

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.net.ssl.SSLContext

/**
 * Android actual: Eclipse Paho (system trust). [tlsCaPem] is ignored — use
 * a publicly trusted broker or install the CA into the device trust store.
 */
actual class MqttBridge actual constructor(
    private val nodeId: String,
    brokerUri: String,
    clientIdPrefix: String,
    @Suppress("UNUSED_PARAMETER") tlsCaPem: String?,
) {
    private val packetTopic = "its/$nodeId/packet"
    private val statusTopic = "its/$nodeId/status"
    private val brokerUri = mqttBrokerToPahoUri(parseMqttBroker(brokerUri))
    private val clientId = "$clientIdPrefix-$nodeId"

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var connected = false

    actual fun isConnected(): Boolean = connected

    actual fun start() {
        if (client != null) return
        val c = MqttAsyncClient(brokerUri, clientId, MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                connected = false
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 15
            keepAliveInterval = 60
            if (brokerUri.startsWith("ssl://")) {
                socketFactory = SSLContext.getDefault().socketFactory
            }
            setWill(statusTopic, "offline".toByteArray(), 1, true)
        }
        try {
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) {
                    connected = true
                    try {
                        c.publish(statusTopic, "online".toByteArray(), 1, true)
                    } catch (_: MqttException) {
                    }
                }

                override fun onFailure(token: IMqttToken?, exception: Throwable?) {
                    connected = false
                }
            })
            client = c
        } catch (_: MqttException) {
            try {
                c.close(true)
            } catch (_: Exception) {
            }
        }
    }

    actual fun publish(payload: ByteArray) {
        val c = client ?: return
        if (!connected) return
        try {
            c.publish(packetTopic, payload, 0, false)
        } catch (_: MqttException) {
        }
    }

    actual fun stop() {
        val c = client ?: return
        client = null
        connected = false
        try {
            c.publish(statusTopic, "offline".toByteArray(), 1, true)
            c.disconnect(2000L)
        } catch (_: Exception) {
        }
        try {
            c.close(true)
        } catch (_: Exception) {
        }
    }
}
