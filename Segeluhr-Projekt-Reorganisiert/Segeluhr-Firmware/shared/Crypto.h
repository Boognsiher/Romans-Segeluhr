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

// Zufällig generiert für dieses Projekt (05.08.2026, Claude Code auf
// Wunsch von Roman). Schützt wie oben beschrieben nur gegen zufälliges
// Mitlesen, nicht gegen einen gezielten Angreifer - für diesen Zweck reicht
// das. MUSS auf Ultra- und S3-Firmware identisch sein (beide binden
// dieselbe shared/Crypto.h ein, also automatisch der Fall, solange diese
// Datei nicht pro Firmware kopiert/verändert wird).
static const uint8_t AES_KEY[AES_KEY_LEN] = {
    0xA9, 0xD2, 0x93, 0x43, 0x8A, 0x3D, 0x12, 0x0D,
    0xFF, 0x9C, 0xF5, 0xD6, 0x5E, 0x07, 0xBB, 0x01
};

// Maximale Nutzlast, die wir verschlüsseln - großzügig bemessen, unsere
// größten Pakete (LoRaStatusPacket = 31 Byte, Stand 10.08.2026) liegen
// weit darunter.
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
