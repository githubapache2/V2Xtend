# Session-Notizen 2026-08-06: Firmware-Build-Fix, Flash & Feldtest-Anleitung

Kontext: `CLAUDE.md` beschrieb als offenen Punkt, dass die Firmware noch auf
USB-Stream-Transport umgestellt werden müsse. Bei genauerer Prüfung stellte
sich heraus, dass der USB-Stream-Code bereits vollständig implementiert war
— das eigentliche Problem war, dass die Firmware in der vorliegenden Form
gar nicht kompilierte. Dieses Dokument hält fest, was geändert wurde, warum,
und wie ein Feldtest durchzuführen ist.

## 1. Repository lokal eingerichtet

Das Arbeitsverzeichnis auf dem Mac enthielt bisher nur `CLAUDE.md` und
`.specstory/` — kein tatsächliches Git-Repo. Geklont von
`https://github.com/pit711/V2X2MAP.git` direkt in dieses Verzeichnis
(`.git`, `android/`, `bridge/`, `docs/`, `firmware/` etc. ergänzt,
`CLAUDE.md`/`.specstory/` unangetastet gelassen).

`CLAUDE.md` wurde zu `.gitignore` hinzugefügt, da die Datei das
SSH-Klartext-Passwort für den RPi enthält.

## 2. ESP-IDF installiert / aktiviert

ESP-IDF **v5.5.4** lag bereits unter `~/esp/esp-idf` (Espressif-Fork,
`esp32c5`-Support ab v5.3 vorhanden). Aktivierung pro Shell-Sitzung:

```bash
source ~/esp/esp-idf/export.sh
```

## 3. Build-Fehler behoben

Der erste `idf.py build`-Lauf schlug an mehreren Stellen fehl. Root Cause:
Der Firmware-Code ruft mehrere Funktionen/Makros auf, die in **keiner**
Version von ESP-IDF existieren (per Volltextsuche im kompletten installierten
ESP-IDF-Baum verifiziert) — vermutlich Code, der nie tatsächlich gegen echte
ESP-IDF-Header kompiliert wurde.

| Datei | Problem | Fix |
|---|---|---|
| `firmware/main/ethernet.c` | `esp_netif_get_mtu()` / `esp_netif_set_mtu()` existieren nicht in `esp_netif` | `update_mtu()` zu No-Op gemacht (Kommentar: MTU lässt sich in esp_netif nur bei Netif-Erstellung setzen, nicht nachträglich) |
| `firmware/main/ethernet.c` | `case IP_EVENT_NETIF_UP:` — kein gültiger `ip_event_t`-Wert, nie ausgelöst | kompletten `case`-Block entfernt (war ohnehin totes Ziel, da das Event nie existiert/feuert) |
| `firmware/main/ethernet.c` | `esp_netif_inherent_config_t` hat kein Feld `mtu` | Zeile `esp_netif_config.mtu = MAX_MTU;` entfernt |
| `firmware/main/led.c` (3×) | `esp_timer_stop_blocking(handle, timeout)` existiert nicht — nur `esp_timer_stop(handle)` | auf `esp_timer_stop(handle)` umgestellt |
| `firmware/main/mqtt.c` (1×) | dito | dito |
| `firmware/main/usb_stream.h` | `bool usb_cfg_is_scanning(void);` ohne `#include <stdbool.h>` → Typkonflikt mit der `.c`-Definition | `#include <stdbool.h>` ergänzt |
| `firmware/sdkconfig` | `CONFIG_SNIFFER_PCAP_DESTINATION_JTAG=y` bindet Espressifs Beispielcode `cmd_pcap.c` (JTAG-Zweig) ein, dessen `esp_apptrace_write()`/`esp_apptrace_flush()`/`esp_apptrace_host_is_connected()`-Aufrufe nicht zur aktuellen `app_trace`-API-Signatur passen (fehlendes `esp_apptrace_dest_t dest`-Argument) | auf `CONFIG_SNIFFER_PCAP_DESTINATION_MEMORY=y` umgestellt — wird ohnehin nicht gebraucht (keine SD-Karte verbaut, kein JTAG-Pcap-Capture geplant; die eigentliche Pcap-Aufzeichnung läuft über die RPi-Bridge (`--pcap-out`)) |

**Nicht angetastet**, weil unnötig für den USB-Pfad, aber der Vollständigkeit
halber notiert: Der komplette Ethernet/W5500-Code (`ethernet.c`) kompiliert
und läuft unverändert mit — er ist nicht per Kconfig abschaltbar (kein
`#if CONFIG_ETH_ENABLED`-Guard vorhanden), bleibt aber ohne angeschlossenes
W5500-Modul harmlos inaktiv.

### Ein Stolperstein während der Arbeit

