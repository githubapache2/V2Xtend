package org.opentrafficmap.receiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttBrokerParseTest {
    @Test
    fun parseMqtts() {
        val e = parseMqttBroker("mqtts://cits1.opentrafficmap.org:8883")
        assertEquals("cits1.opentrafficmap.org", e.host)
        assertEquals(8883, e.port)
        assertTrue(e.tls)
    }

    @Test
    fun parseSslDefaultPort() {
        val e = parseMqttBroker("ssl://broker.example")
        assertEquals("broker.example", e.host)
        assertEquals(8883, e.port)
        assertTrue(e.tls)
    }

    @Test
    fun parseMqttPlain() {
        val e = parseMqttBroker("mqtt://localhost:1883")
        assertEquals("localhost", e.host)
        assertEquals(1883, e.port)
        assertFalse(e.tls)
        assertEquals("tcp://localhost:1883", mqttBrokerToPahoUri(e))
    }

    @Test
    fun parseMqttCaseInsensitive() {
        val e = parseMqttBroker("MQTT://Host:1883")
        assertFalse(e.tls)
        assertEquals(1883, e.port)
    }

    @Test
    fun barePortInfersTls() {
        assertTrue(parseMqttBroker("10.0.0.1:8883").tls)
        assertFalse(parseMqttBroker("10.0.0.1:1883").tls)
        assertFalse(parseMqttBroker("10.0.0.1:1884").tls)
    }

    @Test
    fun mqttPlainOnTlsPortDetected() {
        val bad = parseMqttBroker("mqtt://10.0.0.1:8883")
        assertFalse(bad.tls)
        assertEquals(8883, bad.port)
        assertTrue(mqttPlainOnTlsPort(bad))
        assertFalse(mqttPlainOnTlsPort(parseMqttBroker("mqtt://10.0.0.1:1883")))
    }
}
