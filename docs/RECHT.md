# Rechtlicher Rahmen (Deutschland, 868 MHz)

> Kein Rechtsrat. Die Werte unten sind der Stand bei Erstellung der App und **müssen vom Betreiber gegen die
> aktuelle Allgemeinzuteilung der Bundesnetzagentur („Funkanlagen geringer Reichweite“, SRD, 863–870 MHz) geprüft werden.**

| Bereich | Duty-Cycle | max. Leistung |
|---|---|---|
| 865,0–868,0 MHz | 1 % | 25 mW ERP |
| 868,0–868,6 MHz | 1 % | 25 mW ERP |
| 868,7–869,2 MHz | 0,1 % | 25 mW ERP |
| 869,4–869,65 MHz | **10 %** | 500 mW ERP |
| 869,7–870,0 MHz | 1 % | 25 mW ERP |

* Der Duty-Cycle gilt **je Sender** (Handy-Node, Server-Node, jeder Repeater) und bezieht sich auf eine Stunde.
* Die App liest die Frequenz vom Node, ordnet das Subband zu und begrenzt die eigene Sendezeit entsprechend
  (zusätzlich Sicherheitsfaktor, Standard 0,8). Unbekannte Frequenzen werden mit 0,1 % behandelt.
* **Sendeleistung:** Heltec V3 / WisMesh Tag senden je nach Einstellung bis 22 dBm (~160 mW). Das ist nur in
  869,4–869,65 MHz zulässig; in den 25-mW-Bändern (≤ 14 dBm ERP) muss die Leistung am Node gesenkt werden
  (Antennengewinn berücksichtigen). Die App warnt, prüft aber den Antennengewinn nicht.
* Der Node sendet selbst Adverts, ACKs und Weiterleitungen – diese Airtime kennt die App nur grob (Reserve über den Sicherheitsfaktor).
* Realistischer Durchsatz bei SF8/62,5 kHz/CR 4/8 und 10 %: ca. 10 Byte/s; bei 1 %: ca. 1 Byte/s.
