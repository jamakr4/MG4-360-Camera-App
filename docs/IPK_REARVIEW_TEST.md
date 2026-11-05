# Digitaler Rückspiegel im IPK (Branch 87)

Übernommen aus `codex/ipk-camera-auto-route`: zusätzlicher nativer Vorschau-Sink mit
unabhängigem Ausgabethread, vollständiges gespiegeltes
Heckbild mit 3:2-Seitenverhältnis, gemeinsame Eckenrundung und OEM-360-Wiederaufnahme.
IPK-Blinkerkameras, deren Rotation/Kompaktausschnitt und automatische
Projektionsanforderungen sind nicht enthalten. Die gesamte quadratische IPK-Spiegelfläche
hat den deckenden SAIC-Hintergrundfarbton `#0C0C0C` (RGB 12/12/12), damit OEM-Symbole
und -Texte auch außerhalb des 3:2-Kamerabilds und an dessen gerundeten Ecken verdeckt
werden. Der Farbton entspricht `screen_background_dark`, den auch MG4Control in
[`ClusterGaugeView.kt`](https://github.com/SliDeeN/MG4Control/blob/main/app/src/main/java/com/mg4/control/ui/widget/ClusterGaugeView.kt#L914-L918)
für die gesamte 260×260-Fläche zeichnet. So soll sich die Abdeckfläche ohne
sichtbaren Farbwechsel in den IPK-Hintergrund einfügen.

## Einstellungen

Unter **Digitaler Rückspiegel** stehen dieselben Segment-Schaltflächen wie beim
Dashcam-Speicherziel zur Verfügung:

- **Infotainment / IPK / Beides**; Vorgabe ist wie bisher Infotainment.
- **Navigation / Media** für die IPK-Seite; Vorgabe ist Navigation. Diese Auswahl
  erscheint, sobald IPK oder Beides gewählt ist.

Die ausgewählte Seite muss im IPK manuell geöffnet werden. Auf anderen Seiten oder
bei nicht lesbarem Seitenstatus wird kein Kamerafenster angezeigt. Beim Zurückwechseln
zur gewählten Seite erscheint der aktive Spiegel wieder. Es werden keine
Fahrzeug-Properties zur Seitenwahl geschrieben und kein Navigationsfokus verändert.

## Prüfung im Fahrzeug

1. Rückspiegel aktivieren. Alle drei Anzeigeziele prüfen, auch nach App-Neustart.
   IPK allein darf kein Rückspiegel-Overlay auf dem Hauptdisplay erzeugen.
2. Navigation und Media abwechselnd auswählen. Nur die jeweils gewählte IPK-Seite
   darf den Spiegel zeigen. Verbrauch, Einstellungen und Warn-/Telefonseiten
   dürfen nicht überlagert werden; anschließend zur gewählten Seite zurückkehren.
3. Beides aktivieren und auf das Hauptdisplay-Overlay tippen. Nur dort muss der
   Spiegel für die eingestellte Dauer verschwinden; das IPK zeigt das Heckbild weiter.
4. Während des temporären Ausblendens extern ausschalten: beide Spiegel müssen
   verschwinden und auch nach Ablauf des Timers ausgeschaltet bleiben. Wieder extern
   einschalten und anschließend mit × schließen: ebenfalls beide Anzeigen aus.
5. Links/rechts blinken: das Hauptdisplay behält seine vorhandene Blinkerfunktion;
   das IPK bleibt bei der Heckkamera und zeigt keine Seitenkamera.
6. Eckenradius 0 und einen deutlich gerundeten Wert vergleichen. Die Rundung muss
   auf beiden Displays gelten. Ober-/unterhalb des IPK-Heckbilds und an dessen
   gerundeten Ecken muss der Hintergrund denselben sehr dunklen Farbton wie das
   übrige IPK haben; Texte und Symbole der ursprünglichen Navigation-/Media-Seite
   dürfen dort nicht durchscheinen. Auf sichtbare Kanten der Abdeckfläche achten.
7. OEM-360 öffnen/schließen, Rückwärtsgang einlegen/verlassen, Kachelansicht und
   Einstellungen öffnen/schließen sowie Standby/Wiederaufwachen prüfen. Während
   blockierender Zustände muss die IPK-Ausgabe freigegeben werden und anschließend
   bei weiter aktiviertem Spiegel zurückkehren. Auch ohne erneutes Blinken testen.
8. Mit laufender Dashcam wiederholen: Aufnahme und Hauptvorschau müssen weiterlaufen,
   auch wenn die IPK-Seite gewechselt oder die IPK-Ausgabe beendet wird.

## Lokale Validierung

Erfolgreich ausgeführt: `testDebugUnitTest` (56 Tests ohne Fehler), `assembleDebug`,
`assembleRelease`, `lintDebug` (keine Fehler) und der native
`latest_preview_frame_test.cpp`. Die Prüfung am Fahrzeug steht noch aus.

Ausgabeereignisse: `adb logcat -s IpkCamera CameraStreamManager`; zusätzlich stehen
sie im vorhandenen Entwickler-Ereignisprotokoll unter `IpkCamera`.
