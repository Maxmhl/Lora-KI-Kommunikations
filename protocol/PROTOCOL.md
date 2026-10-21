# LKP v1 – LoRa-KI-Protokoll

Anwendungsprotokoll zwischen Android-App und Bridge. Es setzt auf **MeshCore-Direktnachrichten (Text)** auf.
Referenzimplementierungen: `bridge/lkbridge/codec.py` (Python) und `core/.../Lkp.kt` (Kotlin);
beide werden gegen `protocol/test-vectors.json` geprüft (`python protocol/gen_vectors.py` erzeugt sie neu).

## Warum Text?
MeshCore-Direktnachrichten tragen UTF-8-Text (max. 160 Byte inkl. Zeitstempel/Flags im verschlüsselten Block).
Binärdaten werden daher Base64-kodiert (+33 %). Die Pakete sind bereits Ende-zu-Ende verschlüsselt (MeshCore).

## Objekt (Envelope)
```
crc32(4, BE) | flags(1) | kind(1) | conv(1) | payload
```
* `crc32` über `flags|kind|conv|payload` (nach evtl. Kompression, wie gesendet)
* `flags` Bit0 = payload ist zlib-komprimiert (nur gesetzt, wenn es kleiner wird)
* `kind`: 1 Text · 2 Bild · 3 Datei · 4 KI-Antwort · 5 Status/Fehler · 6 Kontext zurücksetzen
* `conv`: Unterhaltungs-ID 0–255 (Server hält Verlauf pro Absender + conv)
* Bild/Datei-Payload: `len(name)(1) name len(mime)(1) mime daten`
* Anhänge werden vom Server **zwischengespeichert** und mit der nächsten Textnachricht derselben `conv` ausgewertet.

## Frames (je eine MeshCore-Textnachricht, nur ASCII)
Zahlen sind 12 Bit in 2 Zeichen des Alphabets `A–Z a–z 0–9 + /` (Index = Wert, 0–4095).

| Frame | Aufbau | Bedeutung |
|---|---|---|
| Daten | `~D` id(2) idx(2) total(2) base64 | Fragment `idx` von `total` |
| Poll  | `~P` id(2) idx(2) total(2) base64 | wie Daten, aber **ACK anfordern** (letztes Fragment eines Fensters) |
| ACK   | `~A` id(2) hi(2) [missing(2)]* | Empfänger hat alle Indizes `< hi` außer `missing` (max. 12) |
| Abbruch | `~X` id(2) | Empfänger lehnt ab (z. B. Objekt zu groß) |

* Fragment = 99 Nutzbytes = 132 Base64-Zeichen → Frame 140 Zeichen.
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
