# Server einrichten: Ollama + volles NOMAD + Bridge/Gateway

Alles läuft auf **einem Server** (Heimserver, Mini-PC, …) im Intranet:

```
                       ┌────────────────────────── Server ───────────────────────────┐
Browser im Intranet ───┼─► NOMAD  :8080  (volle Oberfläche, wie von Crosstalk Solutions)│
                       │      │ nutzt                                                  │
Handy (WLAN/VPN) ──────┼─► Gateway :8765 ──► NOMAD (gleiche Oberfläche, mit Token)     │
                       │      └─► Offline-Downloads (Karten, Wikipedia)               │
Handy (LoRa) ──► Node ─┼─► Heltec V3 (USB) ─► Bridge ─► Ollama :11434 (oder NOMAD-KB)  │
                       └──────────────────────────────────────────────────────────────┘
```

* **Im Intranet** erreichst du NOMAD direkt unter `http://SERVER:8080` – unverändert.
* **In der App** (Tab „Nomad → Oberfläche“) siehst du *dieselbe* Oberfläche, aber über das **Gateway** mit Token. Das Gateway ist die
  einzige Schnittstelle der App; NOMAD selbst hat keine Anmeldung und sollte nicht ins Internet.
* **Inhalte werden gezielt angefragt, nicht gespeichert** (Standard, „Privatmodus“): Ohne WLAN fragt die App per **LoRa** genau den Artikel
  bzw. Kartenausschnitt an, den du willst; der Server schickt nur diese kleine Antwort. Im WLAN geht dasselbe schnell über das Gateway.
  Auf Wunsch (Privatmodus aus) kann die App Karten und Bibliotheken auch **einmal laden** und im Intervall aktualisieren.
  Education-Plattform (Kolibri) und Videos werden **nicht** angeboten (Filter `exclude`).

## 1. NOMAD installieren
Offizielle Anleitung: <https://github.com/Crosstalk-Solutions/project-nomad> (Debian/Ubuntu, Docker):
```bash
sudo apt-get update && sudo apt-get install -y curl
curl -fsSL https://raw.githubusercontent.com/Crosstalk-Solutions/project-nomad/refs/heads/main/install/install_nomad.sh -o install_nomad.sh
sudo bash install_nomad.sh
```
Danach `http://SERVER:8080` öffnen. Im **Easy-Setup** nur wählen, was du brauchst:
* ✅ **AI Assistant** (Ollama + Wissensdatenbank), **Offline-Karten**, **Information Library** (Wikipedia, Medizin, …), optional Notizen/CyberChef
* ❌ **Education Platform (Kolibri)** und Video-Inhalte einfach nicht installieren/herunterladen

Daten liegen standardmäßig in `/opt/project-nomad/storage` (`zim/`, `maps/`, `models/`).

## 2. Ollama gemeinsam nutzen
Du hast zwei Möglichkeiten:
* **NOMAD verwaltet Ollama** (Standard beim Installieren des AI Assistants) – die Bridge spricht dasselbe Ollama an
  (`[llm] base_url`, meist `http://127.0.0.1:11434`; falls der Port nicht auf dem Host veröffentlicht ist, im NOMAD-Container-Setup freigeben).
* **Eigener Ollama-Server**: in NOMAD unter *AI Assistant → Einstellungen* die URL des Ollama-Servers eintragen (Ollama dort mit `OLLAMA_HOST=0.0.0.0` starten).

Damit sehen NOMAD-Chat, App-Chat (LoRa) und Modellliste in der App dieselben Modelle.

## 3. Bridge + Gateway
```bash
cd bridge && python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
cp config.example.toml config.toml
python -c "import secrets; print(secrets.token_urlsafe(24))"     # -> Token für [gateway] token
```
In `config.toml`:
```toml
[nomad]
url = "http://127.0.0.1:8080"
storage = "/opt/project-nomad/storage"
kiwix_url = "http://127.0.0.1:8090"   # NOMADs Bibliothek (Kiwix) – für Artikel-Anfragen per LoRa/WLAN
knowledge_base = false     # Standard für LoRa-Chats; in der App je Chat umschaltbar

[gateway]
enabled = true
port = 8765
token = "…"                # das erzeugte Token
```
Autostart: `deploy/lkbridge.service` (systemd). Port 8765 in der Firewall nur für Intranet/VPN öffnen.

## 4. Handy anbinden
App → Tab **Nomad → Bibliothek**: Adresse `http://SERVER-IP:8765` und Token eintragen, „Speichern & testen“.
Danach Karten und Bibliotheken auswählen und laden; Intervall (täglich/wöchentlich/monatlich/nie) und „nur WLAN“ einstellen.

