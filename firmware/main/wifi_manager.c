/**
 * wifi_manager.c — home WiFi connectivity for V2Xtend
 *
 * Two-phase init so it works alongside the existing sniffer:
 *
 *   wifi_manager_init()   — read NVS config, register event handlers,
 *                           create netif.  Called BEFORE sniffer_autostart.
 *
 *   wifi_manager_start()  — called AFTER sniffer_autostart.
 *     REALTIME: sniffer_pause → STA mode → connect → on IP: re-enable
 *               promiscuous on top of STA (same channel sniffing).
 *     CYCLE:    launch cycle_task which owns the radio time-sharing:
 *               sniff_ms of sniffing → sniffer_pause → STA+connect →
 *               wifi_ms of MQTT flush → stop STA → sniffer_resume.
 */

#include "wifi_manager.h"

#include <string.h>

#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "lwip/ip4_addr.h"
#include "lwip/inet.h"

#include "cmd_sniffer.h"
#include "config.h"
#include "usb_stream.h"

static const char TAG[] = "WIFI_MGR";

#define EV_CONNECTED    BIT0
#define EV_DISCONNECTED BIT1
#define RECONNECT_MS    5000

static EventGroupHandle_t s_evg;
static volatile bool      s_connected = false;
static volatile bool      s_cycle_active = false; /* cycle task owns radio */
static uint8_t            s_conn_mode;
static uint8_t            s_sniff_mode;
static uint32_t           s_sniff_ms;
static uint32_t           s_wifi_ms;
static esp_netif_t       *s_netif = NULL;
static TaskHandle_t       s_cycle_task = NULL;

/* ── helpers ──────────────────────────────────────────────────────────── */

static void load_str(config_index_t idx, char *buf, size_t len)
{
    size_t sz = len;
    if (config_get_str(idx, buf, &sz) != ESP_OK) buf[0] = '\0';
}

static bool has_str(config_index_t idx)
{
    char tmp[4]; size_t sz = sizeof(tmp);
    return config_get_str(idx, tmp, &sz) == ESP_OK && tmp[0] != '\0';
}

static void apply_ip_config(void)
{
    char ip[16], nm[16], gw[16], dns[16];
    load_str(CONFIG_INDEX_WIFI_IP, ip, sizeof(ip));
    if (ip[0] == '\0') return;   /* DHCP */

    load_str(CONFIG_INDEX_WIFI_NM,  nm,  sizeof(nm));
    load_str(CONFIG_INDEX_WIFI_GW,  gw,  sizeof(gw));
    load_str(CONFIG_INDEX_WIFI_DNS, dns, sizeof(dns));

    esp_netif_dhcpc_stop(s_netif);
    esp_netif_ip_info_t info = {0};
    ip4addr_aton(ip[0] ? ip : "0.0.0.0",       (ip4_addr_t *)&info.ip);
    ip4addr_aton(nm[0] ? nm : "255.255.255.0",  (ip4_addr_t *)&info.netmask);
    ip4addr_aton(gw[0] ? gw : "0.0.0.0",        (ip4_addr_t *)&info.gw);
    esp_netif_set_ip_info(s_netif, &info);

    if (dns[0]) {
        esp_netif_dns_info_t d = {0};
        ip4addr_aton(dns, (ip4_addr_t *)&d.ip.u_addr.ip4);
        d.ip.type = ESP_IPADDR_TYPE_V4;
        esp_netif_set_dns_info(s_netif, ESP_NETIF_DNS_MAIN, &d);
    }
    ESP_LOGI(TAG, "static IP configured: %s", ip);
}

