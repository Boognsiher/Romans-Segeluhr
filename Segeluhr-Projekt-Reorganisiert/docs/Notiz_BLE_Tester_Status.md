# Notiz: Status BLE-Tester (XIAO ESP32-S3) nach Erhalt der echten Uhren

> Diese Notiz ist zum Einpflegen in PROJEKT_STATUS.md gedacht — ich habe
> keinen Zugriff auf die Datei selbst, daher als eigenständige Datei.

## Entscheidung (05.08.2026)
Mit T-Watch Ultra + T-Watch S3 jetzt beide real vorhanden, ändert sich die
Rolle des BLE-Testers (`segeluhr_ble_tester_v2.ino` auf dem XIAO ESP32-S3):

- **Vorher:** primäres Testgerät, simulierte die Central-Rolle der Uhr,
  bevor echte Watch-Hardware da war
- **Jetzt:** herabgestuft zu einem **optionalen Debug-Werkzeug**, nicht mehr
  aktiv weiterentwickelter Haupt-Testweg

## Warum nicht komplett entfernen
- Gutes Diagnose-Tool, um bei Problemen zu isolieren, ob ein Bug im
  App-seitigen BLE-Protokoll liegt oder in der komplexen 6-Screen-Firmware
  der Ultra (der Tester ist bewusst simpel gehalten)
- Schnellere Iteration bei reinen Protokoll-Änderungen (neuer `CMD_*`-Wert),
  ohne die komplette Ultra-Firmware neu flashen und durch Screens navigieren
  zu müssen
- Kostet nichts, das Board einfach griffbereit zu behalten

## Geänderter Umfang der "on the horizon"-Punkte
Die drei geplanten neuen Tester-Demo-Screens (Windtrend, Racemode-Navigation,
Line-Bias) werden **zurückgestellt** — diese Features werden ab jetzt direkt
auf der echten Ultra-Hardware entwickelt/getestet, nicht mehr zuerst auf dem
Tester simuliert.

## Für PROJEKT_STATUS.md
- Abschnitt zum BLE-Tester: Status auf "optionales Debug-Tool" ändern
- "On the horizon": Eintrag zu den drei neuen Tester-Screens entfernen oder
  nach "zurückgestellt/nicht priorisiert" verschieben
