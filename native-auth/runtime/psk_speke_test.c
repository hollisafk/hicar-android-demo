/*
 * psk_speke_test.c - host-side self test for the optional PSK-SPEKE layer.
 * Not part of the Android build. Compile with:
 *
 *   gcc -std=c11 -Wall -D_ENABLE_PSK_SPEKE_ \
 *       -I runtime -I sources/deviceauth/interfaces/innerkits/deviceauth_lite \
 *       runtime/psk_speke_test.c runtime/psk_speke.c -lcrypto -o psk_speke_test
 *
 * It verifies the HKDF against an independent OpenSSL implementation, and
 * most importantly that two peers with DIFFERENT peer ids still derive the
 * very same PSK / nonce / PIN from a shared session key.
 */
#include "psk_speke.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>

static int g_failures;
#define CHECK(cond, msg) do { \
    if (!(cond)) { printf("  FAIL: %s\n", (msg)); g_failures++; } \
    else { printf("  ok  : %s\n", (msg)); } \
} while (0)

/* ---- in-memory file backend ---- */
static uint8_t g_file[PSK_SPEKE_FILE_LEN];
static uint32_t g_file_len;

static void reset_store(void)
{
    (void)memset(g_file, 0, sizeof(g_file));
    g_file_len = 0;
    psk_speke_clear_nonce();
    psk_speke_set_enabled(1);
}

static int32_t t_read(const char *name, uint32_t off, uint8_t *buf, uint32_t len)
{
    if (strcmp(name, PSK_SPEKE_FILE) != 0 || off > g_file_len)
        return 0;
    uint32_t avail = g_file_len - off;
    if (len > avail)
        len = avail;
    (void)memcpy(buf, g_file + off, len);
    return (int32_t)len;
}

static int32_t t_write(const char *name, uint32_t off, const uint8_t *buf, uint32_t len)
{
    if (strcmp(name, PSK_SPEKE_FILE) != 0 || off > sizeof(g_file) ||
        len > sizeof(g_file) - off)
        return -1;
    (void)memcpy(g_file + off, buf, len);
    if (off + len > g_file_len)
        g_file_len = off + len;
    return 0;
}

static struct hc_auth_id make_id(const char *s)
{
    struct hc_auth_id id;
    (void)memset(&id, 0, sizeof(id));
    size_t n = strlen(s);
    if (n > HC_AUTH_ID_BUFF_LEN)
        n = HC_AUTH_ID_BUFF_LEN;
    (void)memcpy(id.auth_id, s, n);
    id.length = (uint32_t)n;
    return id;
}

/* Independent RFC 5869 HKDF-SHA256 reference, deliberately written
 * separately from the implementation under test. */
static void ref_hkdf(const uint8_t *ikm, int ikml, const uint8_t *salt, int saltl,
                     const char *info, int out_len, uint8_t *out)
{
    uint8_t prk[32], t[32], prev[32], in[32 + 256 + 1];
    unsigned int n = 32;
    (void)HMAC(EVP_sha256(), salt, saltl, ikm, (size_t)ikml, prk, &n);
    int have = 0, done = 0, ilen = (int)strlen(info);
    uint8_t counter = 1;
    while (done < out_len) {
        int pos = 0;
        if (have) { (void)memcpy(in, prev, 32); pos = 32; }
        (void)memcpy(in + pos, info, (size_t)ilen);
        pos += ilen;
        in[pos++] = counter;
        n = 32;
        (void)HMAC(EVP_sha256(), prk, 32, in, (size_t)pos, t, &n);
        (void)memcpy(prev, t, 32);
        have = 1;
        int take = out_len - done > 32 ? 32 : out_len - done;
        (void)memcpy(out + done, t, (size_t)take);
        done += take;
        counter++;
    }
}