/* Start STA connection using NVS credentials (tries wifi1 → wifi2 → open). */
static void connect_from_nvs(void)
{
    if (has_str(CONFIG_INDEX_WIFI1_SSID)) {
        char ssid[33], pass[65];
        load_str(CONFIG_INDEX_WIFI1_SSID, ssid, sizeof(ssid));
        load_str(CONFIG_INDEX_WIFI1_PASS, pass, sizeof(pass));
        wifi_config_t cfg = {0};
        strncpy((char *)cfg.sta.ssid,     ssid, sizeof(cfg.sta.ssid) - 1);
        strncpy((char *)cfg.sta.password, pass, sizeof(cfg.sta.password) - 1);
        cfg.sta.failure_retry_cnt = 1;
        esp_wifi_set_config(WIFI_IF_STA, &cfg);
        esp_wifi_connect();
        ESP_LOGI(TAG, "connecting to '%s'", ssid);
    } else if (has_str(CONFIG_INDEX_WIFI2_SSID)) {
        char ssid[33], pass[65];
        load_str(CONFIG_INDEX_WIFI2_SSID, ssid, sizeof(ssid));
        load_str(CONFIG_INDEX_WIFI2_PASS, pass, sizeof(pass));
        wifi_config_t cfg = {0};
        strncpy((char *)cfg.sta.ssid,     ssid, sizeof(cfg.sta.ssid) - 1);
        strncpy((char *)cfg.sta.password, pass, sizeof(cfg.sta.password) - 1);
        cfg.sta.failure_retry_cnt = 1;
        esp_wifi_set_config(WIFI_IF_STA, &cfg);
        esp_wifi_connect();
        ESP_LOGI(TAG, "connecting to '%s' (wifi2)", ssid);
    } else {
        uint8_t open = 0;
        config_get_u8(CONFIG_INDEX_WIFI_OPEN, &open);
        if (open) {
            /* Scan for open APs */
            wifi_scan_config_t sc = {
                .channel = 0, .show_hidden = false,
                .scan_type = WIFI_SCAN_TYPE_ACTIVE,
                .scan_time.active.min = 100, .scan_time.active.max = 400,
            };
            esp_wifi_scan_start(&sc, false);
            ESP_LOGI(TAG, "scanning for open AP");
        } else {
            ESP_LOGW(TAG, "no WiFi credentials configured");
        }
    }
}

/* ── WiFi event handler ───────────────────────────────────────────────── */

static void on_wifi(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT) {
        switch (id) {
        case WIFI_EVENT_STA_START:
            if (!usb_cfg_is_scanning())
                connect_from_nvs();
            break;

        case WIFI_EVENT_STA_DISCONNECTED: {
            wifi_event_sta_disconnected_t *ev = data;
            ESP_LOGW(TAG, "disconnected reason=%d", ev ? ev->reason : -1);
            s_connected = false;
            xEventGroupSetBits(s_evg, EV_DISCONNECTED);
            /* Realtime: try reconnect after back-off. Cycle: task handles it. */
            if (!s_cycle_active && s_sniff_mode == SNIFF_MODE_REALTIME) {
                vTaskDelay(pdMS_TO_TICKS(RECONNECT_MS));
                esp_wifi_connect();
            }
            break;
        }

        case WIFI_EVENT_SCAN_DONE: {
            uint16_t n = 0; esp_wifi_scan_get_ap_num(&n);
            if (n == 0) break;
            wifi_ap_record_t *aps = malloc(n * sizeof(*aps));
            if (!aps) break;
            esp_wifi_scan_get_ap_records(&n, aps);
            for (int i = 0; i < n; i++) {
                if (aps[i].authmode == WIFI_AUTH_OPEN) {
                    wifi_config_t cfg = {0};
                    memcpy(cfg.sta.ssid, aps[i].ssid, sizeof(cfg.sta.ssid));
                    esp_wifi_set_config(WIFI_IF_STA, &cfg);
                    esp_wifi_connect();
                    ESP_LOGI(TAG, "connecting to open AP '%s'", (char *)aps[i].ssid);
                    break;
                }
            }
            free(aps);
            break;
        }

        default: break;
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *ev = data;
        ESP_LOGI(TAG, "got IP " IPSTR, IP2STR(&ev->ip_info.ip));
        s_connected = true;
        xEventGroupSetBits(s_evg, EV_CONNECTED);

        if (s_sniff_mode == SNIFF_MODE_REALTIME) {
            /* Re-enable promiscuous on top of STA (same-channel sniffing) */
            wifi_promiscuous_filter_t f = {
                .filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL
            };
            esp_wifi_set_promiscuous_filter(&f);
            esp_wifi_set_promiscuous(true);
            ESP_LOGI(TAG, "realtime: STA + promiscuous active");
        }
    }
}

/* ── cycle task ───────────────────────────────────────────────────────── */

