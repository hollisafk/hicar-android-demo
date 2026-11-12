/*
 * hichain_wire.c - Huawei AUTH_* wire-compatibility layer.
 * See hichain_wire.h for the reverse-engineering notes.
 *
 * Deliberately depends on nothing but the C standard library so it can be
 * compiled and unit-tested on the host as well as inside the Android .so.
 */
#include "hichain_wire.h"

#include <stdio.h>
#include <string.h>

static int g_wire_enabled = 0;

int hichain_wire_is_enabled(void)
{
    return g_wire_enabled;
}

void hichain_wire_set_enabled(int on)
{
    g_wire_enabled = (on != 0);
}

/*
 * 实机结论（2026-11-07 15:28，LIO-AN00 / HiCar 14.2.0.400）：
 * 华为手机在「已配对免输码重连」时发的是真正的 STS AUTH_START_REQUEST(0x11)，
 * payload 带 peerAuthId / pkgName / serviceType / isoSalt / seed 等字段；
 * 开源分派表本来就有 AUTH_START_REQUEST -> STS_MODULAR。若把 0x11 映射回
 * PAKE_REQUEST(0x1)，内部会走 PAKE 状态机，回一个只有
 * version/challenge/salt/epk 四字段的响应，手机随即回 errorCode=1。
 * 反过来，首次输码配对手机发 0x1、期待 0x8001，实测直通即可成功。
 * 因此线上码一律直通：让 G_MESSAGE_CODE_MAP 自己选 PAKE / STS 模块。
 * 本层只保留版本串对齐与对端校验放宽。
 */
uint16_t hichain_wire_out(uint16_t code)
{
    return code;
}

uint16_t hichain_wire_in(uint16_t code)
{
    return code;
}

int hichain_wire_rewrite_json(char *json, size_t cap)
{
    if ((json == NULL) || !hichain_wire_is_enabled()) {
        return 0;
    }

    const char key[] = "\"message\"";
    char *p = strstr(json, key);
    if (p == NULL) {
        return -1;
    }
    p += sizeof(key) - 1;
    while ((*p == ' ') || (*p == '\t')) {
        ++p;
    }
    if (*p != ':') {
        return -1;
    }
    ++p;
    while ((*p == ' ') || (*p == '\t')) {
        ++p;
    }
    char *num = p;
    if ((*p < '0') || (*p > '9')) {
        return -1;
    }
    uint32_t value = 0;
    while ((*p >= '0') && (*p <= '9')) {
        value = value * 10u + (uint32_t)(*p - '0');
        if (value > 0x10000u) {
            return -1;
        }
        ++p;
    }

    uint16_t mapped = hichain_wire_out((uint16_t)value);
    if (mapped == (uint16_t)value) {
        return 0;
    }

    char digits[16];
    int n = snprintf(digits, sizeof(digits), "%u", (unsigned)mapped);
    if ((n <= 0) || ((size_t)n >= sizeof(digits))) {
        return -1;
    }

    size_t before = (size_t)(num - json);
    size_t after_with_nul = strlen(p) + 1u;
    if (before + (size_t)n + after_with_nul > cap) {
        return -1; /* never truncate a signed/authenticated message */
    }
    memmove(num + n, p, after_with_nul);
    memcpy(num, digits, (size_t)n);
    return 1;
}

int hichain_wire_accept_op(int32_t op)
{
    return (op == 1)    /* BIND */
        || (op == 2)    /* AUTHENTICATE */
        || (op == 6);   /* AUTH_KEY_AGREEMENT */
}

void hichain_wire_version(uint32_t out[3])
{
    if (out == NULL) {
        return;
    }
    out[0] = HICHAIN_WIRE_VERSION_FIRST;
    out[1] = HICHAIN_WIRE_VERSION_SECOND;
    out[2] = HICHAIN_WIRE_VERSION_THIRD;
}

void hichain_wire_min_version(uint32_t out[3])
{
    if (out == NULL) {
        return;
    }
    out[0] = HICHAIN_WIRE_MIN_FIRST;
    out[1] = HICHAIN_WIRE_MIN_SECOND;
    out[2] = HICHAIN_WIRE_MIN_THIRD;
}
