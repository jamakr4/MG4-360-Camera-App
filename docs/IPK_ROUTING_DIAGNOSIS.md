# IPK-Routing: Fahrzeugtest und Diagnose D1

Stand 03.11.2026. Der Fahrzeugtest bestätigt flüssigere parallele Kameraausgabe, transparente Ränder und den Kompakt-Ausschnitt. Automatisches Umschalten aus der Verbrauchsseite funktioniert weiterhin nicht, sowohl im Modus Seitenauswahl als auch bei Medienbedienung des Lenkradsticks.

Der fotografierte Status zeigt `tab=0`, `route=2/2`, direkte OEM-Anforderung gesendet (`7b00=1,7b04=1`), Wechsel unbestätigt. Damit ist der zweite Property-Aufruf ohne gemeldete Exception durchgelaufen; eine sichtbare Seitenwahl ist damit nicht bestätigt. Der Status enthält nicht das Einzelergebnis des ersten MapService-Versuchs.

## Zusätzliche OEM-Analyse

In der lokalen TeleNav-APK `Nav_Map_overseas_eh32.apk` empfängt `com.telenav.arp.cluster.ClusterConnectorReceiver` das Ereignis `ACTION_CLUSTER_MAP_INTERNAL_READY`. Erst dann ruft er `SAICMapServiceWrapper.requestProjection()` auf und protokolliert das als Karten-Ready-Signal. Der Wrapper führt den bekannten Binder-Aufruf aus. Das erklärt die Nutzung als Projektionsbereitschaft, belegt aber keinen allgemein wirksamen Seitenwahlschalter aus der Verbrauchsseite.

Die lokale Adapter-APK `Navigation_SaicAdapterService_overseas_eh32.apk` schreibt bei diesem Aufruf `0x21407b00=1` und `0x21407b04=1` und verteilt `mapRequestProjection()` an Listener. In ClusterInteraction setzt das den UI-Zustand „Karte offen“. Die Fragmentwahl stammt weiterhin aus der vom Fahrzeug gelesenen Tab-Anforderung `0x21407b18`.

Die Cluster-App quittiert Seiten mit weiteren Ready-Signalen: Medien/App bereit (`0x21407b01`) und Navigation bereit (`0x21407b16`). Bisher fehlen deren gemessene Werte beim erfolglosen Routing. Ob sie eine zusätzliche Voraussetzung bilden, ist offen.

`MapFocus.requestAppFocus()` ist kein neutraler Lenkrad-Fokusschalter: Beim erfolgreichen Fokuswechsel versendet der untersuchte Adapter `SAIC_VR_ACTION_CANCEL_ROUTE`. Deshalb wird dieser Pfad für die Kamera nicht aufgerufen. Die Zähler `tab=1/2` bezeichnen Navigation/Musik. Der separate Bedienfokus wird über `0x21407b09` gelesen; seine Enum unterscheidet keinen Fokus, linken und rechten Fokus.

## Diagnosebuild D1

D1 ergänzt Messungen auf dem bereits separaten Car-IO-Handler. Es erfolgen keine zusätzlichen Property-Schreibzugriffe und keine Änderung an Navigations- oder Fokuszuständen. Die bisherigen zwei Projektionsversuche bleiben erhalten; dieser Build ist noch keine bestätigte Routingkorrektur.

Bei aktivierter automatischer Anforderung wird höchstens alle zwei Sekunden eine Zusatzmessung durchgeführt, außerdem bei gelesenen Tab-Wechseln und direkt vor/nach einem ausgeführten Projektionsversuch. Nur geänderte Werte beziehungsweise diese expliziten Vorher-/Nachher-Messungen werden gespeichert. Acht Einträge bleiben mit Uhrzeit im App-Status und in den Einstellungen nach einem Dienstneustart erhalten.

| Feld | Property | Bedeutung |
|---|---|---|
| `tab` | `0x21407b18` | Gemeldete IPK-Seite |
| `focus` | `0x21407b09` | Bedienfokus: 0 keiner, 1 links, 2 rechts, 3 reserviert |
| `centre` | `0x21407b0a` | Roher Zustand des IPK-Mittelbereichs; Wertbedeutung noch offen |
| `ready(M/N)` | `0x21407b01 / 0x21407b16` | Medien-/Navigationsbereitschaft |
| `area` | `0x21407b04` | Rückgelesener FICM-Mittelbereichsstatus |
| `send` | `0x21407b05` | Rückgelesenes SEND-Signal; Wertbedeutung noch offen |

`?` bedeutet nicht lesbar, keinen verfügbaren Wert oder eine Exception beim Lesen, nicht null/aus. Die Zeile `Zugriff` stammt aus den vom Fahrzeug angebotenen Property-Konfigurationen: R = Lesen, W = Schreiben. Das belegt weder eine bestimmte Wertbedeutung noch die Wirksamkeit einer Seitenwahl.

Für den nächsten Test: Musik → Verbrauch → Navigation → Verbrauch manuell wählen, auf jeder Seite kurz warten, dann etwa fünf Sekunden blinken. Danach den vollständigen Status einschließlich „Routing-Diagnose D1“ durch langes Drücken auf den Text kopieren. Der Vergleich zeigt, welche Ready-/Mittelbereichswerte bei einem erfolgreichen manuellen Wechsel anders sind als bei den beiden automatischen Versuchen.
