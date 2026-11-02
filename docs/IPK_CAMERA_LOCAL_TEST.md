# Lokaler IPK-Kameratest

Branch: `codex/ipk-camera-auto-route`, Basis `90fb90c7dfae2087dd27ff4e569a6d21734c062d`.
Version: `0.8.11-ipk-local`, gleicher Paketname und VersionCode 28 wie die Basis.

## Aktivieren und prüfen

1. Die signierte lokale Release-APK als Update der Kamera-App installieren.
2. Einstellungen → Blinker-Kamera: „Kamera beim Blinken anzeigen“ und „Blinker-Kamera zusätzlich im Fahrerdisplay“ aktivieren. Die neue IPK-Funktion ist zunächst ausgeschaltet. Für einen Test im Stand muss „Nur Spurwechsel“ ausgeschaltet sein.
3. Im IPK Musik oder Navigation auswählen. Links, rechts und Blinker aus prüfen. Hauptbildschirm und IPK sollen dieselbe Seitenkamera parallel anzeigen. Die IPK-Ausgabe greift nicht auf das Vorschauziel des Hauptbildschirms zu.
4. „Navigationsseite automatisch anfordern“ aktivieren, im IPK den Bordcomputer wählen und blinken. Die App versucht, Navigation über den vorhandenen SAIC-MapService anzufordern. Erst wenn der gelesene Tab 1 oder 2 ist, wird die Kamera im IPK eingeblendet.
5. Falls der Wechsel nicht erfolgt: „Status des Fahrerdisplays aktualisieren“ in den Blinker-Kamera-Einstellungen antippen. Der letzte Status bleibt nach dem Ausblenden erhalten. Detaillierte Ereignisse stehen außerdem im vorhandenen Entwickler-Ereignisprotokoll mit Quelle `IpkCamera` beziehungsweise in `adb logcat -s IpkCamera CameraStreamManager`.
6. Rückwärtsgang/OEM-360, Wechsel links → rechts, Wechsel einer IPK-Seite während der Anzeige und Standby/Wiederaufwachen prüfen. Die IPK-Kamera muss mit den bestehenden OEM-AVM- und Blinkerprioritäten verschwinden. Mit laufender Dashcam außerdem kontrollieren, dass die Aufnahme weiterläuft.

Die vorhandene Einstellung zur Drehung in Fahrtrichtung gilt auch für das IPK. Das vollständige Bild wird mit seitlichem beziehungsweise oberem/unterem Rand in das 260-px-Quadrat eingepasst. Die Kompakt-Beschneidung des Hauptbildschirms wird nicht zusätzlich auf das IPK angewendet.

## Verhalten der automatischen Seitenanforderung

- Unterstützte Android-Seiten: Navigation = 1, Musik = 2, gelesen aus `0x21407b18`.
- Navigation wird nur aus Bordcomputer = 0 und Einstellungen = 5 angefordert. Telefon = 3, Warnzentrum = 4, Assistenz = 6 und unbekannte Zustände haben Vorrang.
- Höchstens zwei Versuche pro Blinkeranzeige mit mindestens 1,5 Sekunden Abstand; auch ein fehlgeschlagenes Binden zählt als Versuch. Der erste Versuch kann zunächst die Verbindung zum MapService aufbauen.
- Ein erfolgreicher Binder-Aufruf ist noch keine Bestätigung des sichtbaren Seitenwechsels. Maßgeblich ist die danach gelesene Tab-Rückmeldung und beim Test das physische IPK.
- Sobald eine unterstützte Seite bestätigt wurde, wird während derselben Anzeige nicht erneut automatisch umgeschaltet. So wird eine anschließende Seitenwahl des Fahrers nicht zurückgedrängt.
- Beim Ausblenden wird das Kamerafenster entfernt. Ein generischer Rücksprung zur vorherigen nativen IPK-Seite ist nicht verifiziert und wird nicht durch erfundene Property-Schreibwerte simuliert. Das IPK kann auf Navigation bleiben.
- Es werden weder `isMapNavigating`, Guide-Status noch Straßen-/Manöverinformationen verändert. Damit wird eine laufende echte Navigation nicht als Kamera-Ausgang benutzt oder beendet.
- G-Meter und Trackmode gehören nicht zu diesem Build.

