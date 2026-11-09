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

**Smooth Entry** unter **Einstellungen → Anzeige** gilt zusätzlich für die gesamte
IPK-Spiegelfläche samt Hintergrund. Die vorhandene Einstellung bleibt standardmäßig
aktiv. Erst nach einem frischen Kamerabild und dessen TextureView-Aktualisierung
blendet die Fläche von 18 % auf 100 % über 110 ms ein. Beim Ausschalten des Spiegels
oder Abwählen der IPK-Ausgabe blendet das zuletzt angezeigte Bild über 90 ms aus;
der Kamera-Sink wird vor Beginn dieser Animation abgemeldet. Ohne Smooth Entry
erfolgen die Übergänge sofort, die Erstbild-Prüfung bleibt aktiv.

Seitenwechsel, unbekannter Seitenstatus, Rückwärtsgang, OEM-Übergabe, Kachelansicht,
Kameraausfall, Displayverlust, Bildschirm-Aus und Dienstende entfernen die Fläche
sofort und brechen laufende Animationen ab.

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
7. App-Hauptansicht und Einstellungen öffnen/schließen: Der FICM-Spiegel muss
   ausgeblendet bleiben, während der IPK-Spiegel ohne Unterbrechung weiterläuft.
   App mehrfach aus dem Hintergrund öffnen und IPK allein sowie Beides prüfen.
   OEM-360, Rückwärtsgang und Kachelansicht müssen die IPK-Ausgabe weiterhin
   freigeben; danach muss der aktive Spiegel zurückkehren. Auch ohne erneutes
   Blinken und nach Standby/Wiederaufwachen testen.
8. Mit laufender Dashcam wiederholen: Aufnahme und Hauptvorschau müssen weiterlaufen,
   auch wenn die IPK-Seite gewechselt oder die IPK-Ausgabe beendet wird.
9. Smooth Entry ein-/ausschalten: Nach dem ersten Kamerabild muss die gesamte Fläche
   inklusive Hintergrund weich einblenden beziehungsweise sofort erscheinen. Spiegel
   ausschalten und zwischen IPK/Beides/Infotainment wechseln; nur reguläres Ausschalten
   oder Abwählen der IPK-Ausgabe darf weich ausblenden. App/Settings öffnen und zwischen
   IPK und Beides wechseln, ohne die aktive IPK-Ausgabe neu einzublenden.
10. Während Ein-/Ausblenden mehrfach schnell aus-/einschalten, Smooth Entry abschalten,
    die IPK-Seite verlassen sowie OEM/R aktivieren. Neue Vorschauen dürfen nicht durch
    ältere Abschluss-Callbacks verschwinden. Fremde Seiten müssen sofort frei werden;
    OEM darf nicht auf das Ende einer Animation warten. Standby/Wiederaufwachen prüfen.

## Lokale Validierung

Für Smooth Entry lokal geprüft: `testDebugUnitTest` (71 Tests ohne Fehler,
einschließlich IPK-Routing, Anzeigezielen und sofortigen Abbruchentscheidungen),
`assembleDebug`, `assembleRelease` und `lintDebug` (keine Fehler).
Im Android-13-Automotive-Simulator bestanden sieben Übergangstests einschließlich
einer echten TextureView mit Testbild, drei OEM-Koexistenztests und zwei vorhandene
Fenster-/Navigationsleisten-Tests. Die optische Wirkung im echten IPK sowie die
Fahrzeug-Übergaben bleiben anhand der obigen Liste im Fahrzeug zu prüfen.

Ausgabeereignisse: `adb logcat -s IpkCamera CameraStreamManager`; zusätzlich stehen
sie im vorhandenen Entwickler-Ereignisprotokoll unter `IpkCamera`.
