/*
 * psk_speke.c - see psk_speke.h for protocol notes.
 *
 * Only standard C + OpenSSL are used; no Huks/deviceauth internals are
 * required. That keeps the module independently compilable and testable on
 * the host (see psk_speke_test.c).
 */
#include "psk_speke.h"

#include <string.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>

/* Fail at build time if the PIN buffer was not widened for this build. */
typedef char psk_speke_pin_buffer_too_small[(HC_PIN_BUFF_LEN >= PSK_SPEKE_PIN_HEX_LEN) ? 1 : -1];

static int g_enabled = 0;
static psk_speke_read_fn g_reader;
static psk_speke_write_fn g_writer;
static uint8_t g_external_nonce[PSK_SPEKE_NONCE_LEN];
static int g_have_external_nonce = 0;

struct psk_slot {
    uint8_t id_hash[SHA256_DIGEST_LENGTH];
    uint8_t psk[PSK_SPEKE_PSK_LEN];
    uint8_t used;
    uint8_t reserved[3];
};

struct psk_file {
    uint8_t magic[PSK_SPEKE_MAGIC_LEN];
    uint8_t count_le[4];
    uint8_t slots[PSK_SPEKE_MAX_SLOTS][PSK_SPEKE_SLOT_LEN];
};

int psk_speke_is_enabled(void) { return g_enabled; }
void psk_speke_set_enabled(int on) { g_enabled = on ? 1 : 0; }

void psk_speke_init(psk_speke_read_fn reader, psk_speke_write_fn writer)
{
    g_reader = reader;
    g_writer = writer;
}

static void hmac_sha256(const uint8_t *key, int key_len,
                        const uint8_t *data, int data_len,
                        uint8_t out[SHA256_DIGEST_LENGTH])
{
    unsigned int n = SHA256_DIGEST_LENGTH;
    (void)HMAC(EVP_sha256(), key, key_len, data, (size_t)data_len, out, &n);
}

int psk_speke_hkdf(const uint8_t *ikm, uint32_t ikm_len,
                   const uint8_t *salt, uint32_t salt_len,
                   const char *info, uint32_t out_len, uint8_t *out)
{
    if (ikm == NULL || salt == NULL || info == NULL || out == NULL)
        return PSK_SPEKE_ERR;
    if (ikm_len == 0 || salt_len == 0 || out_len == 0 ||
        out_len > 255u * SHA256_DIGEST_LENGTH)
        return PSK_SPEKE_ERR;

    uint8_t prk[SHA256_DIGEST_LENGTH];
    uint8_t zero_salt[SHA256_DIGEST_LENGTH];
    const uint8_t *s = salt;
    uint32_t s_len = salt_len;
    if (s_len == 0) { /* unreachable, kept for RFC 5869 completeness */
        (void)memset(zero_salt, 0, sizeof(zero_salt));
        s = zero_salt;
        s_len = SHA256_DIGEST_LENGTH;
    }
    hmac_sha256(s, (int)s_len, ikm, (int)ikm_len, prk);

    size_t info_len = strlen(info);
    uint8_t input[SHA256_DIGEST_LENGTH + 256 + 1];
    uint8_t t[SHA256_DIGEST_LENGTH];
    uint32_t done = 0;
    uint8_t counter = 1;

    while (done < out_len) {
        uint32_t n = 0;
        if (counter > 1) {
            (void)memcpy(input, t, SHA256_DIGEST_LENGTH);
            n = SHA256_DIGEST_LENGTH;
        }
        if (info_len > 0 && n + info_len + 1 <= sizeof(input)) {
            (void)memcpy(input + n, info, info_len);
            n += (uint32_t)info_len;
        }
        input[n++] = counter;
        hmac_sha256(prk, SHA256_DIGEST_LENGTH, input, (int)n, t);
        uint32_t take = out_len - done;
        if (take > SHA256_DIGEST_LENGTH)
            take = SHA256_DIGEST_LENGTH;
        (void)memcpy(out + done, t, take);
        done += take;
        counter++;
    }

    (void)memset(prk, 0, sizeof(prk));
    (void)memset(t, 0, sizeof(t));
    (void)memset(input, 0, sizeof(input));
    return PSK_SPEKE_OK;
}