### Von unterwegs (außerhalb des Intranets)
Das Gateway spricht HTTP. Für Zugriff von außen **nicht** den Port ins Internet stellen, sondern:
* ein VPN (WireGuard/Tailscale) – die App nutzt dann einfach die VPN-Adresse, oder
* einen TLS-Reverse-Proxy (z. B. Caddy: `reverse_proxy 127.0.0.1:8765`) und in der App `https://…` eintragen.

## Was die App offline kann
| Funktion | Offline | Quelle |
|---|---|---|
| Karte (Region + Weltübersicht, GPS-Position) | ✅ | PMTiles + Kartenstil/Schriften aus NOMAD |
| Wikipedia & Co. lesen, Titel suchen | ✅ | ZIM-Dateien aus NOMAD (eigener Leser, ohne GPL-Code) |
| NOMAD-Oberfläche (Einstellungen, Bibliothek verwalten, …) | ❌ braucht WLAN/VPN | Gateway |
| **NOMAD-KI** (nativer Chat im Tab „KI“, streamt, mit Quellen) | ❌ braucht WLAN/VPN | Gateway → NOMAD → Ollama |
| KI-Chat über LoRa | ✅ ohne Internet | Bridge + Ollama |

Die Volltextsuche in ZIM-Dateien (Xapian-Index) ist nicht enthalten – gesucht wird nach Artikeltiteln.

## NOMAD-KI in der App (Tab „Nomad → KI“)
Natives Chat-Fenster, das NOMADs eigene KI-Schnittstelle nutzt – nicht die LoRa-Bridge:
* **Gleiche Sitzungen wie die NOMAD-Weboberfläche**: neue Chats erscheinen dort mit Titel, alte lassen sich in der App fortsetzen („Verlauf“).
* **Wissensdatenbank (RAG)**: der Schalter ändert die **serverweite** NOMAD-Einstellung `rag.enabled` – er gilt also auch in der Weboberfläche.
  Quellen der Antwort werden unter der Nachricht angezeigt.
* **Modelle**: Auswahl aus den in NOMAD/Ollama installierten Modellen (Bildfähige sind markiert).
* Antworten werden live gestreamt; „Stopp“ bricht die Erzeugung auf dem Server ab.
* Der Chat über **LoRa** (Tab „KI-Chat“) bleibt davon getrennt und funktioniert ohne WLAN.
Voraussetzung: In NOMAD ist der **AI Assistant** installiert und mindestens ein Modell geladen.

## NOMAD per LoRa: gezielte Anfragen
Ohne WLAN läuft NOMAD komplett als **Anfrage/Antwort**. Die App schickt eine winzige Anfrage (ein Funkpaket), der Server holt oder
rendert das Gewünschte und schickt nur das zurück – nichts wird auf dem Handy gespeichert.

| Anfrage | Antwort | Größe (ca.) |
|---|---|---|
| **Wiki: Suche** | bis zu 8 Titel (Titeltreffer + Volltext aus NOMADs Kiwix) | 100–300 Byte |
| **Wiki: Artikel öffnen** | zuerst die ersten ~1500 Zeichen (Text ohne Tabellen/Fußnoten), „Mehr lesen“ blättert weiter | 0,7–1 KB je Block |
| **Wiki: Kurzfassung (KI)** | Ollama fasst den Artikel in wenigen Sätzen zusammen | 300–600 Byte |
| **Karte: Ausschnitt um deine Position, wählbarer Radius** (250 m … 25 km) | winziges Bild (WebP/PNG): Straßen, Wasser, Grün, Ortsnamen, **deine Position als Punkt**, dazu eine Liste der Orte im Ausschnitt | 1,5 KB (96 px) · 2,8 KB (128 px) · 4,6 KB (160 px) · 11 KB (256 px) |
| **Karte: Detailkarte** | Ort im Bild antippen oder aus der Ortsliste wählen → Karte mit kleinerem Radius um diesen Punkt (deine Position bleibt eingezeichnet) | wie oben |
| **Karte: N/S/W/O, Radius ±, vorherige Karte** | weitere Ausschnitte; bereits gesehene kommen aus dem Arbeitsspeicher | wie oben |
| **KI-Chat mit Wissensdatenbank** | Antwort + kompakte Quellenzeile („Quellen: …“) | wenige 100 Byte |

Die App zeigt **vor** jeder Anfrage die geschätzte Wartezeit (Sendepausen!). Bei 10 % Duty-Cycle sind das grob 2–3 Minuten je KB.
Eine Karte mit 160 px dauert also etwa 10–15 Minuten, ein Artikelanfang 2–3 Minuten. Im WLAN kommt dieselbe Antwort sofort.

