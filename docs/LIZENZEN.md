# Lizenzen und Quellen

Eigener Code dieses Repos: Lizenz noch festzulegen (Inhaber: Repo-Eigentümer).

| Komponente | Lizenz | Verwendung |
|---|---|---|
| Project NOMAD (Crosstalk Solutions) | Apache-2.0 | läuft **nur auf dem Server**; nichts davon ist in der App oder im Repo enthalten |
| MapLibre GL JS 4.7.1 | BSD-3-Clause | Karten-Viewer (`app/src/main/assets/map/`) |
| PMTiles (Protomaps) 3.2.1 | BSD-3-Clause | Karten-Viewer |
| Kartendaten / Basiskarte | ODbL (© OpenStreetMap-Mitwirkende), Protomaps | werden vom Server geladen; Hinweis steht in der Karte |
| Schriften/Symbole des Kartenstils | SIL OFL / MIT | werden vom Server geladen |
| Pillow (HPND), pmtiles (BSD-3), mapbox-vector-tile (MIT), aiohttp (Apache-2.0) | siehe Projekte | nur in der Bridge auf dem Server |
| zstd-jni (Luben Karavelov) | BSD-2-Clause | Entpacken von ZIM-Clustern |
| XZ for Java (Tukaani) | Public Domain / 0BSD | Entpacken von ZIM-Clustern |
| Inhalte in ZIM-Dateien | je nach Inhalt, z. B. Wikipedia CC BY-SA 4.0 | werden vom Server geladen |

Der ZIM-Leser (`core/.../ZimFile.kt`) ist eine eigene Implementierung nach der OpenZIM-Spezifikation; weder libzim noch libkiwix
(GPL) noch kiwix-js sind enthalten. Zur Validierung dient `libzim` nur als Testreferenz (`core/src/test/resources/zim-golden.json`).
