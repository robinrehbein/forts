# Bollwerk – Stil-Bibel v1 (verbindlich für Mockups, Spielplatz und später Android)

Gewählter Stil: **texturiert-industriell**, nah an der Anmutung von Forts, aber eigene Formen.
Grundsatz: Jedes Objekt muss auf einem 6-Zoll-Display bei Standard-Zoom in 0,3 s erkennbar sein.
Detail dient der Lesbarkeit, nicht umgekehrt.

---

## 1. Maßstab und Zahlen (eine Wahrheit, überall gleich)

Weltmaßstab: 1 Welt-Einheit = 1 Meter. Standard-Zoom Spiel: 1 m ≈ 24 dp. Max. Balkenlänge **6 m**.

### Karten
| Karte | Breite | Aufbau |
|---|---|---|
| Schlucht | 120 m | 2 Plateaus à 40 m, Kluft 40 m breit, 18 m tief |
| Hügel | 160 m | offenes Gelände, Senke in der Mitte, 8 m Höhenunterschied |

### Materialien
| Material | Kosten | TP | Dichte | Eigenschaft |
|---|---|---|---|---|
| Holz | 4 ⚙/m | 100 | leicht | brennbar, biegt sichtbar |
| Metall | 10 ⚙/m | 260 | schwer | steif, nicht brennbar |
| Panzer | 18 ⚙/m | 520 | sehr schwer | Schadensfaktor 0,3 |
| Seil | 2 ⚙/m | 60 | sehr leicht | nur Zug |
| Tür | 14 ⚙/m | 160 | schwer | offen: Projektile passieren |

### Wirtschaft
Start (Normal): 400 ⚙ / 200 ⚡. Lager: 1000 ⚙ / 400 ⚡.
Mine +6 ⚙/s · Windturbine +6 ⚡/s (×1,0 bis ×1,5 je nach Höhe) · Reaktor +2 ⚡/s.
HUD-Beispielwerte in allen Mockups: **Metall 340 (+12/s)** = 2 Minen, **Energie 180/400 (+8/s)** = Reaktor + 1 Turbine, **Zeit 02:14**, **Wind 3,2 m/s**.

### Waffen
| Waffe | Schaden | Splash | Reichweite | Nachladen | Kosten/Schuss | Freischaltung |
|---|---|---|---|---|---|---|
| MG | 6 × 8 Salve | – | 60 m | 3 s | 8 ⚡ | Waffenkammer |
| Scharfschütze | 40, durchschlägt 1 Balken, Geräte ×3 | – | 120 m | 4 s | 15 ⚡ | Waffenkammer |
| Mörser | 120 | 2,5 m | 25–110 m | 6 s | 15 ⚙ · 30 ⚡ | Werkstatt |
| Kanone | 90 direkt, hoher Impuls | 1,2 m | 100 m | 10 s | 30 ⚙ · 60 ⚡ | Werkstatt |
| Brandrakete | 40 + entzündet Holz in 2 m | 2,0 m | 100 m | 12 s | 20 ⚙ · 40 ⚡ | Fabrik |
| Laser | 80/s für 2 s | – | 140 m | 20 s | 150 ⚡ | Fabrik |

Zielen-Mockup: Mörser **52° · 78 %**, Scheitel **24 m**, Winddrift **+3 m**.
Bauen-Mockup: Ghost-Balken **4,6 m · 18 ⚙** (Holz).

### Techgebäude
Werkstatt 120 ⚙ · 40 ⚡ · 30 s · Waffenkammer 200 ⚙ · 80 ⚡ · 45 s · Upgrade-Zentrum 260 ⚙ · 120 ⚡ · 60 s · Fabrik 480 ⚙ · 240 ⚡ · 90 s (benötigt Upgrade-Zentrum).

---

## 2. Palette (Tokens)

| Token | Hex | Verwendung |
|---|---|---|
| steel | #2b3440 | Panels, Metallgrundton dunkel |
| steel-hi | #8a97a8 | Metall-Lichtkante |
| dark | #141a21 | Hintergründe, Outlines |
| rust | #d2622a | Primär-Akzent, aktive Tools, Primär-Buttons |
| hazard | #e8b73a | Warnstreifen, Warnungen, Nachlade-Ring |
| wood | #a3692f | Holz Grundton (hell #c48a4a, dunkel #6e4520) |
| text | #e9eef4 | Text |
| team-blue | #3a8dde | Spieler 1 |
| team-red | #d9433b | Spieler 2 |
| energy | #4fd1ff | Energie, Reaktorkern, Erz-Kristalle |
| ok | #6bd68a | gültiger Ghost, Fortschritt |
| fire | #ffb03a → #ff6a1f → #c2361a | Flammenverlauf innen → außen |
| smoke | #3a3f47 (90 %) → #6b7078 (20 %) | Rauch alt → jung |

