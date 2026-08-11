# V2Xtend — Designsystem-Handoff (Material 3, XML)

Kopiere die Dateien aus `handoff/res/` in `android/app/src/main/res/`. Sie ersetzen feste Farben
durch Theme-Rollen; die bestehenden Drawables und Layouts bleiben.

## 1. Farben und Theme

- `values/colors_v2x.xml` — Rohpalette (dark + light) und die C-ITS-Typfarben.
- `values/themes.xml` / `values-night/themes.xml` — Material-3-Rollen. Basis
  `Theme.Material3.DayNight.NoActionBar`.

Im Layout **nie** `@color/...` direkt verwenden, sondern die Rolle:

| Zweck | Attribut |
| --- | --- |
| Karten-/Sheet-Fläche | `?attr/colorSurface` |
| erhöhte Fläche (Chips, FAB-Sekundär) | `?attr/colorSurfaceVariant` |
| Akzent (FAB, Auswahl, Schalter) | `?attr/colorPrimary` |
| Live/Verbunden/Grünphase | `?attr/colorTertiary` |
| Warnung/Reconnect | `@color/v2x_warn` |
| Fehler/Rotphase | `?attr/colorError` |
| Primärtext | `?attr/colorOnSurface` |
| Sekundärtext | `?attr/colorOnSurfaceVariant` |

## 2. Formen und Abstände

- `values/shapes_v2x.xml` — `ShapeAppearance.V2X.Small` (6dp, Chips/Badges),
  `.Medium` (10dp, Karten/Buttons/FAB), `.Large` (16dp, BottomSheets).
- `values/dimens_v2x.xml` — 4-dp-Raster (`space_4` … `space_32`), Peek-/Rastpunkt-Höhen,
  Trefferflächen. Kein Layout setzt eigene dp-Werte, alles referenziert diese.

Trefferflächen: alles Bedienbare ≥ `@dimen/touch_min` (44dp). Für Halterungsnutzung gilt das
auch für die Meldungsleiste und den Quelle-Umschalter.

## 3. Typografie

`values/type_v2x.xml` definiert `TextAppearance.V2X.*` auf Basis von Material 3:

- `Display` / `Title` — Outfit 600–800 (Sheet-Titel, Ampelphase)
- `Body` / `Label` — DM Sans 400–500 (alle UI-Texte)
- `Mono` — JetBrains Mono (Zeitstempel, Hex, Station-IDs, Statuscode, Zähler)

Regel: **alle Zahlen, die sich live ändern, in Mono** — sonst springt die Breite.

## 4. Kartenscreen (Richtung 3a)

```
CoordinatorLayout
├─ MapView                          (fill, keine Toolbar darüber)
├─ LinearLayout  @id/alertBar        (top|start, 44dp, Radius full)  ← Chips pro C-ITS-Typ + Anzahl
├─ LinearLayout  @id/alertDetails    (unter der Leiste, sichtbar nur bei expanded)
├─ LinearLayout  @id/fabColumn       (bottom|end, layout_anchor=@id/sheet, anchorGravity=top|end)
└─ LinearLayout  @id/sheet           (BottomSheetBehavior, Radius 16dp oben)
```

- `fabColumn` per `app:layout_anchor="@id/sheet"` + `app:layout_anchorGravity="top|end"` an das
  Sheet hängen, **nicht** an den Bildschirmrand — sonst verschwindet sie beim Aufziehen dahinter.
  Im Zustand `STATE_EXPANDED` per `BottomSheetCallback` ausblenden (alpha 0, `isClickable=false`).
- Die Meldungsleiste liegt über dem Sheet (`translationZ` höher als das Sheet), damit die
  aufgeklappten Details in jedem Rastpunkt lesbar bleiben.

### BottomSheet mit drei Rastpunkten

```kotlin
BottomSheetBehavior.from(sheet).apply {
    isFitToContents = false
    peekHeight = resources.getDimensionPixelSize(R.dimen.sheet_peek)   // 112dp
    halfExpandedRatio = 0.52f                                         // ≈ 380dp auf 740dp
    expandedOffset = resources.getDimensionPixelSize(R.dimen.sheet_expanded_offset)
    state = BottomSheetBehavior.STATE_COLLAPSED
}
```

