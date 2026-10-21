# LKP v2 – LoRa-KI-Protokoll

Anwendungsprotokoll zwischen Android-App und Bridge. Es setzt auf **MeshCore-Direktnachrichten (Text)** auf.
**Version 2 ist nicht kompatibel zu v1** (anderes Kodierungs- und Fragmentformat): App und Bridge gemeinsam aktualisieren.
Referenzimplementierungen: `bridge/lkbridge/codec.py` (Python) und `core/.../Lkp.kt` (Kotlin);
beide werden gegen `protocol/test-vectors.json` geprüft (`python protocol/gen_vectors.py` erzeugt sie neu).

## Warum Text?
MeshCore-Direktnachrichten tragen UTF-8-Text (max. 160 Byte inkl. Zeitstempel/Flags im verschlüsselten Block).
Binärdaten werden daher mit **basE91** kodiert (~23 % Overhead statt 33 % bei Base64; nur druckbare ASCII-Zeichen). Die Pakete sind bereits Ende-zu-Ende verschlüsselt (MeshCore).

## Objekt (Envelope)
```
crc32(4, BE) | flags(1) | kind(1) | conv(1) | payload
```
* `crc32` über `flags|kind|conv|payload` (nach evtl. Kompression, wie gesendet)
* `flags`: `0x02` = Nutzlast mit rohem Deflate (ohne Header) komprimiert; `0x04` zusätzlich mit dem eingebauten Wörterbuch
  (`dictionary.txt`, byte-identisch in Bridge und App, per SHA-256 in den Testvektoren geprüft). Der Sender probiert unkomprimiert / Deflate /
  Deflate+Wörterbuch und nimmt das kleinste. Das Wörterbuch spart v. a. bei kurzen deutschen Texten (typisch 20–40 %).
* `kind`: 1 Text · 2 Bild · 3 Datei · 4 KI-Antwort · 5 Status/Fehler · 6 Kontext zurücksetzen · 7 Modellliste anfordern · 8 Modellliste · 9 Modell wählen · 10 Optionen (`kb=1`) · 11 NOMAD-Anfrage · 12 NOMAD-Antwort
* `conv`: Unterhaltungs-ID 0–255 (Server hält Verlauf pro Absender + conv)
* Bild/Datei-Payload: `len(name)(1) name len(mime)(1) mime daten`
* Anhänge werden vom Server **zwischengespeichert** und mit der nächsten Textnachricht derselben `conv` ausgewertet.

## Modellauswahl
* `7` (App → Server, leere Nutzlast): Server antwortet mit `8` (Zeilen `name<TAB>flags`, Flag `v` = versteht Bilder).
* `9` (App → Server, Nutzlast = Modellname): Modell für diese `conv`; leer = Server-Standard. Die Bridge prüft den Namen
  gegen ihre Liste (sonst Statusmeldung `5`) und speichert die Wahl dauerhaft pro `(Absender, conv)`.
* Bilder: hat das gewählte Modell kein Bildverständnis, nutzt die Bridge `vision_model` (falls konfiguriert), sonst werden sie mit Hinweis ignoriert.

## NOMAD-Anfragen (Kind 11 → 12)
`conv` ist hier die Anfragenummer (0–255) und wird in der Antwort wiederholt. Nutzlast der Anfrage: Tab-getrennter Text, z. B.
`article<TAB>buch<TAB>pfad<TAB>offset<TAB>länge<TAB>text|summary`, `search`, `books`, `mapinfo`, `map<TAB>lat<TAB>lon<TAB>radius_m<TAB>größe<TAB>farben[<TAB>marklat<TAB>marklon]`.
Antwort: erste Zeile `ok<TAB>art…` oder `error<TAB>meldung`, danach Text bzw. Bilddaten. Karte: Kopf `ok map fmt region zoom größe minlon minlat maxlon maxlat m/px bildbytes`, danach Bild und Ortsliste (`name lat lon art` je Zeile). Dasselbe Format nutzt das
WLAN-Gateway (`POST /lk/nomad`). Details: `bridge/lkbridge/nomad_ops.py`, `core/.../NomadRequest.kt`.

