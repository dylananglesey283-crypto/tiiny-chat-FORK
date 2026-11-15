# Testbericht Etappe 2 (2026-11-14, Stand 0.2.1)

Umgebung: Linux-Sandbox, JDK 21, Gradle 9.9.1. Kein Android-Gerät, kein Emulator, keine
Verbindung zum Tiiny.

Befehl: `./gradlew clean :core:test :app:assembleDebug` → BUILD SUCCESSFUL,
**72 Tests, 0 Fehler, 0 übersprungen**.

Ein erfolgreicher Build und bestandene Tests gegen simulierten Speicher und simulierten Server
sind **kein** Nachweis für das Verhalten auf dem Pixel oder mit dem Tiiny.

## 1. Automatisiert bestanden

Gegen `FakeStorage` (Speicher im Arbeitsspeicher mit Fehlereinspielung) bzw. `MockWebServer`.

### A. Projekt-Speicher (19)
- A1 nicht eingerichteter Ordner meldet, ob er leer ist; Kennungsdatei wird nie ersetzt;
  Speicherkennung bleibt nach Neustart gleich
- A2 Projekt schreiben und erneut lesen; gleicher Projektname ergibt eigenen Ordner
- A3 fehlende Berechtigung und nicht erreichbarer Speicher sind unterscheidbar
- A4 kein stiller Ersatzspeicher bei fehlendem Speicher
- A5 beschädigte `nisaba-store.json`, `project.json`, `history.json`, unbekannte Felder und
  neuere Formatversion: gemeldet, nicht gelesen, nicht überschrieben
- A6 sicheres Ersetzen: alte Datei bleibt bei Fehler unverändert; Fehler beim Zurücklesen
  erkannt; unterbrochenes Ersetzen wird beim Lesen ohne Datenverlust abgeschlossen; unklare
  Zwischendateien führen zu keiner Aktion; Konflikt bei zwischenzeitlicher Änderung
- A7 Dateiformat stabil und menschenlesbar
- A8 Chat löschen entfernt genau den Chat-Ordner und prüft die Identität

### B. SOUL.md (15)
- B1 Snapshot erst beim ersten Senden; B2 Änderung vor dem ersten Senden wirkt;
  B3 Änderung danach nicht; B4 neuer Chat verwendet geänderten Inhalt
- B5 fehlende und leere SOUL.md unterschieden; B6 Lesefehler und ungültiges UTF-8 sind keine
  fehlende Datei und blockieren das Senden; Snapshot nicht speicherbar → keine Modellanfrage
- B7 Neustart erhält den Snapshot; B8 Chat ohne Anweisungen bleibt ohne; B9 Snapshot nie
  überschrieben; B10 Editor speichert nicht nach Lesefehler oder über fremde Änderung;
  B11 App-Anweisungen und Snapshot bleiben getrennte Teile

### C. Speicherverlust (8)
- C1–C4 laufende Antwort wird angezeigt, als ungespeichert markiert, Senden gesperrt, nach
  Wiederverbindung automatisch gespeichert
- C5 falscher Speicher, andere Chat-Identität und geänderte Chat-Datei: kein Schreiben
- C6 bestätigungslos gelandeter Schreibvorgang und Wiederholung nach Modellfehler erzeugen
  keine Duplikate
- C7 Nutzernachricht nicht speicherbar → keine Modellanfrage, danach „Antwort anfordern"
- C8 unvollständige Antwort wird als unvollständig gespeichert

### D. Ad-hoc (6)
- D1 keine Ablage; D2 keine Wiederherstellung; D3 kein Speichern oder Verschieben;
  D4 Verfügbarwerden eines Speichers ändert nichts; D5 Verwerfen braucht Bestätigung;
  D6 keine Projektanweisungen

### E. Weitere Funktionen (7)
- E1 Titel lokal ohne zusätzliche Modellanfrage; Kürzung an Wortgrenze
- E2 manuelle Titeländerung bleibt nach Neuöffnen und weiterem Senden erhalten
- E3 Modell- und Speicherfehler unterscheidbar
- E4 keine Zugangsdaten in Projektdateien (Header-Versand siehe Protokolltests)
- E5 antwortendes Modell pro Antwort gespeichert; E6 Doppelsenden abgewiesen

### Protokoll (13)
- Modellliste mit Bearer-Header; leere Liste; 401 → Authentifizierung; 502 → Gateway;
  Verbindungsfehler; 504 → Zeitüberschreitung; 404 → Modell nicht verfügbar
