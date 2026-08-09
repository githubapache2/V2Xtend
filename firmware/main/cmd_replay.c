#include "cmd_replay.h"

#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "esp_console.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "bt_stream.h"
#include "replay_data.h"
#include "usb_stream.h"

static const char TAG[] = "REPLAY";

#define REPLAY_DEFAULT_INTERVAL_MS 1500
#define REPLAY_MIN_INTERVAL_MS     100
#define REPLAY_MAX_INTERVAL_MS     60000

static TaskHandle_t s_replay_task;
static volatile bool s_replay_loop_stop;

static void publish_one_frame(const replay_frame_t *f)
{
    struct timeval tv_now;
    gettimeofday(&tv_now, NULL);

    usb_stream_publish_packet(f->payload, f->len, (uint32_t)tv_now.tv_sec, (uint32_t)tv_now.tv_usec);
    bt_stream_publish_packet(f->payload, f->len, (uint32_t)tv_now.tv_sec, (uint32_t)tv_now.tv_usec);
}

static void replay_once_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "replay once: playing %d frames", REPLAY_FRAME_COUNT);
    for (int i = 0; i < REPLAY_FRAME_COUNT; i++) {
        publish_one_frame(&REPLAY_FRAMES[i]);
        ESP_LOGI(TAG, "replayed frame %d/%d (%u bytes)", i + 1, REPLAY_FRAME_COUNT,
                 (unsigned)REPLAY_FRAMES[i].len);
        vTaskDelay(pdMS_TO_TICKS(REPLAY_DEFAULT_INTERVAL_MS));
    }
    ESP_LOGI(TAG, "replay once: done");
    s_replay_task = NULL;
    vTaskDelete(NULL);
}

static void replay_loop_task(void *arg)
{
    uint32_t interval_ms = (uint32_t)(intptr_t)arg;
    ESP_LOGI(TAG, "replay loop: interval=%ums, %d frames/cycle — 'replay stop' to end",
             (unsigned)interval_ms, REPLAY_FRAME_COUNT);
    int i = 0;
    while (!s_replay_loop_stop) {
        publish_one_frame(&REPLAY_FRAMES[i]);
        ESP_LOGI(TAG, "replayed frame %d/%d (%u bytes)%s", i + 1, REPLAY_FRAME_COUNT,
                 (unsigned)REPLAY_FRAMES[i].len,
                 (i == 4 || i == 5) ? "  <-- DENM" : "");
        i = (i + 1) % REPLAY_FRAME_COUNT;
        vTaskDelay(pdMS_TO_TICKS(interval_ms));
    }
    ESP_LOGI(TAG, "replay loop: stopped");
    s_replay_task = NULL;
    vTaskDelete(NULL);
}

static int do_replay_cmd(int argc, char **argv)
{
    if (argc < 2) {
        printf("usage: replay once | replay loop [interval_ms] | replay stop\r\n");
        return 1;
    }

    if (strcmp(argv[1], "stop") == 0) {
        if (!s_replay_task) {
            printf("replay: nothing running\r\n");
            return 1;
        }
        s_replay_loop_stop = true;
        return 0;
    }

    if (s_replay_task) {
        printf("replay: already running — 'replay stop' first\r\n");
        return 1;
    }

    if (strcmp(argv[1], "once") == 0) {
        xTaskCreate(replay_once_task, "replay_once", 4096, NULL, 4, &s_replay_task);
        return 0;
    }

    if (strcmp(argv[1], "loop") == 0) {
        uint32_t interval_ms = REPLAY_DEFAULT_INTERVAL_MS;
        if (argc >= 3) {
            int v = atoi(argv[2]);
            if (v < REPLAY_MIN_INTERVAL_MS || v > REPLAY_MAX_INTERVAL_MS) {
                printf("replay: interval_ms must be %d..%d\r\n", REPLAY_MIN_INTERVAL_MS, REPLAY_MAX_INTERVAL_MS);
                return 1;
            }
            interval_ms = (uint32_t)v;
        }
        s_replay_loop_stop = false;
        xTaskCreate(replay_loop_task, "replay_loop", 4096, (void *)(intptr_t)interval_ms, 4, &s_replay_task);
        return 0;
    }

    printf("usage: replay once | replay loop [interval_ms] | replay stop\r\n");
    return 1;
}

void register_replay_cmd(void)
{
    const esp_console_cmd_t replay_cmd = {
        .command = "replay",
        .help = "Replay real captured CAM/DENM test packets through the "
                "publish path (no RF TX) — 'replay once', 'replay loop "
                "[interval_ms]', 'replay stop'",
        .hint = NULL,
        .func = &do_replay_cmd,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&replay_cmd));
}
