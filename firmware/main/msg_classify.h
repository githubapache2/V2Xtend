#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Lightweight classifier: is this raw 802.11 payload a DENM message?
 *
 * Ported from the same GN Common Header envelope search used by the
 * Android app (ItsG5Decoder.kt) and the Python bridge
 * (its_g5_bridge.py:_find_inner_gn_common_header_candidates), reduced to
 * just enough to read the BTP destination port — no position/station
 * fields needed here, this only exists to prioritise DENM packets in the
 * BLE disconnect cache (see ble_cache.h). Deliberately does NOT try to be
 * a full decoder; on any parse ambiguity it returns false (treat as
 * regular/non-priority), matching the "no crash / no regression" fallback
 * philosophy already used elsewhere in this codebase (usb_stream.c, the
 * bridge's tshark fallback before it was replaced, etc). */
bool its_msg_is_denm(const uint8_t *payload, uint16_t len);
