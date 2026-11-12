/*
 * hichain_wire_test.c - host self-test for the AUTH_* wire layer.
 *
 * Build (no Android dependencies needed):
 *   gcc -std=c11 -Wall -Wextra -Werror -O2 \
 *       runtime/hichain_wire_test.c runtime/hichain_wire.c -o wire_test
 *
 * 2026-11-07: after real-device capture (LIO-AN00 / HiCar 14.2.0.400) the
 * code-mapping was removed. Huawei's 0x11 is a genuine STS request and must
 * reach STS_MODULAR untouched; mapping it to PAKE_REQUEST makes us answer with
 * a 4-field PAKE response and the phone replies errorCode=1. The layer now
 * only aligns the version string and relaxes peer-side validation.
 */
#include "hichain_wire.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond, name) do {                                              \
    g_checks++;                                                             \
    if (!(cond)) {                                                          \
        g_failures++;                                                       \
        printf("FAIL  %-58s (line %d)\n", (name), __LINE__);               \
    } else {                                                                \
        printf("pass  %s\n", (name));                                       \
    }                                                                       \
} while (0)

static void test_tables(void)
{
    /* Both families must pass through verbatim: G_MESSAGE_CODE_MAP already
     * knows PAKE_MODULAR for 0x1/0x2 and STS_MODULAR for 0x11/0x12. */
    const uint16_t codes[] = {
        0x0001, 0x0002, 0x8001, 0x8002,   /* PAKE family */
        0x0011, 0x0012, 0x8011, 0x8012,   /* AUTH/STS family */
        0x0000, 0x0003, 0x0023, 0x8003, 0x8080, 0x8013, 0x8000
    };
    for (size_t i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        CHECK(hichain_wire_out(codes[i]) == codes[i], "out: every code passes through");
        CHECK(hichain_wire_in(codes[i]) == codes[i], "in: every code passes through");
        CHECK(hichain_wire_in(hichain_wire_out(codes[i])) == codes[i],
              "round trip in(out(x)) == x");
        CHECK(hichain_wire_out(hichain_wire_in(codes[i])) == codes[i],
              "round trip out(in(x)) == x");
    }

    /* Regression: the exact mistake that broke免输码重连. */
    CHECK(hichain_wire_in(0x0011) == 0x0011, "regression: 0x11 stays AUTH_START_REQUEST");
    CHECK(hichain_wire_in(0x0011) != 0x0001, "regression: 0x11 must NOT become PAKE_REQUEST");
    CHECK(hichain_wire_out(0x8001) == 0x8001, "regression: PAKE_RESPONSE stays 0x8001");
    CHECK(hichain_wire_out(0x8011) == 0x8011, "regression: AUTH_START_RESPONSE stays 0x8011");
    CHECK(hichain_wire_in(0x0001) == 0x0001, "regression: first-bind PAKE_REQUEST unchanged");
}

static void test_switch(void)
{
    hichain_wire_set_enabled(0);
    CHECK(hichain_wire_is_enabled() == 0, "switch: default/off state");
    hichain_wire_set_enabled(1);
    CHECK(hichain_wire_is_enabled() != 0, "switch: on state");
    hichain_wire_set_enabled(-7);
    CHECK(hichain_wire_is_enabled() != 0, "switch: any nonzero enables");
    hichain_wire_set_enabled(0);
    CHECK(hichain_wire_is_enabled() == 0, "switch: back to off");
}

static void test_json(void)
{
    const char *src = "{\"message\":1,\"payload\":{\"version\":{\"currentVersion\":\"2.0.31\","
                      "\"minVersion\":\"1.0.0\"},\"support256mod\":true,\"operationCode\":6}}";
    char buf[2048];

    /* Switch off: byte-for-byte unchanged. */
    hichain_wire_set_enabled(0);
    strcpy(buf, src);
    CHECK(hichain_wire_rewrite_json(buf, sizeof(buf)) == 0, "json: no-op while switch is off");
    CHECK(strcmp(buf, src) == 0, "json: off state leaves bytes untouched");

    /* Switch on: still no rewrite, but the payload must survive intact. */
    hichain_wire_set_enabled(1);
    strcpy(buf, src);
    CHECK(hichain_wire_rewrite_json(buf, sizeof(buf)) == 0, "json: enabled layer reports no rewrite");
    CHECK(strcmp(buf, src) == 0, "json: enabled layer leaves bytes untouched");
    CHECK(strstr(buf, "{\"message\":1,") == buf, "json: PAKE_REQUEST stays 1 on the wire");
    CHECK(strstr(buf, "\"currentVersion\":\"2.0.31\"") != NULL, "json: payload version preserved");
    CHECK(strstr(buf, "\"operationCode\":6") != NULL, "json: payload operationCode preserved");

    /* Every wire code keeps its own value, including the STS ones. */
    const char *messages[] = {
        "{\"message\":1,\"payload\":{}}",
        "{\"message\":17,\"payload\":{}}",
        "{\"message\":32769,\"payload\":{}}",
        "{\"message\":32785,\"payload\":{}}",
        "{\"message\":32896,\"payload\":{\"errorCode\":1}}"
    };
    for (size_t i = 0; i < sizeof(messages) / sizeof(messages[0]); i++) {
        char b[256];
        strcpy(b, messages[i]);
        CHECK(hichain_wire_rewrite_json(b, sizeof(b)) == 0, "json: all codes left unchanged");
        CHECK(strcmp(b, messages[i]) == 0, "json: no byte changed for any code");
    }

    /* Nested "message" keys and whitespace must not cause accidental edits. */
    strcpy(buf, "{\"message\":1,\"payload\":{\"message\":999}}");
    CHECK(hichain_wire_rewrite_json(buf, sizeof(buf)) == 0, "json: first message key found");
    CHECK(strcmp(buf, "{\"message\":1,\"payload\":{\"message\":999}}") == 0,
          "json: nested message key left alone");
    strcpy(buf, "{ \"message\" : 2 , \"payload\":{} }");
    CHECK(hichain_wire_rewrite_json(buf, sizeof(buf)) == 0, "json: whitespace tolerated");
    CHECK(strcmp(buf, "{ \"message\" : 2 , \"payload\":{} }") == 0, "json: whitespace preserved");

    /* Malformed input is still rejected without touching the buffer. */
    const char *malformed[] = {
        "{\"payload\":{}}",
        "{\"message\":\"1\",\"payload\":{}}",
        "{\"message\":,\"payload\":{}}",
        "{\"message\":999999999999,\"payload\":{}}",
        ""
    };
    for (size_t i = 0; i < sizeof(malformed) / sizeof(malformed[0]); i++) {
        char b[256];
        strcpy(b, malformed[i]);
        CHECK(hichain_wire_rewrite_json(b, sizeof(b)) == -1, "json: malformed input rejected");
        CHECK(strcmp(b, malformed[i]) == 0, "json: malformed input left intact");
    }
    CHECK(hichain_wire_rewrite_json(NULL, 100) == 0, "json: NULL buffer handled");
    hichain_wire_set_enabled(0);
}

