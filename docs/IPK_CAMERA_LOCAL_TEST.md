# Lokaler IPK-Kameratest

Branch: `codex/ipk-camera-auto-route`, Basis `90fb90c7dfae2087dd27ff4e569a6d21734c062d`.
Zweiter Fahrzeugtest: signierte APK `0.8.13-ipk2`, VersionCode 31, gleicher Paketname und vorhandenes Projektzertifikat. Im Status steht zur Identifikation `IPK2`.

Der erste Fahrzeugtest bestätigt die parallele Kameraausgabe. Auf der Verbrauchsseite blieb der sichtbare Inhalt bei der automatischen Anforderung unverändert. Ob der neue Anforderungsweg dort funktioniert, ist noch im Fahrzeug zu prüfen.

## Aktivieren und prüfen

1. Die signierte lokale Release-APK als Update der Kamera-App installieren.
2. Einstellungen → Blinker-Kamera: „Kamera beim Blinken anzeigen“ und „Blinker-Kamera zusätzlich im Fahrerdisplay“ aktivieren. Die neue IPK-Funktion ist zunächst ausgeschaltet. Für einen Test im Stand muss „Nur Spurwechsel“ ausgeschaltet sein.
3. Im IPK Musik oder Navigation auswählen. Links, rechts und Blinker aus prüfen. Hauptbildschirm und IPK sollen dieselbe Seitenkamera parallel anzeigen. Die IPK-Ausgabe greift nicht auf das Vorschauziel des Hauptbildschirms zu.
4. Für den Vergleich von Startzeit und Flüssigkeit etwa fünf Sekunden auf Musik oder Navigation blinken. Der Status zeigt anschließend die gemessenen Vorschau-FPS und die Zeit bis zur Freigabe des ersten Bildes.
5. „Navigationsseite automatisch anfordern“ aktivieren, im IPK den Bordcomputer wählen und mindestens fünf Sekunden blinken. Zuerst wird der vorhandene SAIC-MapService verwendet, bei fehlender Bestätigung folgen direkt dieselben zwei OEM-Property-Schreibwerte. Erst wenn der gelesene Tab 1 oder 2 ist, wird die Kamera im IPK eingeblendet.
6. Falls der Wechsel nicht erfolgt: Den vollständigen `IPK2`-Status in den Blinker-Kamera-Einstellungen festhalten. Er aktualisiert sich bei geöffnetem Dialog alle 500 ms und bleibt nach dem Ausblenden sowie nach einem Dienstneustart erhalten. Detaillierte Ereignisse stehen außerdem im vorhandenen Entwickler-Ereignisprotokoll mit Quelle `IpkCamera` beziehungsweise in `adb logcat -s IpkCamera CameraStreamManager`.
7. Rückwärtsgang/OEM-360, Wechsel links → rechts, Wechsel einer IPK-Seite während der Anzeige und Standby/Wiederaufwachen prüfen. Die IPK-Kamera muss mit den bestehenden OEM-AVM- und Blinkerprioritäten verschwinden. Mit laufender Dashcam außerdem kontrollieren, dass die Aufnahme weiterläuft.

Die vorhandene Einstellung zur Drehung in Fahrtrichtung gilt auch für das IPK. Das vollständige Bild wird mit seitlichem beziehungsweise oberem/unterem Rand in das 260-px-Quadrat eingepasst. Die Kompakt-Beschneidung des Hauptbildschirms wird nicht zusätzlich auf das IPK angewendet.

## Verhalten der automatischen Seitenanforderung