`idf.py set-target esp32c5` wurde versehentlich auf einer bereits
funktionierenden `sdkconfig` ausgeführt und hat sie dabei von 3292 auf 420
Zeilen zusammengestrichen (u.a. die komplette Bluetooth/NimBLE-Konfiguration
ging verloren, was in der Folge zu zusätzlichen Fehlern führte). Da
`sdkconfig` in Git getrackt ist, ließ sich das per `git checkout --
firmware/sdkconfig` folgenlos rückgängig machen. **Merke:** `set-target`
nicht erneut auf eine bereits konfigurierte `sdkconfig` loslassen, wenn das
Target schon stimmt (`CONFIG_IDF_TARGET` prüfen, bevor man's ausführt).

## 4. Gebaut & geflasht

```bash
source ~/esp/esp-idf/export.sh
cd firmware
idf.py build
idf.py -p /dev/cu.usbmodem5B901648091 -b 921600 flash
```

Build erfolgreich (`its-g5-receiver-firmware.bin`, App-Version
`v0.3.0-1-g41aa703-dirty`, 11 % Flash-Partition frei). Geflasht über den
UART/CH343-Port (Auto-Download-Schaltung, kein manuelles Boot-Taster-Drücken
nötig).

## 5. Verifikation über die RPi-Bridge

```bash
ssh miro@its-g5-bridge.local   # oder: sshpass -p '<Passwort>' ssh ...
cd ~/V2X2MAP/bridge && source ~/venv/bin/activate
python3 its_g5_bridge.py --node-id V2X2MAP:3844beaa0c04 --no-mqtt \
    --dashboard-port 0 --reset-on-start --verbose
```

Ergebnis:
```
frames=0  rate=0/min  bytes=1929  uptime=5s
frames=1  rate=1/min  bytes=1959  uptime=10s
```

`frames=1` bestätigt: Der einmalige Boot-Test-Frame
(`usb_stream_send_test_frame()` in `main.c`) wurde korrekt als gültiger
`"ITS5"`-Frame erkannt und geparst. Ohne `--reset-on-start` bleibt
`frames=0`, weil der Test-Frame schon beim vorherigen Boot (ausgelöst durch
den Flash-Vorgang) verschickt wurde, bevor die Bridge zuhörte — das ist kein
Fehler, sondern reines Timing.

Danach bleibt `frames` konstant bei 1, solange kein echter 802.11p-Verkehr
in Empfangsreichweite ist — dafür braucht es einen Feldtest (siehe unten).

## 6. Rechtlicher Disclaimer — bestätigt er sich automatisch neu?

**Kurz: Nein, er muss nur einmal bestätigt werden und bleibt es dauerhaft.**

Details: `its_g5_bridge.py` zeigt den Disclaimer nur, wenn
`cfg.get("legal_accepted")` nicht bereits `true` ist. Die Zustimmung wird in
einer einfachen JSON-Datei auf der RPi-SD-Karte gespeichert:

```
~/.config/v2x2map/v2x2map.cfg
{
  "legal_accepted": true
}
```

Aktuell (2026-08-06) steht dort bereits `true` — vermutlich aus einem
früheren Testlauf.

- **Neustart des C5**: hat mit dem Disclaimer nichts zu tun. Der Disclaimer
  ist reine Software auf der RPi-Seite (`its_g5_bridge.py`), nicht Teil der
  Firmware. Der C5 kennt keinen Disclaimer-Zustand.
- **Neustart des RPi**: Die Konfigurationsdatei liegt auf der SD-Karte und
  übersteht Reboots problemlos — der Disclaimer erscheint **nicht** erneut,
  solange diese Datei nicht gelöscht wird oder ein neues/anderes
  Benutzerkonto verwendet wird.
- **Einzige Fälle, in denen er wieder auftaucht**: Datei manuell gelöscht,
  frisches SD-Karten-Image, oder ein anderer Linux-User startet die Bridge
  (andere `$HOME`).

⚠️ **Wichtig für unbeaufsichtigten/automatisierten Betrieb**: Der Disclaimer
nutzt einen blockierenden `input()`-Aufruf, falls er doch angezeigt wird.
Aktuell gibt es **keinen systemd-Service, keinen Cronjob, keinen Autostart**
für die Bridge auf dem RPi (geprüft: `systemctl list-units`, `crontab -l` —
beide leer). Die Bridge wird ausschließlich manuell per SSH gestartet. Falls
später ein Autostart beim Booten eingerichtet werden soll: entweder vorher
sicherstellen, dass `legal_accepted: true` in der Config steht (aktuell der
Fall), oder den Disclaimer-Check im Skript für den Service-Kontext
umgehen — sonst hängt ein systemd-Dienst ohne TTY am `input()` fest, falls
die Config aus irgendeinem Grund fehlt.

## 7. Feldtest-Anleitung

### 7.1 Vorbereitung zuhause (unbedingt vorher, nicht erst unterwegs)

**Netzwerk für unterwegs einrichten.** Der RPi ist aktuell nur mit dem
Heimnetz `Sunrise` verbunden (`10.10.10.x`, per NetworkManager), hat
**keinen eigenen Hotspot-Modus**. Im Auto gibt es dieses Netz nicht — Handy
und RPi müssen also ein gemeinsames mobiles Netz nutzen. Zwei Optionen:

- **Handy-Hotspot** (empfohlen, einfachster Weg): Auf dem Telefon einen
  WLAN-Hotspot einrichten, **auf 2.4 GHz erzwingen** falls die Option
  existiert (RPi Zero 2W kann kein 5 GHz). Dann auf dem RPi per SSH
  (solange noch im Heimnetz oder per Kabel erreichbar) die Zugangsdaten
  hinterlegen:
  ```bash
  ssh miro@its-g5-bridge.local
  sudo nmcli device wifi connect "<Hotspot-SSID>" password "<Hotspot-Passwort>"
  ```
  NetworkManager merkt sich das Netz zusätzlich zu `Sunrise` und verbindet
  sich künftig automatisch, sobald es in Reichweite ist (Priorität ggf. mit
  `nmcli connection modify <Name> connection.autoconnect-priority <N>`
  anpassen, falls beide Netze gleichzeitig sichtbar sind).
- **Separater mobiler Router/MiFi**: gleiches Prinzip, RPi und Handy beide
  mit dessen SSID verbinden.

**Danach unbedingt einen Trockentest zuhause machen**, bevor es losgeht:
Handy-Hotspot aktivieren, RPi verbindet sich automatisch (per `ping
its-g5-bridge.local` oder über die Hotspot-Geräteliste die IP prüfen),
Dashboard im Handy-Browser aufrufen (`http://<rpi-ip>:8080`), Bridge einmal
manuell starten und prüfen, dass alles erreichbar ist — nicht erst im Auto
zum ersten Mal ausprobieren.

**Stromversorgung im Auto klären**: RPi Zero 2W braucht 5V/USB (Kfz-
USB-Ladeadapter oder Powerbank genügt), der C5 hängt am RPi und bezieht
seinen Strom von dort mit.

### 7.2 Ablauf während der Fahrt

1. C5 nur über den **nativen USB-Port** mit dem RPi verbinden (kein
   Mac-Kabel gleichzeitig).
2. RPi mit Strom versorgen, warten bis es im Hotspot-Netz erscheint.
3. Per SSH (über den Hotspot) verbinden und die Bridge starten, z.B. mit
   Aufzeichnung für spätere Analyse:
   ```bash
   cd ~/V2X2MAP/bridge && source ~/venv/bin/activate
   python3 its_g5_bridge.py --node-id V2X2MAP:3844beaa0c04 \
       --dashboard-port 8080 --reset-on-start \
       --pcap-out ~/v2x2map/recordings/feldtest_$(date +%Y%m%d_%H%M).pcap \
       --verbose
   ```
   `--no-mqtt` weglassen, falls auch live an den opentrafficmap-Broker
   publiziert werden soll; sonst der Vollständigkeit halber mitgeben, um
   nur lokal zu testen.
4. Auf dem Handy `http://<rpi-ip>:8080` öffnen — dort sollte `frames`
   ansteigen, sobald der C5 in Reichweite von echten ITS-G5-Sendern ist
   (moderne Ampeln mit SPATEM/MAPEM, Fahrzeuge/RSUs mit CAM/DENM).
5. **Sicherheit**: Nicht selbst als Fahrer bedienen — Beifahrer soll Handy/
   RPi im Blick behalten, C5/RPi vorher fest verstauen (z.B. Dashcam-
   Halterung, Ablagefach), keine Bedienung während der Fahrt.
6. **Rechtliches beachten**: Der Disclaimer gilt weiterhin inhaltlich (§ 89
   TKG) — nur für Forschungs-/Entwicklungszwecke, nur mit der nötigen
   Berechtigung, siehe `CLAUDE.md` Abschnitt "Rechtlicher Hinweis".

### 7.3 Nach der Fahrt

- Aufgezeichnete `.pcap`-Datei vom RPi abholen:
  ```bash
  scp miro@its-g5-bridge.local:~/v2x2map/recordings/feldtest_*.pcap .
  ```
- In Wireshark öffnen (Link-Type 105 = IEEE 802.11) zur Detailanalyse der
  empfangenen CAM/DENM/SPATEM/MAPEM-Nachrichten.
- Bridge-Log (`frames=N`, `rate=N/min`) gibt schon während der Fahrt ein
  grobes Bild, wie viel echter Verkehr empfangen wurde.

## 8. Offene Punkte / mögliche nächste Schritte

- Kein Autostart der Bridge beim RPi-Boot eingerichtet — bei Bedarf
  systemd-Service anlegen (siehe Warnhinweis zu `input()` oben).
- RPi läuft aktuell mit dem alten Ethernet/W5500-Code im Firmware-Build mit
  (harmlos, aber ungenutzter Codepfad) — könnte bei Gelegenheit per
  Kconfig-Guard sauber abschaltbar gemacht werden, falls gewünscht.
- Kein zweites Hotspot-Netz auf dem RPi hinterlegt, bis dieser Schritt
  manuell nachgeholt wird (siehe 7.1).