static void test_version_and_op(void)
{
    uint32_t v[3] = { 0, 0, 0 };
    uint32_t m[3] = { 0, 0, 0 };
    hichain_wire_version(v);
    hichain_wire_min_version(m);
    CHECK(v[0] == 2 && v[1] == 0 && v[2] == 31, "version: currentVersion 2.0.31");
    CHECK((v[2] & 8u) != 0, "version: PSK_SPEKE(8) bit is set");
    CHECK(v[2] == HICHAIN_WIRE_SUPPORT_ALGORITHMS, "version: mask equals 0x1f");
    CHECK(m[0] == 1 && m[1] == 0 && m[2] == 0, "version: minVersion 1.0.0");
    hichain_wire_version(NULL);
    hichain_wire_min_version(NULL);
    CHECK(1, "version: NULL output is safe");

    CHECK(hichain_wire_accept_op(1), "op: BIND accepted");
    CHECK(hichain_wire_accept_op(2), "op: AUTHENTICATE accepted");
    CHECK(hichain_wire_accept_op(6), "op: AUTH_KEY_AGREEMENT accepted");
    const int32_t rejected[] = { -1, 0, 3, 4, 5, 7, 99 };
    for (size_t i = 0; i < sizeof(rejected) / sizeof(rejected[0]); i++) {
        CHECK(!hichain_wire_accept_op(rejected[i]), "op: unrelated operationCode rejected");
    }
}

static void test_full_round_trip(void)
{
    /* Simulate the two real flows: first PIN bind (PAKE) and passwordless
     * reconnect (STS). Neither may be translated into the other. */
    hichain_wire_set_enabled(1);

    char first_bind[2048];
    strcpy(first_bind, "{\"message\":1,\"payload\":{\"version\":{\"currentVersion\":\"2.0.31\","
                        "\"minVersion\":\"1.0.0\"},\"support256mod\":true,\"operationCode\":6}}");
    CHECK(hichain_wire_rewrite_json(first_bind, sizeof(first_bind)) == 0,
          "round trip: first-bind message not rewritten");
    unsigned first_code = 0;
    CHECK(sscanf(first_bind, "{\"message\":%u,", &first_code) == 1, "round trip: first-bind code parsed");
    CHECK(hichain_wire_in((uint16_t)first_code) == 0x0001,
          "round trip: first bind reaches PAKE_MODULAR");

    char reconnect[512];
    strcpy(reconnect, "{\"message\":17,\"payload\":{\"peerAuthId\":\"ab\",\"epk\":\"cd\"}}");
    CHECK(hichain_wire_rewrite_json(reconnect, sizeof(reconnect)) == 0,
          "round trip: STS request not rewritten");
    unsigned sts_code = 0;
    CHECK(sscanf(reconnect, "{\"message\":%u,", &sts_code) == 1, "round trip: STS code parsed");
    CHECK(hichain_wire_in((uint16_t)sts_code) == 0x0011,
          "round trip: reconnect reaches STS_MODULAR");
    CHECK(hichain_wire_in((uint16_t)sts_code) != 0x0001,
          "round trip: reconnect must NOT reach PAKE_MODULAR");

    /* Our STS response keeps the code the phone expects. */
    char response[512];
    strcpy(response, "{\"message\":32785,\"payload\":{\"authData\":\"aabb\",\"epk\":\"cd\"}}");
    CHECK(hichain_wire_rewrite_json(response, sizeof(response)) == 0,
          "round trip: STS response not rewritten");
    unsigned rcode = 0;
    CHECK(sscanf(response, "{\"message\":%u,", &rcode) == 1, "round trip: response code parsed");
    CHECK(rcode == 32785, "round trip: response stays AUTH_START_RESPONSE");
    hichain_wire_set_enabled(0);
}

int main(void)
{
    printf("=== hichain_wire host self-test ===\n");
    test_tables();
    test_switch();
    test_json();
    test_version_and_op();
    test_full_round_trip();
    printf("=== %d checks, %d failures: %s ===\n", g_checks, g_failures,
           g_failures == 0 ? "ALL PASS" : "FAILED");
    return g_failures == 0 ? 0 : 1;
}
