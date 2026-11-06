# Gemessene Geometrie der schwebenden Fenster

Kamera-Overlay und Dashcam-Statuspunkt verwenden den gemessenen Fensterursprung:
`getLocationOnScreen() - LayoutParams.x/y`. Das entspricht dem Grundprinzip der
[MG4Control-Buttons in der oberen Leiste](https://github.com/SliDeeN/MG4Control/blob/main/app/src/main/java/com/mg4/control/service/FloatingButtonsOverlay.kt#L311-L328).

Es gibt keinen AA/CarPlay-Paketdetector und keine feste Umschaltung zwischen
1920/1778 Pixeln oder einen angenommenen 142-Pixel-Versatz mehr. Die Bildschirmgröße
kommt vom tatsächlichen Display. Für die Kamera liefert die angehängte View den
sichtbaren Fensterrahmen. Der manuell einstellbare obere Mindestabstand bleibt als
zusätzliche Benutzervorgabe erhalten. Der Statuspunkt darf weiterhin in die obere
Systemleiste und verwendet dafür die gesamte physische Displayfläche.

Nachgeregelt wird nach Layout-Durchläufen und alle 500 ms. Ausstehende
`updateViewLayout`-Aufrufe werden erst nach dem Layout vermessen. Während einer
Zieh-/Pinch-Geste pausiert die automatische Korrektur.

Die bevorzugte Kameraposition wird in physischen Bildschirmkoordinaten gespeichert.
Vorübergehendes Begrenzen am sichtbaren Rand überschreibt weder diese Position noch
die gewünschte Fenstergröße. Wird mehr Platz frei, kehrt das Fenster dorthin zurück.
Alte Fensterkoordinaten werden beim ersten Einblenden anhand der dann gemessenen
Position einmalig übernommen; danach wird kein alter SAIC/Vollbild-Modus ausgewertet.
Die bisherige Entwickleroption „AA/CP foreground poll“ entfällt.

## Fahrzeugtest

1. Kamera-Overlay mittig platzieren. Zwischen Launcher, Android Auto und CarPlay
   mehrfach hin- und herwechseln. Die sichtbare Position soll gleich bleiben.
2. Dasselbe mit einer anderen App im Vollbild wiederholen. Die Geometrie darf nicht
   vom Paketnamen abhängen. Systemleisten gegebenenfalls per Wischgeste einblenden.
3. Fenster nah an die linke/rechte sowie obere/untere Grenze setzen und wechseln.
   Falls der sichtbare Rahmen kleiner wird, muss das Fenster vollständig hinein
   passen. Beim Zurückwechseln sollen ursprüngliche Position und Größe zurückkehren.
4. Während eines Wechsels ziehen und mit zwei Fingern vergrößern/verkleinern. Keine
   konkurrierende automatische Korrektur während der Geste; danach wieder nachregeln.
5. Fenster schließen und unter einer anderen Vordergrund-App erneut öffnen. Nach
   der erstmaligen Positionsübernahme sollen dieselben Bildschirmkoordinaten gelten.
6. Dashcam-Statuspunkt aktivieren. Die eingestellte Position muss bei denselben
   Wechseln erhalten bleiben, auch am Rand und oben in der Systemleiste.
7. Kompaktansicht, gedrehte Seitenkamera, Rückspiegel und beide Bewegungssperren
   prüfen. Diese Darstellungs- und Bedienoptionen müssen weiter funktionieren.

Geometrieänderungen der Kamera werden im Entwicklerprotokoll mit Quelle
`FloatingGeometry` protokolliert. Die Prüfung am Fahrzeug steht noch aus.

## Lokale Validierung

`testDebugUnitTest`: 60 Tests ohne Fehler, einschließlich Wechseln zwischen
Fensterursprüngen, Begrenzung und Wiederherstellung der bevorzugten Position sowie
Statuspunkt-Koordinaten. `assembleDebug`, `assembleRelease` und `lintDebug` sind
erfolgreich (keine Lint-Fehler). Die Release-Test-APK ist mit dem vorhandenen
Projektzertifikat signiert und ihre Signatur wurde geprüft.