- Unterstützte Android-Seiten: Navigation = 1, Musik = 2, gelesen aus `0x21407b18`.
- Navigation wird nur aus Bordcomputer = 0 und Einstellungen = 5 angefordert. Telefon = 3, Warnzentrum = 4, Assistenz = 6 und unbekannte Zustände haben Vorrang.
- Die Car-API, aktuelle Seite und MapService-Verbindung werden bei aktivierter Funktion bereits ohne Blinker vorbereitet. Dabei wird kein Kameraziel angelegt und keine Seite angefordert.
- Höchstens zwei abgeschlossene Versuche pro Blinkeranzeige mit mindestens 1,5 Sekunden Abstand. Warten auf die Verbindung verbraucht keinen Versuch; eine abgelehnte oder nach zwei Sekunden nicht verfügbare Verbindung zählt als Fehlschlag.
- Erster Versuch: MapService, Transaktion 27 (`requestProjection`). Zweiter Versuch: direkter Aufruf des Vendor-Instrument-Cluster-Managers mit `0x21407b00=1` und `0x21407b04=1`, genau wie im untersuchten OEM-`ClusterManager.requestProjection()`. Damit wird ein fehlender oder noch nicht initialisierter Cluster-Manager im MapService umgangen. Das ist eine Projektionsanforderung und keine verifizierte beliebige Seitenwahl.
- Ein erfolgreicher Binder-Aufruf ist noch keine Bestätigung des sichtbaren Seitenwechsels. Maßgeblich ist die danach gelesene Tab-Rückmeldung und beim Test das physische IPK.
- Sobald eine unterstützte Seite bestätigt wurde, wird während derselben Anzeige nicht erneut automatisch umgeschaltet. So wird eine anschließende Seitenwahl des Fahrers nicht zurückgedrängt.
- Beim Ausblenden wird das Kamerafenster entfernt. Ein generischer Rücksprung zur vorherigen nativen IPK-Seite ist nicht verifiziert und wird nicht durch erfundene Property-Schreibwerte simuliert. Das IPK kann auf Navigation bleiben.
- Es werden weder `isMapNavigating`, Guide-Status noch Straßen-/Manöverinformationen verändert. Damit wird eine laufende echte Navigation nicht als Kamera-Ausgang benutzt oder beendet.
- G-Meter und Trackmode gehören nicht zu diesem Build.

## Technische Änderungen

`SignalService` besitzt den neuen `IpkCameraOverlay`. Der Controller sucht Display 1 beziehungsweise eine andere physische Zweitausgabe, liest die tatsächliche Größe und legt ein nicht fokussierbares, nicht bedienbares Overlay auf die OEM-Inhaltsfläche. Bei 800 × 480 px liegt das 260 × 260-px-Fenster bei (482, 126). Display- und Surface-Verlust sowie Bildschirm-Standby werden behandelt.

`CameraProbe.attachPreviewSink(videoIndex, sinkId, surface)` ergänzt den vorhandenen Hauptbildschirm-Preview. Der native FrameConsumer nutzt dieselbe CameraSession, also keinen zweiten V4L2-Open. Jedes zusätzliche Ausgabeziel hat einen eigenen Worker und eine auf ein aktuelles Bild begrenzte Mailbox. Eine zusätzliche Bildratenbegrenzung entfällt: Die frühere Mindestpause von 50 ms ließ bei einer 25-FPS-Quelle mit 40-ms-Abstand nur jedes zweite Bild durch. Das Warten auf eine langsame IPK-Surface blockiert somit nicht selbst die Aufnahme oder den Hauptbildschirm. Standard-/Enhanced-Aufbereitung richtet sich nach dem aktuell von der gemeinsamen Capture-Pipeline gelieferten RGBA-Bild.

Car-/MapService-Aufrufe laufen auf einem eigenen Handler, unabhängig vom Anlegen und Prüfen des Vorschauziels. Eine frische Seitenauskunft aus dem Leerlauf kann beim Blinkerstart sofort verwendet werden. Nach dem Attach wird das erste Bild sofort und danach alle 40 ms geprüft; für ein bereits sichtbares Bild genügt die 250-ms-Prüfung. Der frühere Start wartete dafür auf die gemeinsame 250-ms-Schleife und gegebenenfalls auf Binder-Arbeit.

Der Status enthält `display`, `tab`, die Zahl abgeschlossener Routingversuche, Anforderungsfehler beziehungsweise eine bestätigte Seite und folgende Vorschauwerte:

