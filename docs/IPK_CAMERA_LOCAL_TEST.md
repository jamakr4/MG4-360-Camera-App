# Lokaler IPK-Kameratest

Branch: `codex/ipk-camera-auto-route`, Basis `90fb90c7dfae2087dd27ff4e569a6d21734c062d`.
Aktuelle Test-APK: `0.8.19-ipk-resume`, VersionCode 37, gleicher Paketname und vorhandenes Projektzertifikat. Die Routing-Diagnose D1, abgerundete Ecken und digitaler Rückspiegel sind enthalten. Der Status trägt weiterhin die Kennzeichnung `IPK2` und ergänzt „Routing-Diagnose D1“.

Der Fahrzeugtest vom 03.11.2026 bestätigt flüssigere parallele Kameraausgabe, transparente Ränder und Kompaktansicht. Die automatische Anforderung bleibt auf der Verbrauchsseite ohne sichtbare Wirkung, auch in beiden Bedienmodi des rechten Lenkradsticks. D1 ergänzt einen Diagnoseverlauf, um die fehlende Voraussetzung für den Seitenwechsel zu ermitteln; siehe [Routing-Diagnose](IPK_ROUTING_DIAGNOSIS.md).

## Aktivieren und prüfen

1. Die signierte lokale Release-APK als Update der Kamera-App installieren.
2. Einstellungen → Blinker-Kamera: „Kamera beim Blinken anzeigen“ und „Blinker-Kamera zusätzlich im Fahrerdisplay“ aktivieren. Die neue IPK-Funktion ist zunächst ausgeschaltet. Für einen Test im Stand muss „Nur Spurwechsel“ ausgeschaltet sein.
3. Im IPK Musik oder Navigation auswählen. Links, rechts und Blinker aus prüfen. Hauptbildschirm und IPK sollen dieselbe Seitenkamera parallel anzeigen. Kompaktansicht ein-/ausschalten und beide Ausschnittregler vergleichen, jeweils mit und ohne Drehung in Fahrtrichtung. Der sichtbare Kameraausschnitt muss auf beiden Displays übereinstimmen. Die IPK-Ausgabe greift nicht auf das Vorschauziel des Hauptbildschirms zu.
4. Für den Vergleich von Startzeit und Flüssigkeit etwa fünf Sekunden auf Musik oder Navigation blinken. Der Status zeigt anschließend die gemessenen Vorschau-FPS und die Zeit bis zur Freigabe des ersten Bildes.
5. „Navigationsseite automatisch anfordern“ aktivieren, im IPK den Bordcomputer wählen und mindestens fünf Sekunden blinken. Zuerst wird der vorhandene SAIC-MapService verwendet, bei fehlender Bestätigung folgen direkt dieselben zwei OEM-Property-Schreibwerte. Erst wenn der gelesene Tab 1 oder 2 ist, wird die Kamera im IPK eingeblendet.
6. Falls der Wechsel nicht erfolgt: Den vollständigen `IPK2`-Status in den Blinker-Kamera-Einstellungen festhalten. Er aktualisiert sich bei geöffnetem Dialog alle 500 ms und bleibt nach dem Ausblenden sowie nach einem Dienstneustart erhalten. Detaillierte Ereignisse stehen außerdem im vorhandenen Entwickler-Ereignisprotokoll mit Quelle `IpkCamera` beziehungsweise in `adb logcat -s IpkCamera CameraStreamManager`.
7. Rückwärtsgang/OEM-360, Wechsel links → rechts, Wechsel einer IPK-Seite während der Anzeige und Standby/Wiederaufwachen prüfen. Die IPK-Kamera muss mit den bestehenden OEM-AVM- und Blinkerprioritäten verschwinden. Mit laufender Dashcam außerdem kontrollieren, dass die Aufnahme weiterläuft.

Die vorhandenen Einstellungen für Kompaktansicht, beide Ausschnittregler und Drehung in Fahrtrichtung gelten auch für das IPK. Die gemeinsame `OverlayCompactGeometry` bestimmt den Ausschnitt einschließlich der unterschiedlichen Reglerzuordnung für linke und rechte Kamera. `IpkPreviewGeometry` passt diesen Ausschnitt proportional in das 260-px-Quadrat ein. Die frühere IPK-Ausgabe berücksichtigte nur die Drehung und zeigte auch bei aktivierter Kompaktansicht das vollständige Bild.

Änderungen am Kompakt-Schalter oder den Reglern aktualisieren die bestehende TextureView ohne erneutes Kamera-Attach. Bei ausgeschalteter Kompaktansicht erscheint wieder das vollständige 3:2-Bild. Freie Flächen um das Bild bleiben transparent; schwarze Pixel im Kamerabild selbst bleiben erhalten. Durch das Einpassen des beschnittenen Bildes kann die Darstellung die verfügbare IPK-Fläche besser ausnutzen.

