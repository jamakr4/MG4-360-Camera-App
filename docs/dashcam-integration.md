# Dashcam-Integration, 27. Oktober 2026

## Referenz und Attribution

Verglichen mit [Tommasov/MG4_Dashcam](https://github.com/Tommasov/MG4_Dashcam),
Branch `stable`, Tag `1.4.3`, Commit
[`f345a10`](https://github.com/Tommasov/MG4_Dashcam/commit/f345a10).
Ausgang dieses Repositories: `53fe443`; der Arbeitsbaum war sauber.
Keine anwendbare AGENTS.md gefunden. Keine Historie umgeschrieben, kein Push.

Übernommen/adaptiert wurden die native Aufnahme-Pipeline, Feldverarbeitung,
Kamerapuffer-Kopie in gecachten Speicher, bildgetriebene Komposition, Zeitraster,
Rotation mit kurzzeitig offen gehaltenen Kameras, parallele OEM-Freigabe,
Dateisynchronisierung, FAT-Truncate-vor-Unlink, OEM-Vordergrund-/Wiederanlaufprinzip,
Statusindikator und OEM-Capture-Zugriff. Tommaso Vietinas öffentliche Commitadresse
ist `tommasovietina@hotmail.com`; die entsprechenden Integrationscommits führen
seinen Co-authored-by-Trailer. Die vorhandene Codex-Konvention bleibt erhalten.

**Messwerte in übernommenen C++-Kommentaren stammen ausschließlich aus dem Fork.**
Sie sind keine Messungen dieses Builds. Hier wurden keine Fahrzeugtests durchgeführt.

## Verhalten und Abweichungen vom Fork

- USB / intern / automatisch und die bestehende Ordnerauswahl bleiben erhalten.
  USB wird durch tatsächliches Schreiben, fsync und Rücklesen geprüft.
  Kandidaten umfassen `/mnt/media_rw`, `/storage` und das App-Verzeichnis des Mediums.
  Intern wird ebenfalls geprüft; falls nötig wird das private App-Verzeichnis genutzt.
  Bei mehreren passenden Medien bleibt eine ausdrückliche Auswahl erforderlich.
- AUTO fällt bei fehlendem/unbeschreibbarem Medium intern zurück. Bei einem erkannten
  Muxer-Schreibfehler auf USB folgt ein interner Versuch und frühestens nach 60 Sekunden
  ein erneuter USB-Versuch. USB-only meldet einen Fehler. Volle Medien werden anhand
  fehlgeschlagener Schreiboperationen erkannt; unzuverlässige freie-Bytes-Angaben allein
  lösen keine Löschung aus. Ein bereits vollständig gefüllter Stick kann deshalb eine
  manuelle Bereinigung benötigen, wenn selbst die Schreibprobe nicht mehr gelingt.
- Der tatsächlich vom Service verwendete Pfad wird gespeichert und in den Einstellungen
  angezeigt. Diagnosezustand und Aufnahmefehler sind dort sichtbar; Details stehen im
  vorhandenen Laufzeitprotokoll.
- Der Ringpuffer löscht nur eigene Zeitstempel-MP4s, einschließlich älterer F/R/X/Y-Gruppen.
  Event-Quellen bleiben während ausstehender Kopien geschützt. Vor dem nächsten Segment
  wird ein Platz innerhalb des konfigurierten Limits freigemacht. Bei geschützten Dateien
  kann das Limit vorübergehend überschritten werden.
- FAT-Korrektur: Datei auf Länge null setzen, fsync, erst dann unlink; bei einem Fehler
  kein blindes Unlink. Pro Löschung werden Dateigröße, Ergebnis und freier Speicher vor/nach
  dem Vorgang protokolliert. Ein fehlgeschlagenes Unlink kann eine leere Datei zurücklassen.
  Die Wirksamkeit gegen verlorene FAT-Cluster bleibt am Fahrzeug zu prüfen.
- Kein automatisches Remount/`VolumeRepair`: Der Eingriff betrifft das ganze Volume und
  könnte OEM-Recorder oder andere Apps beim Schreiben unterbrechen. Es gibt hier keinen
  verlässlichen gemeinsamen Schreib-Lock mit diesen Prozessen. Reparatur/Dateisystemprüfung
  eines bereits beschädigten Sticks bleibt eine manuelle Aufgabe.
- Vier Aufnahmezellen à 720×480, Gesamtbild 1440×1040 inklusive 80-Pixel-Footer.
  Front/hinten oben, links/rechts unten; Archiv-Rückansicht ungespiegelt. Die vorhandene
  FPS-Einstellung bleibt wirksam. Die Pipeline wartet auf neue Bilder aller ausgewählten
  Kameras; bleibt eine aus, meldet die Aktivitätsüberwachung einen Fehler statt dauerhaft
  ein eingefrorenes Teilbild als laufende Aufnahme zu melden.
- Die Feldanordnung folgt dem im Fork untersuchten MG4-Treiber (gestapelte Felder trotz
  progressiver Kennzeichnung). Andere Firmware/Layouts müssen geprüft werden. Gepackte
  Aufnahme erfordert UYVY; abweichende Formate werden protokolliert und führen zu einem
  Fehler statt zu stiller Farbverfälschung. Die einzelnen Vorschauen behalten ihre bisherige
  Halbbild-Verarbeitung und Rückspiegel-Spiegelung.
- OEM-Freigabe: Broadcast weckt den Worker direkt; zusätzlich prüft der Service die aktive
  App. Die bestehende Geschwindigkeitsgrenze gilt für beide Wege. Kameras werden vor der
  MP4-Finalisierung freigegeben. Anders als im Fork bleibt die Finalisierung dem Worker
  zugeordnet: erst danach Validierung, Event-Kopie und Ringverwaltung. Native Freigabezeit
  und Stop-/Finalisierungszeit erscheinen im Laufzeitprotokoll.
- OEM-Nachlauf: 2 Sekunden nach regulärem Verlassen, 8 Sekunden nach kurzem/fehlgeschlagenem
  Aufruf. Bei unbekannter Vordergrundlage begrenzter Rückfall nach 60 Sekunden; keine
  Zeitüberschreitung, solange die Werksansicht nachweislich aktiv ist. Kachelansicht verbindet
  ihre Vorschauen nach der Pause wieder. Blinker-/Rückspiegel- und Event-Einstellungen bleiben.
- Starten / Aufnehmen / OEM-Pause / Fehler / Aus bleiben getrennt. „Aufnehmen“ setzt
  erfolgreich geschriebene Muxer-Samples voraus. Fünf Sekunden ohne Samples oder ein
  Schreibfehler stoppen das Segment. Ein unabhängiger Watchdog überwacht abgeschlossene
  Segmente (dreifache Segmentdauer, OEM-Pausen ausgenommen). Worker-Fehler werden abgefangen;
  MP4s ohne lesbaren Videotrack/Sample werden verworfen. Das ist eine Containerprüfung,
  kein vollständiger Decode jedes Frames.
- Android `ACTION_SHUTDOWN`: Schreibannahme sperren, Worker und Event-Kopien beenden,
  native Flushes und UI-Exporte abwarten, Dateisystem synchronisieren. Gemeinsames
  Wartebudget 7,5 Sekunden, Receiver-Abschluss spätestens nach 8,5 Sekunden.
  Ein blockierter Treiber/USB-Schreibvorgang lässt sich nicht sicher abbrechen; Zeitüberschreitung
  wird als unvollständig protokolliert. Ein harter Stromverlust ohne Android-Broadcast
  kann nicht garantiert abgesichert werden. USB-Vorbereitung meldet nur Erfolg, wenn alle
  verfolgten App-Schreibvorgänge und Synchronisierungen erfolgreich beendet sind. Sie ist
  kein systemweites Unmount und schützt keine fremden Apps.
- Statusindikator: standardmäßig aus, optional in den Dashcam-Einstellungen; nicht fokussierbar
  und nicht berührbar. Die experimentelle zweite SystemUI-Icon-Route des Forks entfällt,
  um doppelte bzw. nicht zuverlässig entfernbare Anzeigen zu vermeiden.
- OEM-Aufnahmen: Auflisten und Kopieren von `.nv12`/`.h264` aus
  `/data/user/0/com.saicmotor.hmi.aroundview/files` nach `<Ziel>/avm`.
  Zugriff erfolgt nur mit bestehenden Prozessrechten, ohne Root/SELinux-Umgehung.
  Nicht lesbare Verzeichnisse werden angezeigt. Keine Überschreibung vorhandener Exporte;
  Änderungen der Quelldatei während des Kopierens führen zu einem Fehler. Kein Löschen
  aus der privaten OEM-Ablage: Es fehlt ein zuverlässiger Lock mit dem OEM-Recorder.
  Rohaufnahmen werden nicht als MP4 ausgegeben oder konvertiert.

## Lokale Validierung

Mit installiertem JDK 25, Android SDK und OpenCV-Android-SDK:

```sh
source tools/java_env.sh
configure_gradle_java
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --offline
```

Ergebnis: 31 Unit-Tests, 0 Fehler/Fehlschläge; Lint: 0 Fehler, 198 Warnungen.
Alle 18 nativen CameraProbe-Deklarationen wurden gegen die exportierten JNI-Symbole
der ARM64-Bibliothek im Release-APK abgeglichen. `git diff --check` ist sauber.

Debug und Release bauen ihre ARM64-Bibliothek über CMake/NDK aus den Quellen.
Keine vorkompilierte `.so` wurde ins Repository aufgenommen. Die bestehende lokale
OpenCV-SDK-Voraussetzung in CMake bleibt bestehen. Release-APK ist unsigniert;
keine Installation oder Veröffentlichung vorgenommen.

Neue Unit-Tests prüfen Speicherzielwahl, eigene/geschützte Ringdateien, Löschen,
Auswerf-Schreibsperre, Aktivitäts-/Watchdogzustände und OEM-Rückfallbedingungen.
Sie ersetzen keine Android-Service-, V4L2- oder Fahrzeugtests. Lint wurde ausgeführt;
vorhandene Fehler bei URI-Flags, OEM-Broadcast-Registrierung und AppCompat-Tint wurden
mit kleinen separaten Korrekturen behoben. Verbleibende Warnungen sind im Lint-Bericht.

## Offene Fahrzeug-Abnahme

1. USB-only/AUTO/intern mit ausgewähltem Ordner, mehreren Sticks, FAT/exFAT, schreibgeschütztem,
   vollem und während der Aufnahme entferntem Medium; tatsächlichen Pfad und Fehler vergleichen.
2. Mehrere vollständige Ringdurchläufe: Events erhalten, fremde Dateien unverändert,
   freie Bytes und Löschfehler prüfen; anschließend Offline-Dateisystemprüfung auf verlorene Cluster.
3. Videoauflösung/Feldreihenfolge, Lesbarkeit von Kennzeichen, Farben, FPS/PTS und Segmentlücken
   messen. CPU/Temperatur mit und ohne Kacheln, Blinkeransicht und Rückspiegel vergleichen.
4. OEM per Rückwärtsgang, Taste und Broadcast, unter/über Geschwindigkeitsgrenze, schnelle
   Wiederholung, kurze Fehlstarts, lange Ansicht sowie fehlendes STOP-Signal prüfen.
   Kamerafreigabezeiten dieses Builds messen und Wiederanlauf aller Ansichten bestätigen.
5. Kamera-/Encoder-Stillstand, Schreibfehler, Prozessneustart, Eventkopie während Rotation,
   Auswerfen während Export und Android-Shutdown unter langsamen USB-Schreibzugriffen prüfen.
6. Sichtbarkeit/Position des optionalen Indikators und OEM-Dateirechte mit der tatsächlichen
   Plattformsignatur/SELinux-Policy prüfen. Keine gemessenen Fahrwerte liegen für diesen Build vor.

## Nachtrag: Fahrzeugmeldung zur Vorschau und Engineering-Bedienung

Der Fahrzeugtest des Nutzers mit 0.8.10 zeigte ein Kamerabild nur in der oberen
Hälfte der Vorschau. Ursache in der Integration: `configurePreviewWindowLocked`
reservierte 720×480, während `renderPreviewLocked` weiterhin ein Halbbild mit
720×240 kopierte. Die untere Hälfte blieb unbeschrieben. Die Surface-Geometrie
verwendet jetzt dieselbe Halbbildhöhe wie die Vorschau-Konvertierung. Das betrifft
den gemeinsamen Vorschaupfad für Hauptansicht, Blinker, Kacheln und Rückspiegel;
der kombinierte Dashcam-Recorder bleibt bei 720×480 pro Kamera / 1440×1040 gesamt.

Der OEM-Aufnahmezugriff ist jetzt im Engineering Mode, mit dem vorhandenen
sekundären Button-Stil. Der unabhängige Statusindikator bleibt standardmäßig aus
in den Dashcam-Einstellungen; Schalter-Stil, Akzentfarbe und Erklärung sind an die
bestehende Oberfläche angepasst. Die OEM-Aktion listet/exportiert vorhandene
Aufnahmen und startet keinen Werksrecorder.

Nach der Korrektur: Debug-/Release-Build erfolgreich, 31 Unit-Tests bestanden,
Lint 0 Fehler / 201 Warnungen, Layout-Zuordnung und gemeinsame Styles überprüft.
Die native Bibliothek wurde neu gebaut. Version bleibt 0.8.10 / Code 21 / Beta.
Ein erneuter Fahrzeugtest der korrigierten Vorschau und der Bedienung ist offen.