- `FPS`: erfolgreiche frische native Surface-Posts pro Sekunde, über mindestens 500 ms gemessen. Das ist keine Messung der physischen IPK-Bildwiederholrate.
- `Start`: Zeit vom Anfordern der Blinkeranzeige durch den SignalService bis zur Freigabe des ersten frisch geposteten Bildes; inklusive eines eventuellen Seitenwechsels.
- `Alter`: Alter des letzten frischen Surface-Posts.
- `Ausgabe A/B ms`: letztes Intervall vom Übernehmen des RGBA-Bildes bis zum Surface-Post / Dauer von Window-Lock, Kopieren und Post. Sensor- und nachgelagerte IPK-Latenz sind darin nicht enthalten.

Bleibt die Verbrauchsseite stehen, unterscheiden Meldung und Ereignislog zwischen Verbindungsproblem, abgelehntem Aufruf und gesendeter, aber unbestätigter Anforderung. `tab=0` trotz `route=2/2` belegt weiterhin keinen erfolgreichen Seitenwechsel.

Das neue Fenster bleibt bis zum ersten nativen Frame transparent. Werden für mehr als 1,5 Sekunden keine frischen Vorschau-Bilder bestätigt, entfernt ein Watchdog das Fenster und lässt den darunterliegenden Inhalt wieder sichtbar werden. Ein späterer Versuch verwendet eine neue Surface; alte Vorschau-Worker können dadurch kein Bild der vorherigen Kamerarichtung in die neue Surface schreiben.

Bei parallel laufendem MG4Control belegen dessen Gauge und die Kamera denselben Android-Ausschnitt. Die Kamera wird beim Blinken als neues Overlay angelegt; eine explizite Prioritätsvereinbarung mit MG4Control existiert nicht. Beim Test bitte auch diesen gemeinsamen Betrieb prüfen, sofern dessen Cluster-Modul aktiviert ist.

## Lokal bauen

Die vom Originalprojekt ausgeschlossene Sound-Ressource muss vorhanden sein. Für diesen Build wurde die bereits lokale Datei aus `/Users/jan/Projekts/MG4-360-Camera-App/app/src/main/res/raw/` übernommen; sie bleibt gemäß vorhandener `.gitignore` unversioniert. Android SDK, NDK und OpenCV werden wie im Basisprojekt lokal benötigt.

```sh
./gradlew --offline --no-daemon testDebugUnitTest assembleDebug assembleRelease
c++ -std=c++17 -pthread app/src/test/cpp/latest_preview_frame_test.cpp -o /tmp/mg4-ipk-mailbox-test
/tmp/mg4-ipk-mailbox-test
```

Signierung wie im vorhandenen `tools/build_sign_release.sh` mit dem bereits im Projekt enthaltenen Plattform-Zertifikat. Keine automatische Installation oder Veröffentlichung.

Für die bereitgestellte zweite Test-APK wurde ausschließlich während des Builds `versionCode 31` und `versionName "0.8.13-ipk2"` gesetzt und anschließend der ursprüngliche Inhalt von `app/build.gradle` wiederhergestellt. Die Versionsdatei ist daher kein Teil dieses Folgecommits; die bereits vorhandene lokale Versionsänderung im anderen Checkout bleibt beim Aktualisieren des Branches erhalten. Ein normaler eigener Build verwendet die Versionswerte seines Checkouts.

Die lokalen Tests prüfen Tab-Freigabe, Prioritäten, begrenzte Wiederholungen, manuelle Seitenwahl nach Bestätigung, Positionierung und die Unabhängigkeit der Vorschau-Mailboxen. Das Verhalten des physischen IPK und die Annahme der Seitenanforderung bleiben der Fahrzeugtest.

Prüfstand 02.11.2026: Debug- und Release-Build erfolgreich; 53 Java-Tests ohne Fehler oder übersprungene Tests; nativer Mailbox-Test inklusive ungedrosselter 25-FPS-Zuführung erfolgreich. Die signierte ARM64-Release-APK wurde mit `apksigner verify` geprüft. Startzeit, Flüssigkeit und automatisches Routing dieses Folgebuilds sind noch im Fahrzeug zu bestätigen.
