#pragma once
#include <cstdint>
#include <cstring>
#include <mbedtls/aes.h>
#include <esp_system.h> // für esp_fill_random (Hardware-RNG)

// ============================================================
// Segeluhr - LoRa-Verschlüsselung (AES-128-CTR)
// ============================================================
// Zweck: rohes LoRa überträgt standardmäßig unverschlüsselt. Das hier ist
// kein Schutz gegen gezielte Angreifer (der Schlüssel liegt im Repo), sondern
// verhindert einfaches "zufälliges Mitlesen" durch jemanden, der zufällig auf
// derselben Frequenz/SF/Bandbreite/Sync-Word landet.
//
// CTR-Modus gewählt, weil kein Padding nötig ist (unsere Pakete sind klein
// und nicht 16-Byte-ausgerichtet) und weil ein Nonce pro Paket mitgeschickt
// wird - kein Zustand muss zwischen Sender/Empfänger synchron gehalten werden.
//
// WICHTIG: AES_KEY MUSS auf beiden Uhren exakt identisch sein. Ein Byte
// Unterschied = Empfänger kann nichts mehr entschlüsseln, ohne Fehlermeldung,
// einfach nur Datenmüll.
// ============================================================

constexpr size_t AES_KEY_LEN = 16;
constexpr size_t LORA_NONCE_LEN = 8; // wird jedem Paket vorangestellt

// TODO(Claude Code / Roman): eigenen, zufälligen 16-Byte-Schlüssel eintragen
// (z.B. per `openssl rand -hex 16` erzeugen) - dieser Platzhalter ist NICHT
// sicher, nur ein Beispiel-Byte-Array in der richtigen Länge.
static const uint8_t AES_KEY[AES_KEY_LEN] = {
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
    0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
};

// Maximale Nutzlast, die wir verschlüsseln - großzügig bemessen, unsere
// größten Pakete (LoRaStatusPacket = 20 Byte) liegen weit darunter.
constexpr size_t CRYPTO_MAX_PAYLOAD = 64;
constexpr size_t CRYPTO_MAX_BUFFER = LORA_NONCE_LEN + CRYPTO_MAX_PAYLOAD;

// Verschlüsselt `plain` (Länge `len`) nach `outBuf` (muss mindestens
// len + LORA_NONCE_LEN Byte groß sein). outLen = tatsächlich geschriebene
// Bytes (Nonce + Ciphertext). Rückgabe false bei zu großer Eingabe.
inline bool encryptLoRaPacket(const uint8_t* plain, size_t len,
                               uint8_t* outBuf, size_t& outLen) {
    if (len > CRYPTO_MAX_PAYLOAD) return false;

    uint8_t nonce[LORA_NONCE_LEN];
    esp_fill_random(nonce, LORA_NONCE_LEN); // Hardware-RNG, pro Paket neu

    uint8_t counterBlock[16] = {0};
    memcpy(counterBlock, nonce, LORA_NONCE_LEN);

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_enc(&ctx, AES_KEY, 128);

    size_t ncOff = 0;
    uint8_t streamBlock[16];
    mbedtls_aes_crypt_ctr(&ctx, len, &ncOff, counterBlock, streamBlock,
                           plain, outBuf + LORA_NONCE_LEN);

    mbedtls_aes_free(&ctx);

    memcpy(outBuf, nonce, LORA_NONCE_LEN);
    outLen = LORA_NONCE_LEN + len;
    return true;
}

// Entschlüsselt ein per encryptLoRaPacket() erzeugtes Paket zurück.
// `cipherWithNonce`/`totalLen` = was über LoRa ankam (Nonce + Ciphertext).
// outPlain muss mindestens totalLen - LORA_NONCE_LEN Byte groß sein.
inline bool decryptLoRaPacket(const uint8_t* cipherWithNonce, size_t totalLen,
                               uint8_t* outPlain, size_t& outLen) {
    if (totalLen <= LORA_NONCE_LEN) return false;

    uint8_t counterBlock[16] = {0};
    memcpy(counterBlock, cipherWithNonce, LORA_NONCE_LEN);

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    // CTR-Modus nutzt für Ver- UND Entschlüsselung denselben "enc"-Schlüssel
    mbedtls_aes_setkey_enc(&ctx, AES_KEY, 128);

    size_t cipherLen = totalLen - LORA_NONCE_LEN;
    size_t ncOff = 0;
    uint8_t streamBlock[16];
    mbedtls_aes_crypt_ctr(&ctx, cipherLen, &ncOff, counterBlock, streamBlock,
                           cipherWithNonce + LORA_NONCE_LEN, outPlain);

    mbedtls_aes_free(&ctx);
    outLen = cipherLen;
    return true;
}