- Antwort ohne Streaming im auf Deinem Tiiny beobachteten Format
- Streaming mit `reasoning_content`, usage-Teilstück ohne `choices`, `[DONE]`; Stream ohne
  Abschluss und `finish_reason: length` → unvollständig; Fehlerobjekt im Stream → Kontextgrenze
- Anfrage-Aufbau: Reihenfolge, nur vollständige Antworten, keine Kürzung; Kontextwarnung

### Speicherprüfung (4)
- besteht und lässt vorhandene Dateien unverändert; belegter Prüfordnername → Abbruch ohne
  Änderung; nicht erreichbarer Speicher ohne Schreibzugriff; Namensänderung durch Anbieter erkannt

## 2. In meiner Umgebung manuell geprüft

- APK `nisaba-0.2.1-etappe2-debug.apk` gebaut; mit `aapt2` geprüft: Paket
  `io.github.jenziner.nisaba.debug`, versionCode 3, minSdk 34, targetSdk 37, Berechtigungen
  nur `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK` (und die von AndroidX erzeugte interne
  Empfänger-Berechtigung); kein Speicherzugriff. Debug-Signatur wie Etappe 1 (siehe unten).
- Kompiliert ohne Fehler. Die Oberfläche wurde **nicht** angezeigt oder bedient (kein Emulator).

## 3. Auf Deinem Gerät geprüft (von Dir gemeldet, Etappe 1)

- Installation unter GrapheneOS, Pixel 8 Pro, API 37
- Ordnerwahl mit dauerhafter Freigabe (`Documents/TestNisaba`)
- Speicherprüfung auf dem echten Speicheranbieter: alle Schritte OK, „Gesamt: bestanden"
  (u. a. sicheres Ersetzen 1057 ms)

## 3b. Auf Deinem Gerät geprüft (Etappe 2, Version 0.2.0)

- Installation als Aktualisierung über Etappe 1
- Ohne GrapheneOS-Berechtigung „Netzwerk“: keine Netze, `socket failed: EPERM`
- Mit Berechtigung: Android meldet USB als „Ethernet (usb0)“, 172.19.178.110/30
- `GET /v1/models` aus Nisaba: Zeitüberschreitung beim Verbindungsaufbau über USB
  (172.19.178.109:80) und über WLAN (192.168.x.y:80); im Browser Vanadium über beide
  Wege Antwort `missing_token` → Problem liegt bei der App (vermutlich Android-17-Sperre des
  lokalen Netzes; in 0.2.1 behoben, auf dem Gerät noch nicht geprüft)

## 4. Auf Deinem Gerät noch zu prüfen (Etappe 2)

- 0.2.1: Anfrage der Berechtigung „Lokales Netz“ und danach `GET /v1/models`

- Aktualisierung über Etappe 1, Freigabe bleibt erhalten
- Systemfarben und Dunkelmodus
- `GET /v1/models` über USB im Modus „USB-Verbindung": wird das USB-Netz gemeldet und gewählt?
- Projekt-Chat: Senden, Antwort, Speichern, Neuöffnen; Titel ändern; Chat löschen
- SOUL-Editor: anlegen, ändern, Hinweis, Abbrechen mit Rückfrage; Snapshot beim ersten Senden
- Speicherverlust und Wiederverbinden auf dem Gerät (z. B. Freigabe entziehen)
- Ad-hoc-Chat: Verwerfen mit Bestätigung; nichts im Ordner
- Stopp während einer Antwort; „Antwort anfordern" nach Fehler
- Streaming (Schalter) und Anzeige der Denkausgabe
- HTTP 502 nach Neustart des Tiiny: verständliche Meldung

## 5. Blockiert bzw. noch nicht umgesetzt

- Alle Prüfungen mit dem echten Tiiny aus der Sandbox (nicht erreichbar).
- Entsperren des Tiiny (nicht umgesetzt; Ablauf nur aus Renderer-Code abgeleitet).
- Anhänge, Markdown-Darstellung (nicht umgesetzt).
- E „Vault-Schutz bei Projektlöschungen", „Mikrofon nur per Tastendruck": entfallen in V1.
- E „Zugangsdaten nicht in Logs": Die App schreibt keine Protokolle mit Schlüssel; ein
  automatisierter Test der App-Schicht fehlt (nur Durchsicht des Codes).
