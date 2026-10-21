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
* **Große Inhalte** (Karten, Wikipedia) lädt die App **einmal** über das Gateway aufs Handy und prüft nur im eingestellten Intervall
  auf neue Versionen. Education-Plattform (Kolibri) und Videos werden **nicht** angeboten (Filter `exclude`).

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
knowledge_base = true      # optional: LoRa-Chat nutzt NOMADs Wissensdatenbank (RAG)

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
