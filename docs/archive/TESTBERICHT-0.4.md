# Testbericht 0.4.1 (2026-11-15)

## Nachtrag 0.4.1

- **Auf Deinem Gerät festgestellt (0.4.0):** Absturz bei „Test search“ (DuckDuckGo gewählt,
  ebenso bei „Automatic“) und bei Websuche im Chat; `NetworkOnMainThreadException` in
  `HtmlSearch.search` beim Lesen der Antwort. Die automatische Browser-Erkennung zeigte laut
  Deiner Meldung den richtigen Browser.
- **Behoben in 0.4.1:** Lesen und Auswerten im Hintergrund. Neuer Test H10 (Antwort wird nicht
  auf dem aufrufenden Thread gelesen); ohne die Korrektur schlägt er fehl, mit ihr besteht er.
- Warum die Tests in 0.4.0 das nicht fanden: Auf der JVM gibt es keine Android-Sperre für
  Netzzugriffe auf dem Hauptthread; H1–H4 prüften nur das Auswerten gespeicherter Seiten.
- `./gradlew clean :core:test :app:assembleDebug` → **109 Tests, 0 Fehler**. APK versionCode 6,
  versionName 0.4.1-debug, gleiches Zertifikat (SHA-256 `ea42c05c…f5f6c6`).
- Die Suche selbst (Ergebnisse von Deinem Netz aus) ist auf dem Gerät **noch nicht geprüft**.

# Testbericht 0.4.0 (2026-11-15)

Umgebung: Linux-Sandbox, JDK 21, Gradle 9.9.1. Kein Android-Gerät, kein Emulator, keine
Verbindung zum Tiiny. Internet vorhanden (für die gespeicherten Suchseiten).

Befehl: `./gradlew clean :core:test :app:assembleDebug` → BUILD SUCCESSFUL,
**108 Tests, 0 Fehler, 0 übersprungen**. Kotlin-Kompilierung der App ohne Fehler.

Ein erfolgreicher Build und bestandene Tests gegen simulierten Speicher, simulierten Server und
gespeicherte Suchseiten sind **kein** Nachweis für das Verhalten auf dem Pixel, mit dem Tiiny
oder mit den Suchmaschinen von Deinem Netz aus.

## 1. Automatisiert bestanden

Unverändert bzw. angepasst aus 0.3 (Details in [TESTBERICHT-0.3.md](TESTBERICHT-0.3.md)):
A Projekt-Speicher (A5 prüft jetzt Formatversion 4 als „neuer“), B SOUL.md, C Speicherverlust,
D Ad-hoc (D3 neu: Speichern ins Projekt nur auf ausdrücklichen Aufruf und ohne
Projektanweisungen), E Weitere Funktionen, F 0.3 (F2/F3 auf Version 3; F13 ersetzt durch einen
HtmlSearch-Test gegen einen Testserver), Protokoll, Speicherprüfung.

### G. Version 0.4 – Formate und Dokumente (11)
- G1 `project.json` v2 wird gelesen und beim Speichern auf v3 migriert
- G2 `chat.json` v1 wird gelesen und erst bei Änderung der Auswahl auf v2 migriert
- G3 `chat.json` v2 mit unbekanntem Feld gilt als beschädigt und bleibt unverändert
- G4 Dokumente anlegen; gleicher Name ersetzt nie (`name (2).ext`); Löschen einzeln
- G5 Projekt lässt sich erst löschen, wenn alle Dokumente gelöscht sind
- G6 Textgewinnung: UTF-8, windows-1252, DOCX (Absätze, Tabellen), nicht unterstützte Typen
- G7 Dokumente stehen als eigener Abschnitt in der einzigen system-Nachricht
- G8 aktive Dokumente gehen mit; die Auswahl wird beim ersten Senden in `chat.json` gespeichert
- G9 zu grosse oder fehlende Dokumente: nichts gespeichert, nichts gesendet
- G10 wirksame Dokumentgrenze (halbe Kontextgrösse / Projekt / App)
- G11 Dateinamen werden bereinigt und nummeriert