Himmel Abenddämmerung (oben → Horizont): #1b1f3a → #3b2f5c → #7a4a6e → #d07a5a → #f2b26b. Sonne #ffe7b0 mit weichem Halo.
Berge: 3 Ebenen, je weiter weg desto heller und violetter (atmosphärische Perspektive): #2a2440, #3d3156, #574467.

---

## 3. Typografie

- **Bebas Neue**: Wordmark, große Titel (SIEG, Countdown, Screen-Titel).
- **Rajdhani** 500/600/700: alles andere (HUD, Buttons, Labels). Zahlen immer `tabular-nums`.
- Labels in Versalien mit 0,12–0,18 em Laufweite. Fließtext Rajdhani 500, nie unter 12 dp.
- Kein System-Monospace im Spiel-UI (auch nicht im Spielplatz).

---

## 4. Bauteile

### Balken
- Dicke: Holz 0,32 m, Metall 0,26 m, Panzer 0,42 m, Seil 0,08 m, Tür 0,40 m.
- **Holz**: 2 Planken längs (Fuge in der Mitte), Maserung längs mit leichten Wellen, 1 Astloch pro ~2 m, dunklere Kanten (Ambient Occlusion), oben 1 px Lichtkante.
- **Metall**: Doppel-T-Profil-Anmutung: hellere Oberkante, dunkler Mittelsteg, Nieten alle 0,5 m auf beiden Kanten, dezente Gebrauchsspuren.
- **Panzer**: Plattensegmente à 1 m mit sichtbaren Stoßfugen, 4 Bolzen pro Segment, matter dunkler Stahl, leichte Rostspuren an den Fugen.
- **Seil**: gedrehte Faser (diagonale Schraffur), hängt sichtbar durch (Durchhang ∝ Länge, max 8 %).
- **Tür**: Panzerplatte mit Scharnier an einem Ende, Warnstreifen-Rand; offen = um 100° geschwenkt, Öffnung dunkel.

### Knoten / Gelenke
- Durchmesser 0,42 m (kleiner als bisher), nie größer als 1,4 × dickster angeschlossener Balken.
- Holz-Knoten: Eisenlasche mit 3 Bolzen. Metall-Knoten: sechseckiges Knotenblech mit Mittelbolzen. Fundament-Knoten: siehe unten.
- Leichter Schlagschatten nach unten rechts (2 dp, 30 %).

### Fundamente
Betonsockel, trapezförmig, zu 60 % im Boden versenkt, mit Stahl-Ankerplatte oben und 2 Ankerbolzen. Teamfarbe als schmaler Streifen am Sockel.

### Geräte (kanonische Formen)
- **Reaktor**: kantiges Gehäuse mit Kühlrippen oben, großes rundes Sichtfenster mit pulsierendem **cyanfarbenem** Kern (Kreuzstreben im Fenster), Warnstreifen unten, Teamfarben-Lämpchen. (Der Kuppel-Reaktor aus dem ersten Spielplatz entfällt.)
- **Mine**: Gitter-Bohrturm mit Rad oben, Förderband zur Seite, steht auf Erz-Kristallen (cyan, leuchtend).
- **Windturbine**: schlanker Mast, 3 Blätter mit Rost-Spitzen, dreht mit Windstärke.
- **Werkstatt**: Hütte mit Rostdach, Zahnrad-Schild, Fenster gelb beleuchtet.
- **Waffenkammer**: Bunker mit Wimpel, Munitionskisten. **Upgrade-Zentrum**: Kran + Pfeil-Schild. **Fabrik**: Sägezahndach, Schornsteine mit Rauch.
- Alle Geräte stehen mit sichtbaren Montagefüßen **auf** einem Balken (nie schwebend), 1 dp dunkle Outline, Licht von oben links.

### Waffen
MG (Dreibein, Patronengurt), Scharfschütze (langer Lauf, Zielfernrohr), Mörser (kurzes dickes Rohr mit Rost-Ring auf Drehteller), Kanone (langes Rohr auf Lafette mit Speichenrad), Brandrakete (3er-Werfer mit roten Spitzen), Laser (Linse cyan). Rohr dreht sich immer mit dem Zielwinkel.

---

## 5. Zustände und Effekte