static void test_hkdf(void)
{
    printf("[1] HKDF-SHA256 against independent reference\n");
    static const uint8_t ikm[] = "input key material for psk speke";
    static const uint8_t salt[] = PSK_SPEKE_STORE_SALT;
    uint8_t got[64], want[64];
    (void)memset(got, 0, sizeof(got));
    (void)memset(want, 0, sizeof(want));
    int rc = psk_speke_hkdf(ikm, (uint32_t)sizeof(ikm) - 1,
                            salt, (uint32_t)sizeof(salt) - 1,
                            PSK_SPEKE_HKDF_INFO, sizeof(got), got);
    ref_hkdf(ikm, (int)sizeof(ikm) - 1, salt, (int)sizeof(salt) - 1,
             PSK_SPEKE_HKDF_INFO, (int)sizeof(want), want);
    CHECK(rc == PSK_SPEKE_OK, "hkdf returns success");
    CHECK(memcmp(got, want, sizeof(got)) == 0, "64-byte HKDF matches RFC 5869 reference");

    uint8_t got32[32], want32[32];
    (void)psk_speke_hkdf(ikm, (uint32_t)sizeof(ikm) - 1, salt,
                         (uint32_t)sizeof(salt) - 1, PSK_SPEKE_HKDF_INFO,
                         sizeof(got32), got32);
    ref_hkdf(ikm, (int)sizeof(ikm) - 1, salt, (int)sizeof(salt) - 1,
             PSK_SPEKE_HKDF_INFO, (int)sizeof(want32), want32);
    CHECK(memcmp(got32, want32, sizeof(got32)) == 0, "32-byte HKDF matches reference");
    CHECK(psk_speke_hkdf(NULL, 0, salt, 1, "x", 32, got32) == PSK_SPEKE_ERR,
          "rejects null ikm");
}