### H. Version 0.4 – Websuche und Markdown (9)
- H1–H4 echte Ergebnisseiten von DuckDuckGo, Bing, Brave und Yahoo (am 2026-11-15 aus der
  Sandbox abgerufen, ohne Scripts gespeichert) werden gelesen: Titel, Adresse, Auszug
- H5 Weiterleitungen werden aufgelöst; leere Seiten und Captcha-Seiten gelten als Fehler
- H6–H8 Markdown: Überschriften, Hervorhebungen, Code, Links, Listen (verschachtelt,
  nummeriert), Codeblock, Zitat, Trennlinie, Tabelle; Unbekanntes bleibt Text
- H9 unvollständige Auszeichnung (Streaming) verliert keinen Text

## 2. In meiner Umgebung manuell geprüft

- APK `tiiny-chat-0.4.0-debug.apk` mit `aapt2` geprüft: Paket `io.github.jenziner.nisaba.debug`,
  versionCode 5, versionName 0.4.0-debug, Bezeichnung „Tiiny Chat“. Berechtigungen
  unverändert: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK` (plus die interne
  AndroidX-Empfänger-Berechtigung). Neu nur die Manifest-Abfrage `<queries>` für Browser
  (keine Berechtigung).
- Signatur mit `apksigner` geprüft: gleiches Debug-Zertifikat wie 0.3
  (SHA-256 `ea42c05c…f5f6c6`) → Aktualisierung ohne Deinstallation möglich.
- Durchsicht des Codes der App-Schicht (Chat, Projekteinstellungen, Einstellungen, Diagnose).
- Die Oberfläche selbst wurde **nicht** angezeigt oder bedient (kein Emulator). PDF-Textgewinnung
  (`PdfRenderer`, Android-Teil) und Browser-Erkennung laufen nur auf Android und sind **nicht**
  getestet.

## 3. Auf Deinem Gerät geprüft (von Dir gemeldet)

- Etappe 1: Installation, Ordnerwahl, Speicherprüfung.
- 0.2.1: Modellanbindung.
- 0.3: läuft, „sehr guter erster Eindruck“ (Einzelprüfungen nicht gemeldet).

## 4. Auf Deinem Gerät noch zu prüfen (0.4)

- Aktualisierung über 0.3; Einstellungen, Schlüssel und Ordnerfreigabe bleiben erhalten
- Einstellungen → Web search: welche Suchmaschine „Automatic“ meldet (von Dir gemeldet: Browser richtig erkannt); „Test search“ mit jeder
  Suchmaschine über WLAN und Mobilfunk (Captchas?)
- Websuche im Chat, Quellen öffnen
- Ad-hoc: Speichern-Symbol → Projekt wählen → Chat liegt im Projekt; Papierkorb verwirft
- Projekt-Chat: Büroklammer → Upload (TXT, MD, PDF, DOCX), Dokument-Chips ein/aus,
  Anzeige „Active documents ≈ … tokens“, Antwort nutzt das Dokument
- Projekteinstellungen → Documents: Liste, Löschen einzeln, „Select all“, Dokumentgrenze;
  Projekt löschen erst nach dem Löschen der Dokumente (nur mit einem eigens angelegten
  Testprojekt)
- Markdown-Darstellung der Antworten (Listen, Tabellen, Code, Links)
- Bestehende Projekte: `project.json` bleibt v2, bis Projekteinstellungen gespeichert werden

## 5. Blockiert bzw. noch nicht umgesetzt

- Prüfungen mit dem echten Tiiny aus der Sandbox (nicht erreichbar).
- Suchmaschinen von Deinem Netz aus (Sandbox-IP ist nicht Deine IP; Captcha-Verhalten kann
  abweichen).
- Bilder (Vision), OCR, Entsperren des Tiiny (nicht umgesetzt).
- Automatisierte Oberflächentests der App-Schicht fehlen (nur Kompilierung und Durchsicht).