static void peer_hash(const struct hc_auth_id *peer,
                      uint8_t out[SHA256_DIGEST_LENGTH])
{
    uint8_t buf[HC_AUTH_ID_BUFF_LEN + 32];
    uint32_t n = (peer != NULL && peer->length <= HC_AUTH_ID_BUFF_LEN) ? peer->length : 0;
    if (n > 0)
        (void)memcpy(buf, peer->auth_id, n);
    (void)memcpy(buf + n, PSK_SPEKE_NONCE_INFO, sizeof(PSK_SPEKE_NONCE_INFO));
    SHA256(buf, n + (uint32_t)sizeof(PSK_SPEKE_NONCE_INFO), out);
    (void)memset(buf, 0, sizeof(buf));
}

int psk_speke_derive_nonce(const uint8_t psk[PSK_SPEKE_PSK_LEN],
                           uint8_t out[PSK_SPEKE_NONCE_LEN])
{
    if (out == NULL)
        return PSK_SPEKE_ERR;
    if (g_have_external_nonce) {
        (void)memcpy(out, g_external_nonce, PSK_SPEKE_NONCE_LEN);
        return PSK_SPEKE_OK;
    }
    if (psk == NULL)
        return PSK_SPEKE_ERR;
    /* Bind the nonce to the PSK (identical on both peers). Never use the
     * peer auth id: it differs on the two sides and would break symmetry. */
    uint8_t buf[PSK_SPEKE_PSK_LEN + sizeof(PSK_SPEKE_NONCE_INFO)];
    (void)memcpy(buf, psk, PSK_SPEKE_PSK_LEN);
    (void)memcpy(buf + PSK_SPEKE_PSK_LEN, PSK_SPEKE_NONCE_INFO,
                 sizeof(PSK_SPEKE_NONCE_INFO));
    SHA256(buf, sizeof(buf), out);
    (void)memset(buf, 0, sizeof(buf));
    return PSK_SPEKE_OK;
}

void psk_speke_set_nonce(const uint8_t nonce[PSK_SPEKE_NONCE_LEN])
{
    if (nonce == NULL) {
        psk_speke_clear_nonce();
        return;
    }
    (void)memcpy(g_external_nonce, nonce, PSK_SPEKE_NONCE_LEN);
    g_have_external_nonce = 1;
}

void psk_speke_clear_nonce(void)
{
    (void)memset(g_external_nonce, 0, sizeof(g_external_nonce));
    g_have_external_nonce = 0;
}

int psk_speke_build_pin(const uint8_t psk[PSK_SPEKE_PSK_LEN],
                        const uint8_t nonce[PSK_SPEKE_NONCE_LEN],
                        struct hc_pin *out)
{
    if (psk == NULL || nonce == NULL || out == NULL)
        return PSK_SPEKE_ERR;

    uint8_t tmp[PSK_SPEKE_TMP_KEY_LEN];
    if (psk_speke_hkdf(psk, PSK_SPEKE_PSK_LEN, nonce, PSK_SPEKE_NONCE_LEN,
                       PSK_SPEKE_HKDF_INFO, PSK_SPEKE_TMP_KEY_LEN, tmp) != PSK_SPEKE_OK)
        return PSK_SPEKE_ERR;

    static const char hex[] = "0123456789abcdef";
    for (uint32_t i = 0; i < PSK_SPEKE_TMP_KEY_LEN; i++) {
        out->pin[i * 2] = (uint8_t)hex[tmp[i] >> 4];
        out->pin[i * 2 + 1] = (uint8_t)hex[tmp[i] & 0x0f];
    }
    out->length = PSK_SPEKE_PIN_HEX_LEN;
    (void)memset(tmp, 0, sizeof(tmp));
    return PSK_SPEKE_OK;
}