## Technische Änderungen

`SignalService` besitzt den neuen `IpkCameraOverlay`. Der Controller sucht Display 1 beziehungsweise eine andere physische Zweitausgabe, liest die tatsächliche Größe und legt ein nicht fokussierbares, nicht bedienbares Overlay auf die OEM-Inhaltsfläche. Bei 800 × 480 px liegt das 260 × 260-px-Fenster bei (482, 126). Display- und Surface-Verlust sowie Bildschirm-Standby werden behandelt.

`CameraProbe.attachPreviewSink(videoIndex, sinkId, surface)` ergänzt den vorhandenen Hauptbildschirm-Preview. Der neue native FrameConsumer nutzt dieselbe CameraSession, also keinen zweiten V4L2-Open. Jedes zusätzliche Ausgabeziel hat einen eigenen Worker und eine auf ein aktuelles Bild begrenzte Mailbox, maximal 20 Vorschau-Bilder pro Sekunde. Das Warten auf eine langsame IPK-Surface blockiert somit nicht selbst die Aufnahme oder den Hauptbildschirm. Standard-/Enhanced-Aufbereitung richtet sich nach dem aktuell von der gemeinsamen Capture-Pipeline gelieferten RGBA-Bild.

Das neue Fenster bleibt bis zum ersten nativen Frame transparent. Werden für mehr als 1,5 Sekunden keine frischen Vorschau-Bilder bestätigt, entfernt ein Watchdog das Fenster und lässt den darunterliegenden Inhalt wieder sichtbar werden. Ein späterer Versuch verwendet eine neue Surface; alte Vorschau-Worker können dadurch kein Bild der vorherigen Kamerarichtung in die neue Surface schreiben.

Bei parallel laufendem MG4Control belegen dessen Gauge und die Kamera denselben Android-Ausschnitt. Die Kamera wird beim Blinken als neues Overlay angelegt; eine explizite Prioritätsvereinbarung mit MG4Control existiert nicht. Beim Test bitte auch diesen gemeinsamen Betrieb prüfen, sofern dessen Cluster-Modul aktiviert ist.

## Lokal bauen

Die vom Originalprojekt ausgeschlossene Sound-Ressource muss vorhanden sein. Für diesen Build wurde die bereits lokale Datei aus `/Users/jan/Projekts/MG4-360-Camera-App/app/src/main/res/raw/` übernommen; sie bleibt gemäß vorhandener `.gitignore` unversioniert. Android SDK, NDK und OpenCV werden wie im Basisprojekt lokal benötigt.

```sh
./gradlew --offline --no-daemon testDebugUnitTest assembleDebug assembleRelease
c++ -std=c++17 -pthread app/src/test/cpp/latest_preview_frame_test.cpp -o /tmp/mg4-ipk-mailbox-test
/tmp/mg4-ipk-mailbox-test
```

Signierung wie im vorhandenen `tools/build_sign_release.sh` mit dem bereits im Projekt enthaltenen Plattform-Zertifikat. Keine automatische Installation, Veröffentlichung oder Push.

Die lokalen Tests prüfen Tab-Freigabe, Prioritäten, begrenzte Wiederholungen, manuelle Seitenwahl nach Bestätigung, Positionierung und die Unabhängigkeit der Vorschau-Mailboxen. Das Verhalten des physischen IPK und die Annahme der Seitenanforderung bleiben der Fahrzeugtest.

Prüfstand 02.11.2026: Debug- und Release-Build erfolgreich; 48 Java-Tests ohne Fehler oder übersprungene Tests; nativer Mailbox-Test erfolgreich. Die signierte ARM64-Release-APK wurde mit `apksigner verify` geprüft, Version und zusätzliche JNI-Einstiegspunkte sind im fertigen Paket vorhanden.
