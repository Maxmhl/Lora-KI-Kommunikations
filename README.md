# LoRa-KI-Kommunikation

Android-App, die per **Bluetooth** mit einem MeshCore-Node (z. B. RAK WisMesh Tag) spricht und über das
**LoRa-Mesh** mit einem LLM (Ollama) auf einem Heimserver chattet – wie eine normale KI-App, inklusive
Bilder und Dateien. Große Objekte werden fragmentiert und unter Einhaltung des **deutschen Duty-Cycle-Limits**
(868 MHz) mit Pausen nacheinander gesendet.

```
[Handy-App] --BLE--> [WisMesh Tag] ~~LoRa~~> [Heltec V3] --USB--> [Bridge (Python)] --HTTP--> [Ollama]
```

| Ordner | Inhalt |
|---|---|
| `app/` | Android-App (Kotlin, Jetpack Compose): Chat-UI, BLE, Anhänge, Sendezeit-Budget |
| `core/` | Reiner Kotlin-Kern (LKP-Protokoll, Airtime, Duty-Cycle, Fragmentierung, Companion-Frames) – auf der JVM testbar |
| `bridge/` | Python-Dienst am Server-Node: Reassembly, Ollama, Dateiextraktion (TXT/PDF/DOCX/…) |
| `protocol/` | Protokollspezifikation + gemeinsame Testvektoren (Python ⇄ Kotlin) |
| `docs/` | Rechtlicher Rahmen |

## Einrichtung

### 1. Nodes
* Beide Nodes mit der MeshCore-**Companion**-Firmware (USB/BLE) flashen, **gleiche Funkparameter** (z. B. 869,618 MHz, SF8, BW 62,5 kHz, CR 4/8).
* Sendeleistung an die Vorgaben anpassen (siehe `docs/RECHT.md`).
* Die Nodes müssen sich per Advert kennen (in der App „Advert senden“; auf dem Server-Node muss Auto-Add für Kontakte aktiv sein).

### 2. Server / Bridge
```bash
cd bridge
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp config.example.toml config.toml      # Port, Modell, allowed_peers eintragen
python -m lkbridge -c config.toml -v
```
Unbekannte Absender werden mit ihrem Schlüssel-Präfix ins Log geschrieben – dieses in `allowed_peers` eintragen.
Vision-Modell später: `vision_model = "llava"` in der Config setzen.

### 3. App
Android Studio öffnen (Ordner dieses Repos) oder `./gradlew :app:assembleDebug`. In den Einstellungen:
Node suchen und koppeln (PIN 123456) → Server-Kontakt wählen → fertig.

## Tests
```bash
cd bridge && python -m pytest          # Protokoll, Limiter, Übertragung mit Paketverlust, Bridge
./gradlew :core:test                   # Kotlin-Kern inkl. Python-Testvektoren
./gradlew :app:lintDebug :app:assembleDebug
```

## Grenzen / Hinweise
* Durchsatz ist physikalisch gering (siehe `docs/RECHT.md`): ein 10-KB-Bild braucht bei 10 % Duty-Cycle ~15–20 min, bei 1 % ~3 h.
  Bilder werden deshalb auf ≤ 384 px skaliert (WebP), Dateien sind standardmäßig auf 60 KB begrenzt (einstellbar).
* **Modellwahl:** In der App oben („Modell: … ▾“) pro Unterhaltung wählbar; die Liste holt die App einmalig vom Server (Ollama `/api/tags`).
* Antworten der KI kommen als fertiger, komprimierter Block (kein Token-Streaming); die Bridge bittet das Modell um kurze Antworten.
* Die Companion-Frame-Layouts (`core/.../Companion.kt`) basieren auf der Referenzbibliothek `meshcore_py` und sind
  gegen die eigene Firmware-Version zu verifizieren. Die BLE-Anbindung wurde **nicht auf echter Hardware** getestet.
