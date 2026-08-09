#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Verbindungsabbruch-Cache (siehe CLAUDE.md Roadmap Punkt 3).
 *
 * bt_stream_publish_packet() used to drop every packet outright while no
 * BLE client was connected/subscribed (`if (!connected) return;` — no
 * queue, no cache, just gone). This module gives it somewhere to put
 * those packets instead: a bounded ring buffer, held only while
 * disconnected, drained DENM-first the moment a client (re)subscribes to
 * notifications.
 *
 * Capacity: CACHE_CAPACITY * CACHE_SLOT_MAX bytes, static RAM (no heap).
 *
 * CACHE_SLOT_MAX (700) is a deliberate choice: sized against real field
 * data (all captured payloads so far <= 643 B) with headroom, not against
 * a worst-case IEEE 802.11 frame.
 *
 * CACHE_CAPACITY (24) is a round, pragmatic default, not a computed value
 * — no RAM budget or retry-interval calculation backs this specific
 * number. It comfortably covers the sparse traffic rates observed in
 * testing so far (see CLAUDE.md Punkt 3 for the empirical eviction-test
 * numbers), but has not been validated against sustained dense traffic
 * (e.g. a busy intersection) — revisit once real dense-traffic field data
 * exists (CLAUDE.md Punkt 1b).
 */

#define BLE_CACHE_CAPACITY  24
#define BLE_CACHE_SLOT_MAX  700

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
