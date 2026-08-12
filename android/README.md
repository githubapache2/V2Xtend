# ITS-G5 Receiver — Android App

Reads the frame stream from the ESP32-C5 (same code as on the device),
shows frames live on an OpenStreetMap map and in the log, and can
optionally forward them to `mqtts://cits1.opentrafficmap.org`.

Wire protocol as in `bridge/its_g5_bridge.py`:

    magic[4]  = "ITS5"
    sec       u32 LE
    usec      u32 LE
    len       u16 LE
    payload   <len> bytes  (raw 802.11 MAC frame)

## Open in Android Studio

1. **File → Open** → the **repo root** (`V2Xtend/`), not this `android/` folder.
   Gradle root lives at the repo root (KMP modules `:shared` + `:androidApp`).
2. On the first sync, Gradle pulls the wrapper JAR and all dependencies (USB-Serial, OSMDroid, Paho).
3. Min SDK is 24 (Android 7), target/compile 34 (Android 14), Kotlin 1.9, AGP 8.2.

Build from CLI:

```bash
cd ..   # repo root
./gradlew :androidApp:assembleDebug
# or from this directory via the shim:
./gradlew assembleDebug
```

See also `BUILD.md` in this folder.

## Install on the device

- Enable **USB debugging** on the phone (developer options).
- Connect a USB-C cable from PC to phone.
- In Android Studio, **Run** on your phone.

## Connecting the ESP32-C5

- Use a USB-OTG adapter or a USB-C-to-USB-C cable between phone and ESP32-C5 devboard.
- On first attach, Android asks for USB permission — confirm.
- If the app does not start automatically, open it once manually and tap **Connect**.

`usb_device_filter.xml` matches the VID of the Espressif USB-Serial-JTAG (0x303A) as well as CP210x, CH340/CH9102 and FTDI as fallbacks. If your board has a different VID, add it in `app/src/main/res/xml/usb_device_filter.xml`.

## Usage

- **Connect** opens the serial port and starts the reader thread.
- **MQTT switch** connects to `cits1.opentrafficmap.org:8883` (TLS, public-CA validated) and publishes every received frame as raw payload to `its/<NodeID>/packet`. Status topic with last-will / will-retain.
- **Map**: every ITS-G5 frame (EtherType 0x8947) whose GeoNetworking source position can be parsed gets a marker.
- **Log**: newest on top. EtherType colouring: 🟢 ITS-G5, 🔘 other WLAN, 🟠 not parsable.

## Modules

- `Frame.kt` — data class for a decoded frame
- `FrameReader.kt` — resync parser (magic + header + payload), equivalent to the Python bridge
- `ItsG5Decoder.kt` — looks for the LLC/SNAP EtherType, extracts the GeoNetworking source position
- `UsbSerialController.kt` — wrapper around `usb-serial-for-android` (CDC-ACM)
- `MqttBridge.kt` — Paho async client with TLS, will-retain
- `FrameLogAdapter.kt` — RecyclerView adapter
- `MainActivity.kt` — single Activity, wires everything together

## Known limitations

- The ITS-G5 position decoder assumes a standard GeoNetworking common header.
  If real-world frames are laid out differently, `ItsG5Decoder.kt` needs to be adjusted.
- OSMDroid pauses the map on background/tab switch. MQTT stays active (service-free,
  Paho runs on its own thread). For real background operation a `ForegroundService`
  would be needed — deliberately left out, because the device hangs off the USB
  socket and the screen is on while driving anyway.
- The test frame (`DEADBEEF…`) from the ESP firmware comes through at boot — don't
  be alarmed; it can be removed in `main/main.c` in the repo.