static int read_store(struct psk_file *file)
{
    if (g_reader == NULL || file == NULL)
        return PSK_SPEKE_ERR;
    (void)memset(file, 0, sizeof(*file));
    int32_t n = g_reader(PSK_SPEKE_FILE, 0, (uint8_t *)file, (uint32_t)sizeof(*file));
    if (n <= 0)
        return PSK_SPEKE_ERR;
    if (memcmp(file->magic, PSK_SPEKE_MAGIC, PSK_SPEKE_MAGIC_LEN) != 0) {
        (void)memset(file, 0, sizeof(*file));
        return PSK_SPEKE_ERR;
    }
    return PSK_SPEKE_OK;
}

static int write_store(const struct psk_file *file)
{
    if (g_writer == NULL || file == NULL)
        return PSK_SPEKE_ERR;
    return g_writer(PSK_SPEKE_FILE, 0, (const uint8_t *)file,
                    (uint32_t)sizeof(*file)) == 0 ? PSK_SPEKE_OK : PSK_SPEKE_ERR;
}

static void pack_slot(uint8_t raw[PSK_SPEKE_SLOT_LEN], const struct psk_slot *slot)
{
    (void)memcpy(raw, slot->id_hash, SHA256_DIGEST_LENGTH);
    (void)memcpy(raw + SHA256_DIGEST_LENGTH, slot->psk, PSK_SPEKE_PSK_LEN);
    raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN] = slot->used;
    raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN + 1] = 0;
    raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN + 2] = 0;
    raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN + 3] = 0;
}

static void unpack_slot(struct psk_slot *slot, const uint8_t raw[PSK_SPEKE_SLOT_LEN])
{
    (void)memcpy(slot->id_hash, raw, SHA256_DIGEST_LENGTH);
    (void)memcpy(slot->psk, raw + SHA256_DIGEST_LENGTH, PSK_SPEKE_PSK_LEN);
    slot->used = raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN];
    (void)memset(slot->reserved, 0, sizeof(slot->reserved));
}

static int find_slot(const struct psk_file *file,
                     const uint8_t id_hash[SHA256_DIGEST_LENGTH])
{
    for (uint32_t i = 0; i < PSK_SPEKE_MAX_SLOTS; i++) {
        const uint8_t *raw = file->slots[i];
        if (raw[SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN] == 1 &&
            memcmp(raw, id_hash, SHA256_DIGEST_LENGTH) == 0)
            return (int)i;
    }
    return -1;
}

static int put_psk(const struct hc_auth_id *peer,
                   const uint8_t psk[PSK_SPEKE_PSK_LEN])
{
    if (peer == NULL || peer->length == 0 || psk == NULL)
        return PSK_SPEKE_ERR;
    struct psk_file file;
    if (read_store(&file) != PSK_SPEKE_OK) {
        (void)memset(&file, 0, sizeof(file));
        (void)memcpy(file.magic, PSK_SPEKE_MAGIC, PSK_SPEKE_MAGIC_LEN);
    }

    uint8_t id_hash[SHA256_DIGEST_LENGTH];
    peer_hash(peer, id_hash);
    int idx = find_slot(&file, id_hash);
    if (idx < 0) {
        for (uint32_t i = 0; i < PSK_SPEKE_MAX_SLOTS; i++) {
            if (file.slots[i][SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN] != 1) {
                idx = (int)i;
                break;
            }
        }
    }
    if (idx < 0)
        idx = 0; /* store full: overwrite oldest slot (FIFO is enough for a car head unit) */

    struct psk_slot slot;
    (void)memset(&slot, 0, sizeof(slot));
    (void)memcpy(slot.id_hash, id_hash, SHA256_DIGEST_LENGTH);
    (void)memcpy(slot.psk, psk, PSK_SPEKE_PSK_LEN);
    slot.used = 1;
    pack_slot(file.slots[idx], &slot);

    uint32_t used = 0;
    for (uint32_t i = 0; i < PSK_SPEKE_MAX_SLOTS; i++)
        if (file.slots[i][SHA256_DIGEST_LENGTH + PSK_SPEKE_PSK_LEN] == 1)
            used++;
    file.count_le[0] = (uint8_t)(used & 0xff);
    file.count_le[1] = (uint8_t)((used >> 8) & 0xff);
    file.count_le[2] = (uint8_t)((used >> 16) & 0xff);
    file.count_le[3] = (uint8_t)((used >> 24) & 0xff);

    int rc = write_store(&file);
    (void)memset(&slot, 0, sizeof(slot));
    (void)memset(&file, 0, sizeof(file));
    (void)memset(id_hash, 0, sizeof(id_hash));
    return rc;
}