static void cycle_task(void *arg)
{
    ESP_LOGI(TAG, "cycle task started (sniff=%lums wifi=%lums)",
             (unsigned long)s_sniff_ms, (unsigned long)s_wifi_ms);

    wifi_country_t country = { .cc = "DE", .schan = 1, .nchan = 13,
                                .max_tx_power = 20,
                                .policy = WIFI_COUNTRY_POLICY_MANUAL };

    for (;;) {
        /* ── Sniff window ── sniffer runs in NULL + promiscuous */
        vTaskDelay(pdMS_TO_TICKS(s_sniff_ms));

        /* ── WiFi window ── */
        s_cycle_active = true;
        sniffer_pause();   /* stops promiscuous + esp_wifi_stop */

        /* Start in STA mode */
        esp_wifi_set_mode(WIFI_MODE_STA);
        esp_wifi_set_country(&country);
        esp_wifi_start();  /* → WIFI_EVENT_STA_START → connect_from_nvs */

        /* Wait for connection (up to wifi_ms) */
        xEventGroupClearBits(s_evg, EV_CONNECTED | EV_DISCONNECTED);
        EventBits_t bits = xEventGroupWaitBits(
            s_evg, EV_CONNECTED, pdTRUE, pdTRUE,
            pdMS_TO_TICKS(s_wifi_ms));

        if (bits & EV_CONNECTED) {
            ESP_LOGD(TAG, "cycle: connected, MQTT flushing");
            /* Give MQTT a short window to flush queued frames */
            uint32_t flush_ms = s_wifi_ms / 3;
            if (flush_ms < 200) flush_ms = 200;
            vTaskDelay(pdMS_TO_TICKS(flush_ms));
            esp_wifi_disconnect();
            vTaskDelay(pdMS_TO_TICKS(200));
        } else {
            ESP_LOGD(TAG, "cycle: no connection in WiFi window");
        }

        /* Stop STA, back to NULL, resume sniffer */
        esp_wifi_stop();
        esp_wifi_set_mode(WIFI_MODE_NULL);
        s_cycle_active = false;
        sniffer_resume();  /* restarts NULL + promiscuous */
    }
}

/* ── public API ───────────────────────────────────────────────────────── */

bool wifi_manager_is_connected(void) { return s_connected; }

esp_err_t wifi_manager_init(void)
{
    uint8_t conn = CONN_MODE_BLE;
    config_get_u8(CONFIG_INDEX_CONN_MODE, &conn);
    s_conn_mode = conn;

    if (conn == CONN_MODE_BLE) {
        ESP_LOGI(TAG, "BLE-only — WiFi manager inactive");
        return ESP_ERR_NOT_SUPPORTED;
    }

    s_sniff_mode = SNIFF_MODE_CYCLE;
    config_get_u8(CONFIG_INDEX_SNIFF_MODE, &s_sniff_mode);
    s_sniff_ms = SNIFF_MS_DEFAULT;
    s_wifi_ms  = WIFI_MS_DEFAULT;
    config_get_u32(CONFIG_INDEX_SNIFF_MS, &s_sniff_ms);
    config_get_u32(CONFIG_INDEX_WIFI_MS,  &s_wifi_ms);
    if (s_sniff_ms < 500) s_sniff_ms = SNIFF_MS_DEFAULT;
    if (s_wifi_ms  < 200) s_wifi_ms  = WIFI_MS_DEFAULT;

    ESP_LOGI(TAG, "mode=%d sniff_mode=%d sniff=%lums wifi=%lums",
             conn, s_sniff_mode,
             (unsigned long)s_sniff_ms, (unsigned long)s_wifi_ms);

    s_evg = xEventGroupCreate();

    /* esp_netif_init is idempotent in newer IDF; guard against double-call */
    esp_err_t ni = esp_netif_init();
    if (ni != ESP_OK && ni != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_netif_init: %s", esp_err_to_name(ni));
        return ni;
    }
    s_netif = esp_netif_create_default_wifi_sta();
    apply_ip_config();

    ESP_ERROR_CHECK(esp_event_handler_register(
        WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(
        IP_EVENT, IP_EVENT_STA_GOT_IP, on_wifi, NULL));

    ESP_LOGI(TAG, "init complete (start pending sniffer_autostart)");
    return ESP_OK;
}

esp_err_t wifi_manager_start(void)
{
    if (s_conn_mode == CONN_MODE_BLE) return ESP_ERR_NOT_SUPPORTED;

    wifi_country_t country = { .cc = "DE", .schan = 1, .nchan = 13,
                                .max_tx_power = 20,
                                .policy = WIFI_COUNTRY_POLICY_MANUAL };

    if (s_sniff_mode == SNIFF_MODE_REALTIME) {
        /* Pause sniffer, switch to STA, connect.
         * On IP_EVENT_STA_GOT_IP the handler re-enables promiscuous. */
        sniffer_pause();
        esp_wifi_set_mode(WIFI_MODE_STA);
        esp_wifi_set_country(&country);
        esp_wifi_start();   /* → WIFI_EVENT_STA_START → connect_from_nvs */
        ESP_LOGI(TAG, "realtime: STA started");
    } else {
        /* Cycle / individual: let the task manage radio time-sharing.
         * Sniffer keeps running in NULL + promiscuous until the task pauses it. */
        xTaskCreate(cycle_task, "wifi_cycle", 4096, NULL, 4, &s_cycle_task);
        ESP_LOGI(TAG, "cycle task started");
    }
    return ESP_OK;
}
