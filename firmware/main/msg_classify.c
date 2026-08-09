#include "msg_classify.h"

#include <string.h>

#define BTP_PORT_DENM 2002

static const int HDR_LENGTHS[] = { 24, 26, 30, 32 };
#define N_HDR_LENGTHS (sizeof(HDR_LENGTHS) / sizeof(HDR_LENGTHS[0]))

/* ext header length by GN Common Header HT nibble — same table as the
 * Python/Kotlin decoders. */
static int ext_hdr_len_for_ht(int ht)
{
    switch (ht) {
        case 1: return 4;
        case 2: return 48;
        case 3: return 56;
        case 4: return 44;
        case 5: return 28;
        case 6: return 36;
        default: return 28;
    }
}

/* Returns the BTP destination port at inner_off, or -1 if the payload is
 * too short for the fields this offset implies. */
static int btp_port_at_offset(const uint8_t *p, uint16_t len, int inner_off)
{
    if (len < inner_off + 8) return -1;
    uint8_t ht_hst = p[inner_off + 1];
    int ht  = (ht_hst >> 4) & 0x0F;
    int ext_hdr_len = ext_hdr_len_for_ht(ht);
    int btp_off = inner_off + 8 + ext_hdr_len;
    if (len < btp_off + 2) return -1;
    return (p[btp_off] << 8) | p[btp_off + 1];
}

/* Structural candidate check — same 3-condition heuristic as
 * _find_inner_gn_common_header_candidates() in the Python bridge. */
static bool looks_like_common_header(const uint8_t *p, uint16_t len, int off)
{
    if (off + 8 > len) return false;
    int nh_inner   = p[off] >> 4;
    int ht_inner   = p[off + 1] >> 4;
    int plen_inner = (p[off + 4] << 8) | p[off + 5];
    return (nh_inner == 0 || nh_inner == 1 || nh_inner == 2)
        && (ht_inner == 4 || ht_inner == 5 || ht_inner == 6)
        && plen_inner >= 1 && plen_inner <= 999;
}

bool its_msg_is_denm(const uint8_t *payload, uint16_t len)
{
    for (size_t i = 0; i < N_HDR_LENGTHS; i++) {
        int hdr_len = HDR_LENGTHS[i];
        if (len < hdr_len + 8 + 4 + 8) continue;
        if (memcmp(payload + hdr_len, "\xAA\xAA\x03\x00\x00\x00", 6) != 0) continue;

        int basic_off = hdr_len + 8;
        bool secured = (payload[basic_off] & 0x0F) == 2;

        if (!secured) {
            int port = btp_port_at_offset(payload, len, basic_off + 4);
            return port == BTP_PORT_DENM;
        }

        /* Secured: scan the same ~20-byte window as the Python/Kotlin
         * decoders, try each structural candidate, and prefer the first
         * one that maps to a *known* message type (CAM or DENM) — cheap
         * proxy for "plausible" without needing the full position-vector
         * validation the live decoders do (not needed just to classify
         * DENM vs. everything-else for cache priority). Falls back to the
         * first structural candidate if none maps to a known type. */
        int scan_start = basic_off + 4;
        int scan_end   = basic_off + 4 + 20;
        if (scan_end > (int)len - 36) scan_end = (int)len - 36;

        int first_candidate_port = -1;
        for (int off = scan_start; off < scan_end; off++) {
            if (!looks_like_common_header(payload, len, off)) continue;
            int port = btp_port_at_offset(payload, len, off);
            if (port < 0) continue;
            if (first_candidate_port < 0) first_candidate_port = port;
            if (port == BTP_PORT_DENM || port == 2001 /* CAM */) {
                return port == BTP_PORT_DENM;
            }
        }
        return first_candidate_port == BTP_PORT_DENM;
    }
    return false;
}