int psk_speke_set_psk(const struct hc_auth_id *peer,
                      const uint8_t psk[PSK_SPEKE_PSK_LEN])
{
    return put_psk(peer, psk);
}

int psk_speke_save(const struct hc_auth_id *peer,
                   const struct hc_session_key *session_key)
{
    if (peer == NULL || session_key == NULL)
        return PSK_SPEKE_ERR;
    if (session_key->length == 0 || session_key->length > HC_SESSION_KEY_LEN)
        return PSK_SPEKE_ERR;

    /* Normalise a 16/32-byte session key into a 32-byte PSK. Both peers
     * feed the identical session key and use the SAME constant salt, so the
     * PSK matches on both sides without any extra round trip. A per-peer
     * salt would break that symmetry. */
    uint8_t psk[PSK_SPEKE_PSK_LEN];
    static const uint8_t store_salt[] = PSK_SPEKE_STORE_SALT;

    if (psk_speke_hkdf(session_key->session_key, session_key->length,
                       store_salt, (uint32_t)sizeof(store_salt) - 1,
                       PSK_SPEKE_STORE_INFO,
                       PSK_SPEKE_PSK_LEN, psk) != PSK_SPEKE_OK)
        return PSK_SPEKE_ERR;
    int rc = put_psk(peer, psk);
    (void)memset(psk, 0, sizeof(psk));
    return rc;
}

int psk_speke_load(const struct hc_auth_id *peer, uint8_t psk[PSK_SPEKE_PSK_LEN])
{
    if (peer == NULL || peer->length == 0 || psk == NULL)
        return PSK_SPEKE_ERR;
    struct psk_file file;
    if (read_store(&file) != PSK_SPEKE_OK)
        return PSK_SPEKE_ERR;
    uint8_t id_hash[SHA256_DIGEST_LENGTH];
    peer_hash(peer, id_hash);
    int idx = find_slot(&file, id_hash);
    if (idx < 0) {
        (void)memset(&file, 0, sizeof(file));
        return PSK_SPEKE_ERR;
    }
    struct psk_slot slot;
    unpack_slot(&slot, file.slots[idx]);
    (void)memcpy(psk, slot.psk, PSK_SPEKE_PSK_LEN);
    (void)memset(&slot, 0, sizeof(slot));
    (void)memset(&file, 0, sizeof(file));
    return PSK_SPEKE_OK;
}

int psk_speke_has(const struct hc_auth_id *peer)
{
    uint8_t psk[PSK_SPEKE_PSK_LEN];
    int rc = psk_speke_load(peer, psk);
    (void)memset(psk, 0, sizeof(psk));
    return rc == PSK_SPEKE_OK ? 1 : 0;
}

int psk_speke_forget(const struct hc_auth_id *peer)
{
    if (peer == NULL || peer->length == 0)
        return PSK_SPEKE_ERR;
    struct psk_file file;
    if (read_store(&file) != PSK_SPEKE_OK)
        return PSK_SPEKE_OK; /* nothing stored */
    uint8_t id_hash[SHA256_DIGEST_LENGTH];
    peer_hash(peer, id_hash);
    int idx = find_slot(&file, id_hash);
    if (idx >= 0)
        (void)memset(file.slots[idx], 0, PSK_SPEKE_SLOT_LEN);
    int rc = write_store(&file);
    (void)memset(&file, 0, sizeof(file));
    return rc;
}
