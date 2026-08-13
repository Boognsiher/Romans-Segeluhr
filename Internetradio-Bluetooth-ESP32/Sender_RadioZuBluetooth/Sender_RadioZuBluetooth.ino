// ============================================================================
// Sender: Internetradio -> Bluetooth (A2DP Source)
// ============================================================================
// Holt einen Internetradio-Stream (MP3) per WiFi, dekodiert ihn und sendet
// das Audio per klassischem Bluetooth (A2DP) an den zweiten ESP32
// (siehe Empfaenger_BluetoothZuDAC), der als A2DP-Sink laeuft.
//
// Board: klassischer ESP32 (WROOM-32 o.ae.) - braucht Bluetooth Classic,
//        S3/C3/C6 funktionieren NICHT (nur BLE, kein A2DP).
//
// Benoetigte Bibliotheken (Arduino-Bibliotheksverwalter):
//   - "arduino-audio-tools"  von Phil Schatzmann
//   - "ESP32-A2DP"           von Phil Schatzmann (wird von audio-tools genutzt)
//
// Arduino IDE Boardeinstellungen: "ESP32 Dev Module", Partition Scheme
// "Default", Flash Size passend zum Board.
// ============================================================================

#include "WiFi.h"
#include "AudioTools.h"
#include "AudioTools/AudioCodecs/CodecMP3Helix.h"
#include "AudioTools/AudioLibs/A2DPStream.h"

// ---- Anpassen -------------------------------------------------------------
const char *WIFI_SSID = "DEIN_WLAN_NAME";
const char *WIFI_PASSWORD = "DEIN_WLAN_PASSWORT";

// URL eines MP3-Streams. Beispiel SRF 3 (bei Bedarf pruefen/ersetzen, Sender
// aendern ihre Stream-URLs gelegentlich):
const char *RADIO_STREAM_URL = "http://stream.srg-ssr.ch/m/drs3/mp3_128";

// Muss EXAKT dem Bluetooth-Namen entsprechen, den der Empfaenger-Sketch
// setzt (a2dp_sink.start("...") im anderen Sketch).
const char *BLUETOOTH_EMPFAENGER_NAME = "ESP32-Radio-Empfaenger";
// ---------------------------------------------------------------------------

URLStream urlStream(WIFI_SSID, WIFI_PASSWORD);
A2DPStream a2dpStream = A2DPStream::instance();
EncodedAudioStream decoder(&a2dpStream, new MP3DecoderHelix());
StreamCopy copier(decoder, urlStream);

void setup() {
  Serial.begin(115200);
  AudioToolsLogger.begin(Serial, AudioToolsLogLevel::Warning);

  // A2DP-Source starten: sucht aktiv nach einem Bluetooth-Geraet mit
  // passendem Namen und verbindet sich automatisch damit.
  auto cfg = a2dpStream.defaultConfig(TX_MODE);
  cfg.name = BLUETOOTH_EMPFAENGER_NAME;
  cfg.auto_reconnect = true;
  a2dpStream.begin(cfg);

  decoder.begin();

  // Internetradio-Stream oeffnen und in den Decoder -> A2DP-Stream leiten
  urlStream.begin(RADIO_STREAM_URL, "audio/mp3");

  Serial.println("Sender laeuft: verbinde mit Empfaenger und starte Stream...");
}

void loop() {
  copier.copy();
}