Der Peek-Streifen ist der oberste Block **innerhalb** des Sheets und damit in jedem Rastpunkt
sichtbar. Er hat drei Zellen mit festen Gewichten (0.44 / 0.30 / 0.26), damit beim Zustandswechsel
nichts springt.

### Ampelzelle — drei Zustände, eine Grundfläche

| Zustand | Phasentext | Slot darunter | Farbe |
| --- | --- | --- | --- |
| Phase + Restzeit | GRÜN / ROT | `12 s · 180 m` (Mono) | `colorTertiary` / `colorError` |
| Phase ohne Restzeit | GRÜN | `ohne Restzeit` (Label, gedeckt) | `colorTertiary` |
| kein Empfang | `—` | `keine Ampel` | `colorOnSurfaceVariant` |

Der Slot darunter wird **nie `GONE`** — nur Text, Stil und Farbe wechseln. Sonst springt die
Zeilenhöhe und der Wechsel liest sich als Fehler. Übergang: 220 ms `ArgbEvaluator` auf die Farbe,
Geometrie unverändert.

### C-ITS-Meldungen

Ein Eintrag je Typ, nicht je Nachricht. Wiederholung desselben Typs erhöht den Zähler und setzt
die Standzeit auf 30 s zurück. Nach 30 s ohne Nachschub verfällt der Eintrag.

```kotlin
data class CitsAlert(val type: CitsType, var text: String, var count: Int, var lastSeen: Long)

fun onMessage(type: CitsType, text: String) {
    val a = alerts.firstOrNull { it.type == type }
    if (a != null) { a.count++; a.text = text; a.lastSeen = now() }
    else alerts.add(0, CitsAlert(type, text, 1, now()))
    alerts.removeAll { now() - it.lastSeen > 30_000 }   // Ticker jede Sekunde
}
```

Leiste ohne Einträge: Text „Keine Events", Chevron `GONE`, `isClickable = false`.

### Verbindungsstatus

In der Zeile „Quelle" im Zustand `STATE_EXPANDED`: Label — Statuscode (Mono, 10sp, zweizeilig
erlaubt) — Umschalter USB/BT (je 44dp).

- verbunden: `CONNECTED ttyACM0@115200` in `?attr/colorTertiary`
- Reconnect: `RECONNECT n/5 …` in `@color/v2x_warn`

Der Statustext darf umbrechen; die Zeilenhöhe wird von den 44-dp-Pillen bestimmt und bleibt gleich.

## 5. Layer-Sheet, Settings, Frame-Detail

- **Layer-Sheet**: Kartenstil als Einzelauswahl (Häkchen rechts, Zeile `colorPrimaryContainer`),
  Overlays als `MaterialSwitch`. Beide Gruppen 56dp Zeilenhöhe, Gruppentitel als
  `TextAppearance.V2X.Label` in `colorPrimary`. Farbpunkt links = Markerfarbe des Overlays.
- **Settings**: Kategorien als aufklappbare Zeilen mit `ic_expand_more` (Rotation 180° beim
  Öffnen). Zeile = Titel + Sekundärtext links, Wert/Schalter rechts. Destruktive Aktion am Ende,
  `colorError` als Kontur, nicht als Fläche.
- **Frame-Detail**: Kopf mit Typbadge (Typfarbe auf `dim`-Fläche) + Zeitstempel + Signaturhinweis.
  Felder als 2-spaltiges Raster, Werte in Mono. Hex-Dump auf `colorSurfaceContainerLowest` mit
  Offset-Spalte in `colorOnSurfaceVariant`.

## 6. Reihenfolge für den Umbau

1. `colors_v2x.xml`, `themes.xml`, `values-night/themes.xml` einsetzen, Theme im Manifest umstellen.
2. In allen Layouts feste `@android:color/` und Hex-Werte durch `?attr/`-Rollen ersetzen.
3. `dimens_v2x.xml` einsetzen, dp-Literale in den Layouts ersetzen.
4. `shapes_v2x.xml` + `type_v2x.xml` einsetzen, Karten/Sheets/Buttons darauf umstellen.
5. Kartenscreen auf Peek-Sheet umbauen (Toolbar entfernen, Peek-Streifen, FAB-Anker).
6. Meldungsleiste ergänzen, Ampelzelle als eigenes Custom-View mit den drei Zuständen.