Die Einstellung für abgerundete Ecken gilt ebenfalls für das IPK. Der Radius verwendet wie auf dem Hauptdisplay `UiPrefs.getCornerRadiusPx`: halbe kürzere Bildkante mal eingestellter Radiusanteil. Die Kontur schneidet das sichtbare Kamerarechteck einschließlich Kompaktansicht und Drehung aus; frei bleibende Bereiche bleiben transparent. Radiusänderungen werden direkt übernommen. Beim Test Radius 0 und einen deutlich gerundeten Wert mit beiden Kameras sowie Kompaktansicht ein/aus vergleichen.

## Digitaler Rückspiegel im IPK

Unter Einstellungen → Digital Rearview den digitalen Rückspiegel und den neuen Schalter „Digitalen Rückspiegel zusätzlich im Fahrerdisplay anzeigen“ aktivieren. Der neue Schalter ist standardmäßig aus und unabhängig vom IPK-Blinkerkamera-Schalter.

Das Heckbild verwendet dieselbe CameraSession und die bereits vorhandene horizontale Spiegelung des nativen Vorschau-Sinks. Wie die Dashcam-Ausgabe hat es das logische Format 720 × 480. Es wird vollständig und proportional als etwa 260 × 173 px zentriert in die IPK-Fläche eingepasst; oben und unten bleiben jeweils etwa 43 px transparent. Die Kompaktregler und Drehung für Seitenkameras verändern das Heckbild nicht. Die Eckenrundung gilt auch für den Rückspiegel.

Wenn die IPK-Blinkerkamera aktiviert ist, hat sie Vorrang und wird danach wieder durch den Rückspiegel ersetzt. Ist ausschließlich der IPK-Rückspiegel aktiviert, bleibt er auch während eines Blinkervorgangs sichtbar, während auf dem Hauptdisplay die Seitenkamera erscheinen kann. Beim Wechsel zwischen Rückspiegel und Blinker beginnt jeweils eine neue IPK-Anzeigesitzung mit eigenem Routingbudget; ein Wechsel links → rechts zählt weiterhin zur selben Blinkersitzung.

Die bisherige Rückspiegelsteuerung gilt gemeinsam: ausschalten, schließen, zeitweiliges Ausblenden und Öffnen der Einstellungen pausieren auch den IPK-Rückspiegel. Bei sichtbarer Hauptansicht kann das IPK weiterhin den Rückspiegel anzeigen, ohne die dort manuell ausgewählte Kamera umzustellen. OEM-AVM, Rückwärtsgang und sichtbare Vierkamera-Kachelansicht geben die Kameras wie bisher frei. Die Ausgabe bleibt auf den nativen Seiten Musik/Navigation sichtbar; das automatische Umschalten aus Verbrauch/Einstellungen ist weiterhin ungelöst.

Fahrzeugtest: Musik oder Navigation wählen; Rückspiegel auf beiden Displays und bei paralleler Dashcam-Aufnahme vergleichen. Links/rechts blinken und Rückkehr zum Heckbild prüfen, dann den IPK-Blinkerschalter deaktivieren und prüfen, dass das Heckbild im IPK beim Blinken erhalten bleibt. Anschließend den IPK-Rückspiegel-Schalter bei laufendem Spiegel ein/aus schalten, zeitweiliges Ausblenden testen und OEM-360/Rückwärtsgang sowie Vierkamera-Kachelansicht prüfen.

## Rückkehr nach OEM-360

Ein OEM-Start entfernt die eigenen Kamerafenster und beendet eine ausstehende Wiederaufnahme. Ein OEM-Stop startet nun einen eigenen Rückkehrlauf auf dem Main-Handler, unabhängig von Blinker- oder Gangänderungen. Bis zur Freigabe bleiben die eigenen Kamerafenster entfernt.

Der Lauf prüft alle 250 ms den Vordergrundzustand. Solange OEM-AVM sichtbar oder der OEM-Latch aktiv ist, wird nichts wieder angezeigt. Erst nach mindestens 250 ms bestätigter Abwesenheit wird die Anzeigeentscheidung erzwungen. Ist der Vordergrundstatus nicht lesbar, muss zusätzlich das unabhängige OEM-Lebenszyklussignal `DestroyEnd` vorliegen. Ein erneuter OEM-Start und das Beenden des Dienstes löschen den Rückkehrlauf.

Vor der erneuten Entscheidung werden Blinker und Gang frisch aus den bereits verwendeten Fahrzeugsignalen gelesen und die alten Entscheidungscaches verworfen. Dadurch kann der digitale Rückspiegel auch bei unverändertem Blinker und Gang wieder erscheinen. Die normale Entscheidung berücksichtigt weiterhin Rückwärtsgang, temporäres Ausblenden, Einstellungen, Kachelansicht und die tatsächlich sichtbare IPK-Seite. Ein vorhandener aktueller Blinker erhält weiterhin Vorrang vor dem Rückspiegel.

