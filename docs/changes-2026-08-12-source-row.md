# Änderungen 2026-08-12 — "Quelle"-Zeile (USB/BT) + Frame-Log-Kopfzeile

Zusammenfassung aller Änderungen aus dieser Session am Kartenscreen-Sheet
(`activity_main.xml` / `MainActivity.kt`), inklusive der mehreren
Korrekturdurchgänge auf Nutzer-Feedback hin.

## 1. Sichtbarkeitsregel korrigiert

**Bug:** Die Quelle-Zeile (USB/BT-Umschalter) war fälschlich schon im
eingeklappten Sheet-Zustand sichtbar.

**Fix:** Quelle-Zeile (`sourceRow`) und Frame-Log-Kopfzeile
(`logHeaderRow`) starten mit `android:visibility="gone"` und werden in
`MainActivity.setupBottomSheet()`s `onStateChanged`-Callback gemeinsam
ein-/ausgeblendet:
- **COLLAPSED** (Peek): beide `GONE` — nur Ampelzelle/Tempo/Quelle-Kurzstatus
  (Peek-Streifen) + C-ITS-Zähler-Zeile sichtbar.
- **HALF_EXPANDED**: sieht aus wie **EXPANDED** — Nutzer-Vorgabe, damit der
  Halb-Zustand nicht nur das leere Frame-Log zeigt, sondern schon die volle
  Detailansicht.
- **EXPANDED**: beide sichtbar (unverändert).

## 2. Reihenfolge/Position

Zuerst stand die Frame-Log-Kopfzeile ("Paket-Log" + Gesamtzahl + Zahnrad)
über der Quelle-Zeile — auf Nutzer-Korrektur getauscht:

**Aktuelle Reihenfolge** (von oben nach unten im Sheet):
1. Peek-Streifen (Ampelzelle, Tempo/Kurs, Quelle-Kurzstatus) — immer sichtbar
2. C-ITS-Zähler-Zeile (CAM/DENM/SPATEM/MAPEM) — immer sichtbar
3. **Quelle-Zeile** (USB/BT) — nur ab HALF_EXPANDED
4. **Frame-Log-Kopfzeile** ("Paket-Log" + Gesamtzahl + Zahnrad) — nur ab
   HALF_EXPANDED
5. Trennlinie
6. Eigentliches Frame-Log (RecyclerView)

## 3. Quelle-Zeile — Styling

Container: neues Drawable `bg_source_row.xml`, Radius 12dp, Rahmen 1dp.
Inhalt (horizontal): Label "SOURCE" — Statustext (wächst, rechtsbündig) —
USB-Button — BT-Button (Pillen, min. 44×44dp, Radius 9dp).

## 4. Bugfix: Connection-Meldung stand im Button

**Bug:** Beim Verbinden/Fehler zeigte der Button-Text selbst die
Live-Meldung an (z. B. "USB ✕ ttyACM0" oder eine Fehlermeldung), obwohl die
Meldung bereits links davon in `sourceStatusText` steht — doppelt und
inkonsistent mit dem reinen Farbwechsel-Konzept.

**Fix:** `onUsbState()`/`onBtState()` setzen den Button-Text jetzt fest auf
`@string/connect` bzw. `@string/connect_bt`, unabhängig vom State — nur
noch `backgroundTintList` (und die Textfarbe, siehe Punkt 5) ändern sich
noch. Die Verbindungsmeldung (`info`) fließt weiterhin ausschließlich in
`sourceStatusText`.

## 5. Bugfix: Nur für Dark Mode ausgelegt

**Bug:** Container-Hintergrund (`bg_source_row.xml`) und Button-Textfarben
waren mit fest codierten Dark-Theme-Hex-Werten gebaut (`#1E1E2C`-Fläche,
`#0A0A0F`-Buttontext) — in Light Mode dadurch ein dunkler Fleck mit
schwarzer Beschriftung statt eines hellen Cards wie der Rest der App.

**Fix, analog zur bereits Theme-fähigen C-ITS-Zähler-Zeile:**
- `bg_source_row.xml`: Fläche/Rahmen jetzt `?attr/colorSurfaceVariant` /
  `?attr/colorOutline` statt fixer Hex-Werte — hell in Light Mode, dunkel
  angehoben in Dark Mode, wie die restlichen Karten der App.
- Neue Hilfsfunktionen `sourcePillActiveColor()` / `sourcePillTextColor()`
  in `MainActivity.kt`: lösen `?attr/colorPrimary` / `?attr/colorOnPrimary`
  / `?attr/colorOnSurfaceVariant` zur Laufzeit aus dem aktuellen Theme auf,
  statt fixer Hex-Werte. Aktiv (verbunden) = `colorPrimary`-Fläche +
  `colorOnPrimary`-Text (kontrastreich in beiden Themes); inaktiv (idle) =
  transparent + `colorOnSurfaceVariant`-Text.
- Label/Statustext nutzten schon vorher `?attr/colorOnSurfaceVariant` —
  unverändert, war schon Theme-fest.

## 6. Frame-Log-Kopfzeile (neu)

Eigene Zeile über dem Frame-Log: Titel "Paket-Log"/"Frame log" (Outfit
Bold 17sp) + Gesamtzahl (`logStats`, jetzt nur noch `"<N> total"` statt
`"<N> pkts · <M>/min"` — die Rate steht weiterhin separat im
Peek-Streifen) + Zahnrad-Icon (`logSettingsGear`, öffnet `SettingsActivity`,
gleiche Aktion wie der bestehende Settings-FAB).

## Geänderte Dateien
- `android/app/src/main/res/layout/activity_main.xml`
- `android/app/src/main/res/drawable/bg_source_row.xml` (neu)
- `android/app/src/main/java/org/opentrafficmap/receiver/MainActivity.kt`

## Verifikation
- `./gradlew assembleDebug` — erfolgreich (nur vorbestehende
  `PreferenceManager`-Deprecation-Warnings).
- Per `adb install -r` auf dem verbundenen Gerät installiert.
- Alle drei Sheet-Zustände (Collapsed/Half/Expanded) per `adb`-Swipe +
  Screenshot durchgeprüft: Quelle-Zeile korrekt erst ab Half sichtbar,
  Reihenfolge Quelle → Frame-Log-Kopfzeile bestätigt.
- Light/Dark-Fix und der Button-Text-Fix wurden vom Nutzer selbst am
  Gerät gegengeprüft und als korrekt bestätigt.