### Schaden (Holz und Metall)
| TP | Darstellung |
|---|---|
| 100–70 % | intakt |
| 70–40 % | feine dunkle Haarrisse **entlang der Maserung** (Holz) bzw. Dellen und Kratzer (Metall) – keine Zickzack-Kritzeleien |
| 40–15 % | abgesplitterte Kanten, ein fehlendes Stück, deutlich dunkler; Metall verbogen (leichte Krümmung) |
| < 15 % | zusätzlich langsames rotes Pulsieren der Kontur |
| gebrochen | Holz: Balken teilt sich an der Trefferstelle in zwei Hälften mit gezackten Splitterenden, die als Trümmer fallen. Metall: abgescherte, verbogene Enden mit Funkenregen. |

### Feuer (nur Holz)
1. Entzündung: orange Glut an der Unterseite, erste kleine Flammenzungen.
2. Brand: 3–6 animierte Flammenzungen (Verlauf fire-Token), Glut-Schein (additiver Halo) auf Nachbarbalken, aufsteigende Funken, Rauchsäule, die mit dem Wind kippt.
3. Verkohlt: Balken schwarz mit glühenden Rissen, danach Bruch.

### Explosion
Weißer Blitz (1 Frame) → Feuerball (0,3 s) → Schockwellen-Ring → 6–12 Trümmerstücke (rotierend, Material des getroffenen Balkens) → Rauchwolke (bleibt 3 s, driftet mit Wind) → Brandspur-Decal auf Balken/Boden. Kamera-Shake proportional zum Schaden (max 6 dp).

### Weitere Feedbacks
Staubwolke, wenn Trümmer den Boden treffen. Knarzen-Animation (leichtes Zittern) bei Balken über 80 % Bruchlast. Mündungsfeuer + Rückstoßbewegung des Rohrs beim Schuss.

---

## 6. Hintergrund und Gelände
- Himmel laut Palette, Sterne nur im oberen Drittel, Sonne tief am Horizont.
- 3 Berg-Silhouetten-Ebenen mit Parallaxe (0,2 / 0,4 / 0,6), dünner Dunstschleier vor jeder Ebene.
- Wolken: langgestreckte, flach schattierte Bänder (2 Töne + helle Oberkante), keine weichgezeichneten Blobs; driften mit dem Wind.
- Gelände: Graskante mit Büscheln, darunter Erde, dann Gesteinsschichten mit leichtem Versatz; die Kluft wird nach unten dunkler und neblig.
- Erzfelder: cyanfarbene Kristallgruppen mit leichtem Glühen.

---

## 7. HUD und Bedienung
- HUD oben: Chips mit Icon + Label (Versalien, klein) + Wert groß + Rate grün. Links Metall und Energie (mit Füllbalken), rechts Zeit, Wind (Pfeil dreht mit Richtung), Pause.
- Toolbar unten in der Daumenzone, Gruppen: Materialien | Geräte | Zurück, Reparatur; ganz rechts Modusschalter (ZIELEN / BAUEN).
- **FEUER-Button**: rund, Rost-Orange mit hellem Rand, Nachlade-Ring in hazard-Gelb, Restzeit darunter; deaktiviert = entsättigt mit Ring-Fortschritt, nie braun-matt.
- **Zielen-Geste (entschieden)**: Waffe antippen, dann irgendwo ziehen. **Die Zugrichtung ist die Schussrichtung**, die Zuglänge die Kraft (30–100 %). Gepunktete Flugbahn mit Wind, Punkte werden zum Ende hin kleiner, am Ende ein Einschlag-Fadenkreuz. Kein Schleuder-Prinzip.
- Ghost-Balken: gestrichelt grün (ok) oder rot (ungültig) mit Grund-Text, Längen-/Kosten-Chip daneben, Snap-Ring am Zielknoten, Lupe 2× (80 dp über dem Finger) mit Fadenkreuz, zeigt den echten vergrößerten Bildausschnitt.
- Gegnerischer Zustand: Chip "SPIELER 2 · 62 %" mit Reaktor-TP-Balken.

---

## 8. Audio (nur Spielplatz, Vorschau)
Prozedurale WebAudio-Sounds, erst nach erster Interaktion, Stumm-Schalter im HUD: Mörser-Abschuss (dumpfer Wumms), Kanone (Knall + Hall), MG (kurze Klicks), Explosion (Rauschen mit Tiefpass-Abfall), Holz bricht (Knacken), Metall bricht (Klirren), Feuer (leises Knistern-Loop), Balken gesetzt (Holz-Klopfen / Metall-Klonk).
