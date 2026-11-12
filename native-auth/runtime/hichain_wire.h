/*
 * hichain_wire.h - Huawei AUTH_* wire-compatibility layer (optional).
 *
 * Reverse engineered from HwDeviceAuth.vdex (com.huawei.deviceauth.request
 * .AuthRequest). When a phone supports PSK_SPEKE (algorithm bit 8), it does
 * NOT run a new handshake: it keeps the exact same PakeTask / SPEKE payload
 * and only rewrites the top-level JSON "message" field to the AUTH_* code
 * range before transmitting, then rewrites it back before dispatching:
 *
 *   changeToAuthMsgCode / pake2StsMessage : 0x2 -> 0x12,
 *                                          0x8001 -> 0x8011,
 *                                          0x8002 -> 0x8012
 *   changeToTaskDependentMsg / sts2PakeMsg: 0x11 -> 0x1, 0x12 -> 0x2,
 *                                          0x8011 -> 0x8001,
 *                                          0x8012 -> 0x8002
 *
 * The open-source deviceauth_lite maps 0x8011/0x8012 to STS_MODULAR, so
 * feeding those codes straight into navigate_message() would take the STS
 * branch and fail. Doing the translation at the JSON boundary instead keeps
 * G_MESSAGE_CODE_MAP, is_message_illegal(), the state machine and every
 * cryptographic routine completely untouched: internally we always see the
 * ordinary PAKE_* codes.
 *
 * The other half of the negotiation is the version string. Huawei encodes
 * its algorithm mask in the third component of currentVersion:
 *   ConfiguredVersionInfo: HICHAIN_VERSION=2, SUPPORT_ALGORITHMS=0x1f
 *   -> "2.0.31", minVersion "1.0.0"
 * A peer still advertising "1.0.0" is treated as not supporting PSK_SPEKE.
 *
 * Everything here is inert unless the library was built with
 * _ENABLE_WIRE_AUTH_ AND hichain_wire_set_enabled(1) was called. With the
 * switch off the emitted JSON and version strings are byte-for-byte upstream.
 */
#ifndef HICHAIN_WIRE_H
#define HICHAIN_WIRE_H

#include <stdint.h>
#include <stddef.h>

/* 0x1f = DL_SPEKE|EC_SPEKE|STS|PSK_SPEKE|ISO_9798 */
#define HICHAIN_WIRE_SUPPORT_ALGORITHMS 31u
#define HICHAIN_WIRE_VERSION_FIRST      2u
#define HICHAIN_WIRE_VERSION_SECOND     0u
#define HICHAIN_WIRE_VERSION_THIRD      31u
#define HICHAIN_WIRE_MIN_FIRST          1u
#define HICHAIN_WIRE_MIN_SECOND         0u
#define HICHAIN_WIRE_MIN_THIRD          0u

/* Process-wide runtime switch. Default OFF. */
int      hichain_wire_is_enabled(void);
void     hichain_wire_set_enabled(int on);

/* Pure code tables (independent of the switch, so they are unit-testable).
 * Unknown codes pass through unchanged. */
uint16_t hichain_wire_out(uint16_t internal_code);
uint16_t hichain_wire_in(uint16_t wire_code);

/*
 * Rewrite the top-level "message" number of an already-serialised JSON
 * buffer into the AUTH_* range. Only the first "message" key is touched
 * (every make_*() helper emits it as the first field). No-op when the
 * switch is off or when the code does not map.
 * Returns 1 = rewritten, 0 = untouched, -1 = malformed / would overflow.
 */
int      hichain_wire_rewrite_json(char *json, size_t cap);

/* operationCode values accepted while the compatibility layer is on:
 * BIND(1), AUTHENTICATE(2), AUTH_KEY_AGREEMENT(6). */
int      hichain_wire_accept_op(int32_t op);

void     hichain_wire_version(uint32_t out[3]);
void     hichain_wire_min_version(uint32_t out[3]);

#endif /* HICHAIN_WIRE_H */
