# Erweiterung (Recherche, noch nicht eingebaut): Ton bei Quick-Messages auf der Land-Uhr

Offener Punkt aus `docs/Offene_Punkte_Hardware_Test_05_08.md`: die Land-Uhr
(T-Watch S3) vibriert bei eingehenden Quick-Messages/Fragen, gibt aber
keinen Ton aus. Diese Datei fasst die heutige Recherche zusammen — **kein
fertiger Code**, siehe Begründung unten.

## Hat die T-Watch S3 überhaupt einen Lautsprecher?

**Ja.** Bestätigt über die offizielle LilyGoLib-Hardware-Doku
([`lilygo-t-watch-s3.md`](https://github.com/Xinyuan-LilyGO/LilyGoLib/blob/master/docs/hardware/lilygo-t-watch-s3.md)),
gilt für die reguläre S3 (nicht nur "S3 Plus", also genau die Hardware, die
bei uns als Land-Uhr läuft):

- **Lautsprecher-Verstärker**: MAX98357A (3.2W Class-D, I²S)
  — BCLK GPIO 48, WCLK GPIO 15, DOUT GPIO 46
- **Mikrofon** (fürs Gesamtbild, aktuell nicht relevant): PDM-Mikrofon
  SPM1423HM4H-B — SCK GPIO 44, DATA GPIO 47

## Was LilyGoLib an API dafür mitbringt

Aus dem offiziellen `examples/factory`-Sketch (Werksselbsttest, spielt dort
MP3s von SD ab) lässt sich die grundsätzliche API ablesen:

```cpp
// Verstärker-Stromversorgung vor der Wiedergabe einschalten
instance.powerControl(POWER_SPEAK, true);

// Rohes I2S-Audio ausgeben (dort für MP3-Decoder-Output genutzt)
instance.player.configureTX(sampleRate, bitsPerSample, (i2s_channel_t)channels);
instance.player.write((uint8_t*)buf, len);
```

`instance.player` ist demnach ein von LilyGoLib bereitgestelltes I2S-Player-
Objekt (Pin-Zuordnung übernimmt die Bibliothek, wie schon beim SX1262-Radio-
Objekt) — kein eigener MAX98357A-Treiber nötig, genau das Muster, das wir
schon beim LoRa-Radio (`docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`)
verwendet haben.

Für einen simplen Quick-Message-Ton reicht ein kurzer generierter Sinus-/
Rechteckton (kein MP3-Decoding nötig) — im Prinzip:

```cpp
// SKIZZE, nicht verifiziert (siehe unten) - kurzer Beep, z.B. 800 Hz, 150 ms
void playBeep(int freqHz, int durationMs) {
    instance.powerControl(POWER_SPEAK, true);
    instance.player.configureTX(16000, 16, I2S_CHANNEL_MONO); // Beispielwerte
    const int sampleRate = 16000;
    const int nSamples = (sampleRate * durationMs) / 1000;
    int16_t buf[nSamples];
    for (int i = 0; i < nSamples; i++) {
        buf[i] = (int16_t)(3000.0 * sin(2.0 * PI * freqHz * i / sampleRate));
    }
    instance.player.write((uint8_t*)buf, nSamples * sizeof(int16_t));
    instance.powerControl(POWER_SPEAK, false); // Verstärker wieder aus, spart Akku
}
```

## Warum das heute NICHT direkt in `Segeluhr_TWatch_S3.ino` eingebaut wurde

Anders als beim LoRa-`distanceTraveledM`-Feature (klar spezifizierte,
bereits im Projekt verwendete API) beruht der Codeschnipsel oben auf einem
**Beispiel aus einem anderen LilyGoLib-Sketch** (Werkstest), nicht auf
Code, der schon irgendwo in diesem Projekt läuft. Zwei konkrete Risiken:

1. **Version:** `CLAUDE.md` schreibt für LilyGoLib-Abhängigkeiten explizit
   gepinnte Versionen aus `LilyGoLib-ThirdParty` vor — die exakte
   `player`/`powerControl`/`POWER_SPEAK`-Signatur in der bei euch
   installierten Version könnte abweichen (hat schon zweimal zu
   API-Konflikten geführt, siehe `CLAUDE.md`).
2. **`USING_PCM_AMPLIFIER`/`USING_AUDIO_CODEC`:** im Factory-Sketch ist die
   Audio-Ausgabe an ein `#define` gekoppelt (ähnlich dem
   `USING_BHI260_SENSOR`-Fund beim Gesten-Training, siehe
   `docs/Erweiterung_Gesten_Training_Klio.md` Abschnitt 4) — ohne dieses
   Flag bleibt der Aufruf vermutlich ein Stub. Muss vor dem Einbau geprüft
   werden, sonst reproduzieren wir exakt den Bug, der beim Gesten-Training
   erst nach genauerem Hinsehen gefunden wurde.

Blind geschriebener Code, der wegen (1)/(2) nicht kompiliert oder beim
Booten crasht, würde heute Abend nur Zeit von den bereits fertigen,
höher priorisierten Tests (siehe `docs/Test_Checkliste_10_08.md`) wegnehmen.

## Nächster Schritt

Sobald am Rechner mit installierter LilyGoLib gearbeitet wird: einmal in
die tatsächlich installierten Header schauen (`player`/`powerControl` in
der `LilyGoLib`-Klassendefinition, Suche nach `POWER_SPEAK` und
`USING_PCM_AMPLIFIER`) — dann lässt sich die Skizze oben in wenigen
Minuten zu echtem, kompilierbarem Code machen und in
`triggerHaptic()`/die Quick-Message-Empfangslogik in
`Segeluhr_TWatch_S3.ino` einhängen (Ton parallel zur bestehenden Vibration,
Stumm-Modus-Schalter respektieren wie beim bisherigen Vibrations-Pfad).
