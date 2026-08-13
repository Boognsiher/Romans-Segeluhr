// ============================================================================
// Empfaenger: Bluetooth (A2DP Sink) -> externer I2S-DAC (z.B. PCM5102)
// ============================================================================
// Nimmt den vom Sender-ESP32 (siehe Sender_RadioZuBluetooth) per klassischem
// Bluetooth (A2DP) gesendeten Audio-Stream entgegen und gibt ihn per I2S an
// einen externen DAC aus (deutlich bessere Klangqualitaet als der interne
// 8-Bit-DAC).
//
// Board: klassischer ESP32 (WROOM-32 o.ae.) - braucht Bluetooth Classic,
//        S3/C3/C6 funktionieren NICHT (nur BLE, kein A2DP).
//
// Benoetigte Bibliothek (Arduino-Bibliotheksverwalter):
//   - "ESP32-A2DP" von Phil Schatzmann
//
// Verkabelung PCM5102-Breakout <-> ESP32 (Pins unten im Code anpassbar):
//   VCC  -> 3.3V
//   GND  -> GND
//   BCK  -> GPIO26
//   DIN  -> GPIO22
//   LCK  -> GPIO25
//   SCK  -> GND   (internes Taktmodul verwenden, auf den meisten Breakouts)
//   FMT  -> GND   (I2S-Format)
//   XMT/XSMT -> 3.3V (Stummschaltung aufheben, sonst bleibt der DAC stumm)
//   FLT  -> GND
//   DEMP -> GND
//
// Arduino IDE Boardeinstellungen: "ESP32 Dev Module", Partition Scheme
// "Default", Flash Size passend zum Board.
// ============================================================================

#include "BluetoothA2DPSink.h"

// Muss EXAKT dem Namen entsprechen, mit dem der Sender-Sketch nach diesem
// Geraet sucht (BLUETOOTH_EMPFAENGER_NAME im anderen Sketch).
const char *BLUETOOTH_NAME = "ESP32-Radio-Empfaenger";

// I2S-Pins zum externen DAC (siehe Verkabelung oben)
i2s_pin_config_t i2sPins = {
  .bck_io_num = 26,
  .ws_io_num = 25,
  .data_out_num = 22,
  .data_in_num = I2S_PIN_NO_CHANGE
};

BluetoothA2DPSink a2dpSink;

void setup() {
  Serial.begin(115200);

  a2dpSink.set_pin_config(i2sPins);
  a2dpSink.start(BLUETOOTH_NAME);

  Serial.println("Empfaenger laeuft, wartet auf Bluetooth-Verbindung vom Sender...");
}

void loop() {
  // Die Bibliothek arbeitet komplett im Hintergrund (I2S-Task), hier ist
  // nichts weiter zu tun.
}
