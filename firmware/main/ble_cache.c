#include "ble_cache.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#include "esp_log.h"

#include "msg_classify.h"

static const char TAG[] = "BLE_CACHE";

typedef struct {
    bool     used;
    bool     is_denm;
    uint32_t seq;
    uint16_t len;
    uint32_t sec;
    uint32_t usec;
    uint8_t  data[BLE_CACHE_SLOT_MAX];
} cache_slot_t;

static cache_slot_t     s_slots[BLE_CACHE_CAPACITY];
static uint32_t         s_next_seq;
static int              s_count;
static SemaphoreHandle_t s_mutex;

void ble_cache_init(void)
{
    if (s_mutex) return;
    s_mutex = xSemaphoreCreateMutex();
    memset(s_slots, 0, sizeof(s_slots));
    s_next_seq = 0;
    s_count = 0;
}

static int find_free_slot(void)
{
    for (int i = 0; i < BLE_CACHE_CAPACITY; i++) {
        if (!s_slots[i].used) return i;
    }
    return -1;
}

/* Returns the index of the oldest (lowest seq) used slot matching
 * want_denm, or -1 if none. */
static int find_oldest(bool want_denm)
{
    int best = -1;
    for (int i = 0; i < BLE_CACHE_CAPACITY; i++) {
        if (!s_slots[i].used || s_slots[i].is_denm != want_denm) continue;
        if (best < 0 || s_slots[i].seq < s_slots[best].seq) best = i;
    }
    return best;
}

void ble_cache_store(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec)
{
    if (len == 0 || len > BLE_CACHE_SLOT_MAX) return;
    if (!s_mutex) ble_cache_init();

    bool is_denm = its_msg_is_denm(payload, len);

    xSemaphoreTake(s_mutex, portMAX_DELAY);

    int slot = find_free_slot();
    if (slot < 0) {
        /* Full: evict oldest non-DENM to protect DENM data; if every slot
         * happens to be a DENM, evict the oldest DENM rather than refuse
         * new data outright. */
        slot = find_oldest(false);
        if (slot < 0) slot = find_oldest(true);
        if (slot < 0) {
            /* Shouldn't happen (s_count would be 0), but stay defensive. */
            xSemaphoreGive(s_mutex);
            return;
        }
        s_count--;
    }

    cache_slot_t *s = &s_slots[slot];
    s->used    = true;
    s->is_denm = is_denm;
    s->seq     = s_next_seq++;
    s->len     = len;
    s->sec     = sec;
    s->usec    = usec;
    memcpy(s->data, payload, len);
    s_count++;

    xSemaphoreGive(s_mutex);
}

int ble_cache_count(void)
{
    return s_count;
}

void ble_cache_drain(ble_cache_emit_fn emit, void *ctx)
{
    if (!s_mutex) return;

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    int n = s_count;
    xSemaphoreGive(s_mutex);
    if (n == 0) return;

    ESP_LOGI(TAG, "draining %d cached frame(s)", n);

    /* Two passes: DENM first (oldest-first), then everything else
     * (oldest-first). Emit is called with the mutex released so a slow
     * BLE send (emit typically blocks on the notify queue) doesn't stall
     * ble_cache_store() from the sniffer task. */
    for (int pass = 0; pass < 2; pass++) {
        bool want_denm = (pass == 0);
        for (;;) {
            xSemaphoreTake(s_mutex, portMAX_DELAY);
            int idx = find_oldest(want_denm);
            cache_slot_t copy;
            if (idx >= 0) {
                copy = s_slots[idx];
                s_slots[idx].used = false;
                s_count--;
            }
            xSemaphoreGive(s_mutex);

            if (idx < 0) break;
            emit(copy.data, copy.len, copy.sec, copy.usec, ctx);
        }
    }
}
