#!/usr/bin/env python3
"""
Liest eine .pcap-Datei (DLT_IEEE802_11, von der V2Xtend-Bridge geschrieben)
und gibt jedes ITS-G5-Paket als JSON-Zeile aus.

Nutzt tshark im Hintergrund als Decoder (statt einer selbstgebauten Parser-
Logik), da tshark die vollständige IEEE1609.2/ETSI-COER-Struktur inkl.
signierter Pakete korrekt beherrscht. Extrahiert die relevanten Felder
per rekursiver Schlüsselsuche, damit es unabhängig vom Nachrichtentyp
(CAM/DENM/SPATEM/...) funktioniert, da GeoNetworking-Positionsdaten bei
jedem Pakettyp im selben Feldnamen stecken.

Nutzung:
    python3 pcap_to_json.py aufnahme.pcap
    python3 pcap_to_json.py aufnahme.pcap --pretty
    python3 pcap_to_json.py aufnahme.pcap > ausgabe.jsonl
"""
import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone

MSG_TYPES = {
    "1": "DENM", "2": "CAM", "4": "SPATEM", "5": "MAPEM",
    "6": "IVIM", "7": "EV-RSR", "8": "TISTPG", "9": "SREM",
    "10": "SSEM", "11": "EVCSN", "12": "SAEM", "13": "RTCMEM",
    "14": "CPM", "15": "IMZM", "16": "VAM", "17": "DSM",
}


def find_first(d, key):
    """Rekursive Tiefensuche nach einem Schlüssel, egal wie tief verschachtelt."""
    if isinstance(d, dict):
        if key in d:
            return d[key]
        for v in d.values():
            result = find_first(v, key)
            if result is not None:
                return result
    elif isinstance(d, list):
        for item in d:
            result = find_first(item, key)
            if result is not None:
                return result
    return None


def extract(layers: dict) -> dict:
    frame = layers.get("frame", {})
    n = int(frame.get("frame.number", 0))
    length = int(frame.get("frame.len", 0))
    time_epoch = float(frame.get("frame.time_epoch", 0))

    record = {
        "n": n,
        "timestamp": datetime.fromtimestamp(time_epoch, tz=timezone.utc).isoformat(),
        "len": length,
        "protocols": frame.get("frame.protocols", ""),
    }

    msg_id = find_first(layers, "its.messageId")
    record["msg_type"] = MSG_TYPES.get(msg_id, f"UNKNOWN({msg_id})") if msg_id else "NON-ITS"

    station_id = find_first(layers, "its.stationId")
    if station_id is not None:
        record["station_id"] = station_id

    mac = find_first(layers, "geonw.src_pos.addr.mid")
    if mac:
        record["gn_addr"] = mac

    station_type = find_first(layers, "its.stationType")
    if station_type is not None:
        record["station_type"] = int(station_type)

    record["secured"] = "geonw.sec" in json.dumps(layers.get("gnw", {}))

    lat_raw = find_first(layers, "geonw.src_pos.lat")
    lon_raw = find_first(layers, "geonw.src_pos.long")
    if lat_raw is not None and lon_raw is not None:
        lat = int(lat_raw) / 1e7
        lon = int(lon_raw) / 1e7
        if -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0:
            record["lat"] = lat
            record["lon"] = lon

    speed_raw = find_first(layers, "geonw.src_pos.speed")
    if speed_raw is not None:
        record["speed_mps"] = int(speed_raw) / 100.0

    hdg_raw = find_first(layers, "geonw.src_pos.hdg")
    if hdg_raw is not None:
        record["heading_deg"] = int(hdg_raw) / 10.0

    return record


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pcap_file")
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args()

    result = subprocess.run(
        ["tshark", "-r", args.pcap_file, "-T", "json"],
        capture_output=True, text=True, check=True,
    )
    packets = json.loads(result.stdout)

    for pkt in packets:
        layers = pkt.get("_source", {}).get("layers", {})
        record = extract(layers)
        if args.pretty:
            print(json.dumps(record, indent=2, ensure_ascii=False))
        else:
            print(json.dumps(record, ensure_ascii=False))


if __name__ == "__main__":
    main()