## LKR – Rohdatenrahmen (Server → Handy, optional)
Für öffentliche Antworten (Kind 12) per `CMD_SEND_RAW_DATA`, unverschlüsselt aber beglaubigt:
`0xA7 | (Version<<4 | Poll) | msgId(2) | idx(2) | total(2) | Daten (≤ 148) | HMAC-SHA256(Schlüssel, alles davor)[:4]`, Schlüssel = `SHA256("lkr1|" + Geheimnis)`.
Gleiche Fensterlogik und Bestätigungen wie bei LKP (die ACKs gehen als Textnachricht zurück). Funkpaket = 2 + Pfadlänge + Rahmen Byte.
Aktivierung je Handy über Kind 10 (`raw=1` Wiki-Text, `rawmap=1` Karten). Rückfall auf LKP-Text bei Fehlern. Referenz: `bridge/lkbridge/rawframe.py`, `core/.../LkRaw.kt`.

## Frames (je eine MeshCore-Textnachricht, nur ASCII)
Zahlen sind 12 Bit in 2 Zeichen des Alphabets `A–Z a–z 0–9 + /` (Index = Wert, 0–4095).

| Frame | Aufbau | Bedeutung |
|---|---|---|
| Daten | `~D` id(2) idx(2) total(2) basE91 | Fragment `idx` von `total` |
| Poll  | `~P` id(2) idx(2) total(2) basE91 | wie Daten, aber **ACK anfordern** (letztes Fragment eines Fensters) |
| ACK   | `~A` id(2) hi(2) [missing(2)]* | Empfänger hat alle Indizes `< hi` außer `missing` (max. 12) |
| Abbruch | `~X` id(2) | Empfänger lehnt ab (z. B. Objekt zu groß) |

* Fragment = 118 Nutzbytes → höchstens 146 basE91-Zeichen → Frame höchstens **154 Zeichen**. Grund: MeshCore verschlüsselt in 16-Byte-Blöcken
  (`4 Zeitstempel + 1 Flags + Text + 1 Terminator`); 154 Zeichen füllen genau 10 Blöcke. Jedes Zeichen mehr kostet einen weiteren Block Airtime.
* `id` wählt der Sender (12 Bit, pro Sender eindeutig). Nachrichten ohne `~`-Präfix behandelt die Bridge als Klartext-Prompt (conv 0).
* Passt die Lückenliste nicht in einen ACK (> 12), wird `hi` auf die erste ausgelassene Lücke gekürzt – der Sender nimmt so nie Fragmente als empfangen an, die fehlen.

## Ablauf (Selective Repeat mit Fenster 6)
1. Sender schickt bis zu 6 Fragmente, das letzte als Poll.
2. Empfänger antwortet auf Poll (und bei Vollständigkeit) mit ACK.
3. Sender berechnet `empfangen = [0,hi) \ missing` und sendet alles Fehlende, dann die nächsten neuen.
4. Kein ACK innerhalb `ack_timeout`: letztes Fragment als Poll wiederholen (max. N Mal), dann Abbruch.
5. Wiederaufnahme: Sender sendet zuerst nur das letzte Fragment als Poll (Probe) und lernt so den Empfängerstand.
6. Abgeschlossene `(peer,id)` werden gemerkt; Duplikate lösen nur ein erneutes ACK aus.

## Duty-Cycle
Jedes gesendete Paket – auch ACKs – geht durch den `DutyCycleLimiter` des sendenden Nodes:
* **Pausenregel:** nach Airtime `ta` mindestens `ta / (duty · margin)` bis zum nächsten Sendebeginn.
* **Gleitendes 1-h-Fenster:** Summe der Airtime ≤ `duty · margin · 3600 s`.
* Airtime nach Semtech AN1200.13 (Explicit Header, CRC an, Preamble aus Node), Paketgröße geschätzt aus MeshCore-Header + AES-Blöcken.