Fahrzeugtest: Auf Musik/Navigation den digitalen Rückspiegel einschalten, OEM-360 öffnen und wieder schließen. Danach ohne Betätigung von Blinker oder Gang prüfen, dass das Heckbild zurückkehrt. Mehrfach wiederholen und einmal OEM-360 während der Rückkehr erneut öffnen. Zusätzlich mit paralleler Dashcam-Aufnahme und mit einer noch aktiven Blinkeranzeige testen.

## Verhalten der automatischen Seitenanforderung

- Unterstützte Android-Seiten: Navigation = 1, Musik = 2, gelesen aus `0x21407b18`.
- Navigation wird nur aus Bordcomputer = 0 und Einstellungen = 5 angefordert. Telefon = 3, Warnzentrum = 4, Assistenz = 6 und unbekannte Zustände haben Vorrang.
- Die Car-API, aktuelle Seite und MapService-Verbindung werden bei aktivierter Funktion bereits ohne Blinker vorbereitet. Dabei wird kein Kameraziel angelegt und keine Seite angefordert.
- Höchstens zwei abgeschlossene Versuche pro IPK-Anzeigesitzung mit mindestens 1,5 Sekunden Abstand. Warten auf die Verbindung verbraucht keinen Versuch; eine abgelehnte oder nach zwei Sekunden nicht verfügbare Verbindung zählt als Fehlschlag.
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

Für die aktuelle Test-APK wurde ausschließlich während des Builds `versionCode 37` und `versionName "0.8.19-ipk-resume"` gesetzt und anschließend der ursprüngliche Inhalt von `app/build.gradle` wiederhergestellt. Die Versionsdatei ist daher kein Teil dieses Folgecommits; die bereits vorhandene lokale Versionsänderung im anderen Checkout bleibt beim Aktualisieren des Branches erhalten. Ein normaler eigener Build verwendet die Versionswerte seines Checkouts.

Die lokalen Tests prüfen Tab-Freigabe, Prioritäten, begrenzte Wiederholungen, manuelle Seitenwahl nach Bestätigung, Positionierung und die Unabhängigkeit der Vorschau-Mailboxen. Die IPK-Geometrietests bilden die Viewportecken zurück auf das ursprüngliche Kamerabild ab: Der Ausschnitt muss für beide Kameras, beide Drehungen, asymmetrische und maximale Beschnitte sowie mehrere Displaygrößen stimmen und proportional in die Fläche passen. Das Verhalten des physischen IPK und die Annahme der Seitenanforderung bleiben der Fahrzeugtest.

Prüfstand 03.11.2026: Debug- und Release-Build von D1 erfolgreich; 55 Java-Tests ohne Fehler oder übersprungene Tests. Die signierte Release-APK wurde mit `apksigner verify` geprüft. Der native Mailbox-Test inklusive ungedrosselter 25-FPS-Zuführung war beim vorherigen Diagnose-/Performance-Build erfolgreich. Flüssigere Ausgabe, Transparenz und Kompaktansicht wurden im Fahrzeug bestätigt. Automatisches Routing bleibt ungelöst; die zusätzlichen D1-Signale müssen noch im Fahrzeug erfasst werden.

Der anschließende Rundungsbuild wurde als Release erneut erfolgreich gebaut und mit `apksigner verify` geprüft. Die Eckenrundung ist noch im Fahrzeug zu prüfen.

Der Rückspiegel-Folgebuild ist als Debug und Release gebaut; 61 Java-Tests sind ohne Fehler oder übersprungene Tests erfolgreich. Neue Tests prüfen die Priorität und Rückkehr der Bildquellen, unabhängige IPK-Auswahl, Freigabe bei blockierenden Zuständen und den vollständigen ungedrehten 3:2-Heckausschnitt trotz aktiver Seitenkamera-Beschneidung. Signatur und APK-Version wurden geprüft. Der IPK-Rückspiegel benötigt noch den Fahrzeugtest einschließlich paralleler Dashcam-Aufnahme.

Prüfstand 04.11.2026: OEM-Rückkehrkorrektur als Debug und Release erfolgreich gebaut; 65 Java-Tests ohne Fehler oder übersprungene Tests. Die zusätzlichen Tests prüfen vorzeitigen OEM-Stop, erneutes Auftauchen der OEM-App, unbekannten Vordergrundzustand und Abbruch alter Rückkehrversuche. Signatur und Version der bereitgestellten APK sind geprüft. Der Ablauf nach OEM-360-Ende muss noch im Fahrzeug bestätigt werden.
