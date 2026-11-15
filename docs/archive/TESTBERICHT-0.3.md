# Testbericht 0.3.0 (2026-11-15)

Umgebung: Linux-Sandbox, JDK 21, Gradle 9.9.1. Kein Android-Gerät, kein Emulator, keine
Verbindung zum Tiiny und zu keiner SearXNG-Instanz.

Befehl: `./gradlew clean :core:test :app:assembleDebug` → BUILD SUCCESSFUL,
**88 Tests, 0 Fehler, 0 übersprungen**. Kotlin-Kompilierung der App ohne Warnungen.

Ein erfolgreicher Build und bestandene Tests gegen simulierten Speicher und simulierten Server
sind **kein** Nachweis für das Verhalten auf dem Pixel, mit dem Tiiny oder mit SearXNG.

## 1. Automatisiert bestanden

Unverändert aus Etappe 2 (Details in [TESTBERICHT-ETAPPE2.md](TESTBERICHT-ETAPPE2.md)), an 0.3
angepasst (englische Meldungen, Formatversion 2, Vorrangsatz):
A Projekt-Speicher (19), B SOUL.md (15), C Speicherverlust (8), D Ad-hoc (6),
E Weitere Funktionen (7), Protokoll (13), Speicherprüfung (4).

### F. Version 0.3 (16)
- F1 `project.json` v1 wird gelesen und nicht umgeschrieben; F2 neue Projekte als v2
- F3 Einstellungen speichern migriert v1 auf v2, danach lesbar
- F4 v2 mit unbekanntem Feld gilt als beschädigt und bleibt unverändert
- F5 Einstellungen nach fremder Änderung werden nicht gespeichert (Konflikt)
- F6 Projekt mit Chats wird nach Prüfung gelöscht
- F7 Dateien in `files/` oder fremde Einträge sperren das Löschen (nichts wird entfernt)
- F8 Löschen prüft die Projektidentität
- F9 Vorrangsatz steht zwischen App- und Projektanweisungen
- F10 Reasoning-Felder werden nur gesendet, wenn gesetzt
- F11 Denk-Fähigkeiten aus der Modellliste (Objekt und Wahrheitswert)
- F12 Reasoning wird nie geraten (ohne Fähigkeit keine Felder, Hinweis)
- F13 SearXNG-Antwort wird gelesen; HTTP 403 mit Hinweis auf `settings.yml`
- F14 Websuche fliesst nur in die Anfrage ein, nicht in die Ablage
- F15 fehlgeschlagene Websuche: keine Modellanfrage, Nachricht bleibt gespeichert, Wiederholung
- F16 Suchbegriff wird gekürzt (400 Zeichen)

## 2. In meiner Umgebung manuell geprüft

- APK `tiiny-chat-0.3.0-debug.apk` mit `aapt2` geprüft: Paket `io.github.jenziner.nisaba.debug`,
  versionCode 4, versionName 0.3.0-debug, targetSdk 37, Bezeichnung „Tiiny Chat“, adaptives
  Symbol vorhanden. Berechtigungen unverändert: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_LOCAL_NETWORK` (plus die interne AndroidX-Empfänger-Berechtigung).
- Signatur mit `apksigner` geprüft: gleiches Debug-Zertifikat wie 0.2.1
  (SHA-256 `ea42c05c…f5f6c6`) → Aktualisierung ohne Deinstallation möglich.
- Symbol-Vordergrund und Wortmarke als Bild gerendert und angesehen: Schrift mittig, innerhalb
  der Sicherheitszone.
- Die Oberfläche selbst wurde **nicht** angezeigt oder bedient (kein Emulator).

## 3. Auf Deinem Gerät geprüft (von Dir gemeldet)

- Etappe 1: Installation, Ordnerwahl, Speicherprüfung bestanden.
- 0.2.1: Modellanbindung funktioniert („Jetzt geht es wunderbar“); gelegentliche
  Verbindungsabbrüche mit nötigem manuellem Aktualisieren (wie in der Tiiny-Software).

## 4. Auf Deinem Gerät noch zu prüfen (0.3)

- Aktualisierung über 0.2.1; Freigaben und Schlüssel bleiben erhalten
- Start im Ad-hoc-Chat, Senden, „Neu beginnen“ mit Bestätigung
- Seitenmenü: Projektliste, neues Projekt, Zahnrad → Projekteinstellungen
- Projekteinstellungen: Reasoning und Websuche speichern (`project.json` wird v2)
- Projekt löschen: Sperre bei Inhalten in `files/`; Löschen eines Testprojekts
  (nur ein eigens angelegtes Testprojekt verwenden)
- Reasoning ein/aus am Tiiny: wirkt `thinking_enabled` über Port 80?
- SearXNG: „Test“ in den Einstellungen, Websuche im Chat, Quellen öffnen
- Verbindungsabbrüche: treten sie noch auf? Verbindungsprotokoll kopieren
- Symbol, Wortmarke, Dunkelmodus

## 5. Blockiert bzw. noch nicht umgesetzt

- Prüfungen mit dem echten Tiiny und mit SearXNG aus der Sandbox (nicht erreichbar).
- Entsperren des Tiiny, Anhänge, Markdown-Darstellung (nicht umgesetzt).
- Automatisierte Oberflächentests der App-Schicht fehlen (nur Kompilierung und Durchsicht).
