#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Verbindungsabbruch-Cache (siehe CLAUDE.md Roadmap Punkt 3 / M6).
 *
 * bt_stream_publish_packet() used to drop every packet outright while no
 * BLE client was connected/subscribed. This module stashes those packets
 * in a bounded ring buffer and drains DENM-first on (re)subscribe.
 *
 * Capacity: BLE_CACHE_CAPACITY * BLE_CACHE_SLOT_MAX bytes, static BSS
 * (no heap). Platform-agnostic — same firmware for Android and iOS.
 *
 * BLE_CACHE_SLOT_MAX (960): field payloads historically ≤643 B; Graz
 * MAPEM from pcap-20260811-080000.pcap is 927 B. 960 leaves headroom
 * without jumping to a full 802.11 MTU. Live path MAX_PAYLOAD (2048) is
 * unchanged — only the disconnect-cache slot size grows.
 *
 * BLE_CACHE_CAPACITY (32) — revised after 64×960 boot-loop (2026-08-13):
 *   - 64×960 B = 61.4 KB BSS left too little heap for WiFi+NimBLE on
 *     ESP32-C5; boot died in ble_hs_event_start_stage2 (rc != 0),
 *     rst:0x1a CPU_LOCKUP. Measured free heap at crash boot ≈103 KiB
 *     HP DRAM for malloc — BLE init needs a healthy chunk of that.
 *   - 32×960 B = 30.7 KB BSS: MAPEM still fits; ≈12.8 s of dense
 *     400 ms/packet before CAM eviction; DENM-first unchanged.
 *   - iOS still has no USB fallback — 32 is a deliberate trade of
 *     reconnect coverage vs. reliable boot, not a placeholder.
 *   - Revisit upward only after idf.py size + a clean BLE-start soak
 *     (no assert) on device.
 */

#define BLE_CACHE_CAPACITY  32
#define BLE_CACHE_SLOT_MAX  960

void ble_cache_init(void);

/* Store one packet in the cache (call only while BLE is disconnected /
 * not yet subscribed — bt_stream.c is responsible for that check). Drops
 * the oldest non-DENM entry to make room if full; if every slot happens
 * to hold a DENM, drops the oldest DENM. len > BLE_CACHE_SLOT_MAX is
 * silently ignored (matches MAX_PAYLOAD already enforced upstream in
 * bt_stream_publish_packet, this is just a defensive bound). */
void ble_cache_store(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec);

/* Number of packets currently held (for logging/diagnostics). */
int ble_cache_count(void);

typedef void (*ble_cache_emit_fn)(const uint8_t *payload, uint16_t len,
                                   uint32_t sec, uint32_t usec, void *ctx);

/* Drain the entire cache through `emit`, DENM entries first (oldest DENM
 * first, then oldest non-DENM first), then clear it. `emit` is called
 * synchronously from the caller's task context — bt_stream.c uses it to
 * re-publish each cached frame through the normal BLE notify queue, so
 * the caller controls pacing/backpressure, not this module. */
void ble_cache_drain(ble_cache_emit_fn emit, void *ctx);