**Grenzen:** Die Karte ist ein vereinfachtes Bild (kein Vektor-Zoom, kein Routing, keine Straßennamen). Die Volltextsuche nutzt Kiwix, soweit
die ZIM-Datei einen Suchindex enthält. Bilder aus Artikeln werden nicht übertragen.

Server-Voraussetzungen: `pip install -r requirements.txt` (u. a. Pillow, pmtiles, mapbox-vector-tile), laufendes Kiwix (NOMAD „Information Library“,
Port 8090) und die Karten-Regionen im NOMAD-Speicher.

## Privatmodus („Nichts auf dem Handy speichern“)
Standardmäßig an (Tab *Nomad → Server*). Dann liegen Chats, Anhänge, Wikipedia-Artikel und Kartenausschnitte **nur im Arbeitsspeicher** und sind
nach dem Beenden der App weg; es gibt keine Offline-Bibliothek; der Browser-Zwischenspeicher der NOMAD-Oberfläche wird gelöscht; Benachrichtigungen
zeigen keinen Inhalt. Beim Einschalten werden bereits gespeicherte Chats und Offline-Inhalte gelöscht.
Gespeichert bleiben **nur Verbindungseinstellungen** und das **Sendezeit-Protokoll** (Zeitpunkte und Dauer, kein Inhalt) – es sorgt dafür, dass die
gesetzlichen Sendepausen auch nach einem App-Neustart eingehalten werden.
Nicht in der Hand der App: der MeshCore-Node und der Server können Nachrichten ihrerseits speichern.

## Karte: Position, Radius, Detailkarten
1. **Position**: *Node-GPS* (vom Node gemeldete Position – GPS am Node muss aktiv sein und einen Fix haben; die App fragt den Node dafür neu ab),
   *Handy-GPS* oder *manuell*. Sie geht (verschlüsselt) mit der Kartenanfrage zum Server, der sie in die Karte einzeichnet.
2. **Radius** (Meter vom Mittelpunkt bis zum Bildrand) und Detailgrad (Bildgröße) wählen – der Server skaliert die Vektorkarte stufenlos.
3. **Ort antippen** oder **aus der Ortsliste wählen** → Detailkarte mit nächstkleinerem Radius um diesen Punkt. Vorherige Karten bleiben für „Zurück“ im Arbeitsspeicher.

## Kompaktmodus (experimentell): schneller für öffentliche Inhalte
Verschlüsselte MeshCore-Textnachrichten tragen nur ~118 Nutzbyte je Funkpaket (Base91-Kodierung, 16-Byte-AES-Blöcke, Empfänger-/MAC-Kopf).
Wikipedia-Text und Kartenbilder sind **öffentliche Inhalte** – dafür kann der Server stattdessen **Rohdatenpakete** (`CMD_SEND_RAW_DATA`) senden:
148 Nutzbyte je Paket, ohne Kodierung und Padding → **rund 20 % weniger Funkzeit**.

* **Unverschlüsselt, aber beglaubigt**: jedes Paket trägt einen 4-Byte-HMAC mit dem gemeinsamen Geheimnis (dein Gateway-Token, mind. 16 Zeichen).
  Mithörende können den Inhalt lesen, aber keine gefälschten Antworten einschleusen (verworfen, siehe `LkRaw`/`rawframe.py`).
* **Bleibt verschlüsselt**: Anfragen (inkl. deiner Position), KI-Chats und alle ACKs.
* **Karten separat**: Ein Kartenausschnitt um deine Position verrät Mithörenden, wo du bist – deshalb eigener Schalter, standardmäßig aus.
* **Voraussetzungen/Grenzen**: MeshCore-Firmware mit Rohdaten-Unterstützung auf beiden Nodes; Rohdaten gehen nur **direkt** (Nachbarn) oder über einen
  **bekannten Pfad** (kein Flooding). Fehlt Unterstützung/Pfad oder kommen keine ACKs, wechselt der Server **automatisch auf den verschlüsselten Weg**
  und pausiert den Kompaktmodus für eine Stunde, wenn es zweimal hintereinander nicht klappt.
* **Nicht auf echter Hardware getestet.** Rahmenformat, Echtheitsprüfung, Aufteilung, Rückfall und Duty-Cycle sind getestet (Python + Kotlin,
  byte-identisch); ob deine Firmware die Rohdatenpakete wie erwartet liefert, zeigt erst ein Test mit den Nodes.
* Beide Seiten: `[gateway] token` in der Bridge = Token in der App; `raw_enabled = false` in der Bridge schaltet den Modus serverseitig ab.
