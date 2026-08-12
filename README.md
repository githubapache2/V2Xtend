**V2Xtend** is an open-source receiver and live map for **ITS-G5 / V2X** traffic — the 5.9 GHz IEEE 802.11p messages cars and roadside infrastructure send to coordinate. It's a fork of [V2X2MAP](https://github.com/pit711/V2X2MAP), see [Acknowledgements](#acknowledgements) for what's new here.

Plug a $20 ESP32-C5 dev board into your phone, drive somewhere with modern infrastructure, watch the CAMs, DENMs and SPATEMs roll in.

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

<table>
<tr>
<td><img src="docs/screenshot_01_main.png"         alt="Live map"    width="160"/></td>
<td><img src="docs/screenshot_02_settings_top.png" alt="Settings 1"  width="160"/></td>
<td><img src="docs/screenshot_03_settings_mid.png" alt="Settings 2"  width="160"/></td>
<td><img src="docs/screenshot_04_settings_bot.png" alt="Settings 3"  width="160"/></td>
</tr>
</table>

## Acknowledgements

This project, **V2Xtend**, is a fork of [**pit711/V2X2MAP**](https://github.com/pit711/V2X2MAP) — the Android app, the BLE stack, the Python bridge and the Windows installer all come from that project; see [What's different from V2X2MAP](#whats-different-from-v2x2map) below for what this fork adds on top.

V2X2MAP itself is, in turn, a fork of the firmware from the team behind [**opentrafficmap/its-g5-receiver-firmware**](https://codeberg.org/opentrafficmap/its-g5-receiver-firmware) on Codeberg — without their foundational work neither project would exist. V2X2MAP adapted it for the Waveshare ESP32-C5-WIFI6-KIT devboard and extended it with BLE streaming, the Android app, and the Windows installer.

---

## What's different from V2X2MAP

This fork keeps everything from upstream and adds:

- **BLE disconnect-tolerant cache with DENM priority** — packets received while the phone is disconnected from the C5 over BLE used to be dropped silently. They're now buffered (24 slots) and delivered the moment the connection comes back, with DENM (hazard) messages protected from eviction ahead of routine CAM traffic. Verified with zero packet loss across disconnects of several minutes, and correct DENM-priority behavior under a deliberate buffer-overflow test.
- **Fixed decoding of signed (secured) packets** — practically every real CAM/DENM on the air is cryptographically signed (IEEE 1609.2). Both the Android app's live decoder and the Python bridge used to misread these as garbage (message type `UNKNOWN`, impossible GPS coordinates/speed/heading). Fixed in both codebases, verified against 200+ real, ground-truthed field messages.
- **Fixed a latent SPI pin conflict** — the firmware's W5500 Ethernet support (unused by this fork, which relies on USB + BLE) shared GPIO pins with the SD-card interface.
- Various fixes needed to get the firmware building at all against current ESP-IDF (v5.5.4) headers.

---

## What it is

Modern cars and roadside units (RSUs) broadcast standardised safety messages on the dedicated 5.9 GHz V2X band:

- **CAM** — Cooperative Awareness: "I'm here, going X km/h"
- **DENM** — Decentralised Environmental Notification: "hazard ahead!"
- **SPATEM** — Signal Phase + Timing: traffic-light countdown
- **MAPEM** — intersection geometry

V2Xtend captures these in promiscuous mode, decodes the GeoNetworking headers locally, and plots each message as a colour-coded marker on an OSM map. No cloud round-trip required — everything runs on the phone.

---

## Hardware

One **Waveshare ESP32-C5-WIFI6-KIT** dev board and any Android phone with USB-OTG or Bluetooth LE.

The board supports 5.9 GHz IEEE 802.11p out of the box; the firmware drives it as a sniffer and forwards captured frames to your phone.

<img src="docs/hardware.jpg" alt="Waveshare ESP32-C5-WIFI6-KIT dev board" width="340"/>

- **Amazon with external Antenna:** [Waveshare ESP32-C5-WROOM-1 dev board](https://amzn.to/4uDpwNa) *
- **Amazon without external Antenna:** [Waveshare ESP32-C5-WROOM-1 dev board](https://amzn.to/43qIJ9h) *
- **AliExpress:** [Waveshare Official Store](https://s.click.aliexpress.com/e/_c3lTQYn5) *



---

## Features

| Feature | Description |
|---|---|
| **Live map** | 5 switchable tile layers: Standard, Dark, Satellite, ÖPNV, Humanitarian |
| **Grouped frame log** | One row per station (MAC); expandable to last 20 frames; shows type icon, speed, distance, 🔒/🔓 secured |
| **CAM markers** | One marker per vehicle, updated in-place with baked-in heading + speed label |
| **Compass mode** | Bearing-up FAB rotates the map to keep your heading at the top |
| **Own GPS track** | Optional blue polyline traces your route |
| **Auto-follow** | Map pans with you; zoom stays exactly as you set it |
| **Geiger-counter mode** | Audio + haptic tick on every frame, distinct beep + buzz on DENM hazard |
| **BLE + USB auto-reconnect** | Exponential-backoff reconnect on cable pull or BT drop — no user interaction |
| **Offline maps** | OSMdroid tile cache up to 600 MB |
| **PCAP recording** | One tap records to standard `.pcap`; open directly in Wireshark (link type 105 = IEEE 802.11) |
| **Multi-broker MQTT** | One input field per broker, add/remove with + / 🗑; per-type message filter |
| **Full i18n** | English default, German for German-locale devices — all UI, errors and notifications |

---

## Architecture

```
+---------------+     5.9 GHz 802.11p      +------------+
|  Vehicles &   |   CAM / DENM / SPATEM    |  ESP32-C5  |
|  RSUs         |  ----------------------> |  sniffer   |
+---------------+                          +-----+------+
                                                 |
                                  USB-Serial-JTAG | BLE-GATT
                                                 v
                                        +--------+--------+
                                        | Android app /   |
                                        | Python bridge   |
                                        +--------+--------+
                                                 |
                                                 | optional
                                                 v
                                          MQTT (cits1.opentrafficmap.org
                                                 or your own)
```

---

## Install

### Pre-built firmware + app (no installer yet)

[**Releases**](../../releases/latest) has a firmware zip (bootloader + partition table + app binary + `flash_args`, flash with `esptool.py write_flash @flash_args`) and a debug-signed Android APK. No one-click Windows installer yet for this fork — use these pre-built files with `esptool`/`adb`, or the [manual build steps](#manual-build-from-source) below. (Upstream's own installer is at [pit711/V2X2MAP releases](https://github.com/pit711/V2X2MAP/releases/latest) if you don't specifically need this fork's fixes.)

### Windows — one-click installer (not yet available for this fork)

1. Download **ITS-G5 Receiver Setup** from the [Releases page](../../releases/latest)
2. Connect the ESP32-C5 via USB
3. Run the EXE and follow three steps:

<table>
<tr>
<td align="center"><strong>Step 1 — Select COM port</strong></td>
<td align="center"><strong>Step 2 — Flash firmware</strong></td>
<td align="center"><strong>Step 3 — Set Node-ID</strong></td>
</tr>
<tr>
<td><img src="docs/installer-1-port.png"   alt="Installer step 1: select COM port"   width="260"/></td>
<td><img src="docs/installer-2-flash.jpg"  alt="Installer step 2: flash firmware"    width="260"/></td>
<td><img src="docs/installer-3-nodeid.png" alt="Installer step 3: set Node-ID"       width="260"/></td>
</tr>
<tr>
<td>The installer detects the board automatically. Pick the right port and click <em>Weiter</em>.</td>
<td>The installer writes bootloader, partition table and application to the C5. Takes 30–60 seconds.</td>
<td>The installer reads the MAC from the chip and pre-fills the Node-ID. Hit <em>Fertig – Bridge starten</em>.</td>
</tr>
</table>

---

### Manual build from source

<details>
<summary>Firmware (ESP-IDF)</summary>

```powershell
# once per shell — activate ESP-IDF toolchain
. .\esp-idf\export.ps1

cd V2Xtend\firmware
idf.py build
idf.py -p COMx -b 921600 flash
```

</details>

<details>
<summary>Android app</summary>

```powershell
cd V2Xtend
.\gradlew.bat :androidApp:assembleDebug
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Or open `V2Xtend/` (repo root — not `android/`) in Android Studio. Min SDK 24 (Android 7.0).

The Gradle root lives at the repo root since the KMP/`shared` module was introduced.
`android/gradlew` remains as a compatibility shim that forwards to the root wrapper.

</details>

<details>
<summary>Python bridge + dashboard</summary>

```powershell
cd V2Xtend\bridge
python its_g5_bridge.py --port COMx --node-id <mac-without-colons>
```

Dashboard at `http://127.0.0.1:8080`. Default MQTT broker: `mqtts://cits1.opentrafficmap.org:8883`.

</details>

---

## Legal

Receiving and forwarding ITS-G5 radio data may be subject to national telecommunications law and data-protection law. The Android app shows a disclaimer on first launch. Use at your own risk.

Code is published under the **MIT License** — see [`LICENSE`](LICENSE).

\* affiliate link (no extra cost for you)