static void test_symmetry(void)
{
    printf("[2] two-peer symmetry (different peer ids, same session key)\n");
    struct hc_auth_id peer_a = make_id("CAR_HEAD_UNIT_SEES_PHONE_A");
    struct hc_auth_id peer_b = make_id("PHONE_SEES_CAR_HEAD_UNIT_B");
    struct hc_session_key sk;
    (void)memset(&sk, 0, sizeof(sk));
    sk.length = 32;
    for (uint32_t i = 0; i < sk.length; i++)
        sk.session_key[i] = (uint8_t)(0x40 + i);

    reset_store();
    CHECK(psk_speke_save(&peer_a, &sk) == PSK_SPEKE_OK, "peer A saves PSK");
    uint8_t psk_a[PSK_SPEKE_PSK_LEN];
    CHECK(psk_speke_load(&peer_a, psk_a) == PSK_SPEKE_OK, "peer A loads PSK");

    reset_store();
    CHECK(psk_speke_save(&peer_b, &sk) == PSK_SPEKE_OK, "peer B saves PSK");
    uint8_t psk_b[PSK_SPEKE_PSK_LEN];
    CHECK(psk_speke_load(&peer_b, psk_b) == PSK_SPEKE_OK, "peer B loads PSK");

    CHECK(memcmp(psk_a, psk_b, sizeof(psk_a)) == 0,
          "both peers derive the SAME PSK despite different peer ids");

    uint8_t nonce_a[PSK_SPEKE_NONCE_LEN], nonce_b[PSK_SPEKE_NONCE_LEN];
    CHECK(psk_speke_derive_nonce(psk_a, nonce_a) == PSK_SPEKE_OK, "peer A derives nonce");
    CHECK(psk_speke_derive_nonce(psk_b, nonce_b) == PSK_SPEKE_OK, "peer B derives nonce");
    CHECK(memcmp(nonce_a, nonce_b, sizeof(nonce_a)) == 0, "both peers derive the SAME nonce");

    struct hc_pin pin_a, pin_b;
    (void)memset(&pin_a, 0, sizeof(pin_a));
    (void)memset(&pin_b, 0, sizeof(pin_b));
    CHECK(psk_speke_build_pin(psk_a, nonce_a, &pin_a) == PSK_SPEKE_OK, "peer A builds PIN");
    CHECK(psk_speke_build_pin(psk_b, nonce_b, &pin_b) == PSK_SPEKE_OK, "peer B builds PIN");
    CHECK(pin_a.length == PSK_SPEKE_PIN_HEX_LEN, "PIN length is 64 hex chars");
    CHECK(memcmp(pin_a.pin, pin_b.pin, PSK_SPEKE_PIN_HEX_LEN) == 0,
          "both peers feed the SAME PIN into SPEKE");

    int hex_ok = 1;
    for (uint32_t i = 0; i < pin_a.length; i++) {
        char c = (char)pin_a.pin[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            hex_ok = 0;
    }
    CHECK(hex_ok, "PIN is lowercase hex");

    uint8_t tmp[PSK_SPEKE_TMP_KEY_LEN];
    (void)psk_speke_hkdf(psk_a, PSK_SPEKE_PSK_LEN, nonce_a,
                         PSK_SPEKE_NONCE_LEN, PSK_SPEKE_HKDF_INFO,
                         sizeof(tmp), tmp);
    uint8_t expanded[PSK_SPEKE_PIN_HEX_LEN + 1];
    (void)memset(expanded, 0, sizeof(expanded));
    static const char digits[] = "0123456789abcdef";
    for (uint32_t i = 0; i < sizeof(tmp); i++) {
        expanded[i * 2] = (uint8_t)digits[tmp[i] >> 4];
        expanded[i * 2 + 1] = (uint8_t)digits[tmp[i] & 0x0f];
    }
    CHECK(memcmp(expanded, pin_a.pin, PSK_SPEKE_PIN_HEX_LEN) == 0,
          "PIN equals hex(HKDF(PSK, nonce, hichain_tmp_auth_enc_key, 32))");
}

static void test_nonce_override(void)
{
    printf("[3] external nonce override (seam for phone-side payload.nonce)\n");
    uint8_t psk[PSK_SPEKE_PSK_LEN], n1[PSK_SPEKE_NONCE_LEN], n2[PSK_SPEKE_NONCE_LEN];
    (void)memset(psk, 0x33, sizeof(psk));
    (void)psk_speke_derive_nonce(psk, n1);
    uint8_t supplied[PSK_SPEKE_NONCE_LEN];
    (void)memset(supplied, 0x7e, sizeof(supplied));
    psk_speke_set_nonce(supplied);
    CHECK(psk_speke_derive_nonce(psk, n2) == PSK_SPEKE_OK, "derive with external nonce");
    CHECK(memcmp(n2, supplied, sizeof(n2)) == 0, "external nonce is used verbatim");
    CHECK(memcmp(n1, n2, sizeof(n1)) != 0, "override actually changes the nonce");
    psk_speke_clear_nonce();
    uint8_t n3[PSK_SPEKE_NONCE_LEN];
    (void)psk_speke_derive_nonce(psk, n3);
    CHECK(memcmp(n3, n1, sizeof(n3)) == 0, "clearing restores deterministic nonce");
}

static void test_store(void)
{
    printf("[4] per-peer store CRUD\n");
    reset_store();
    struct hc_auth_id a = make_id("PEER_ONE");
    struct hc_auth_id b = make_id("PEER_TWO");
    struct hc_session_key sk;
    (void)memset(&sk, 0, sizeof(sk));
    sk.length = 16;
    for (uint32_t i = 0; i < sk.length; i++)
        sk.session_key[i] = (uint8_t)i;

    CHECK(psk_speke_has(&a) == 0, "empty store has no PSK");
    CHECK(psk_speke_save(&a, &sk) == PSK_SPEKE_OK, "save for peer A (16-byte key)");
    CHECK(psk_speke_has(&a) == 1, "has() finds peer A");
    CHECK(psk_speke_has(&b) == 0, "has() does not confuse peer B");
    CHECK(psk_speke_save(&b, &sk) == PSK_SPEKE_OK, "save for peer B");
    CHECK(psk_speke_has(&a) == 1 && psk_speke_has(&b) == 1, "two slots coexist");

    CHECK(psk_speke_forget(&a) == PSK_SPEKE_OK, "forget peer A");
    CHECK(psk_speke_has(&a) == 0, "peer A gone");
    CHECK(psk_speke_has(&b) == 1, "peer B untouched");
    CHECK(psk_speke_forget(&b) == PSK_SPEKE_OK, "forget peer B");
    CHECK(psk_speke_has(&b) == 0, "store empty again");
}

static void test_switch(void)
{
    printf("[5] runtime switch defaults off\n");
    psk_speke_set_enabled(0);
    CHECK(psk_speke_is_enabled() == 0, "off after set_enabled(0)");
    psk_speke_set_enabled(1);
    CHECK(psk_speke_is_enabled() == 1, "on after set_enabled(1)");
}

int main(void)
{
    psk_speke_init(t_read, t_write);
    printf("PSK-SPEKE host self test\n=========================\n");
    test_hkdf();
    test_symmetry();
    test_nonce_override();
    test_store();
    test_switch();
    printf("=========================\n%s (%d failure(s))\n",
           g_failures == 0 ? "ALL PASS" : "FAILED", g_failures);
    return g_failures == 0 ? 0 : 1;
}
