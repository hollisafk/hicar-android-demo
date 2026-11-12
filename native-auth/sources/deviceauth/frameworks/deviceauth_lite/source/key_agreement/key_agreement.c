/*
 * Copyright (c) 2020 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "key_agreement.h"
#include "base.h"
#include "log.h"
#ifdef _ENABLE_WIRE_AUTH_
#include "hichain_wire.h"
#endif

#ifdef DESC
#undef DESC
#endif
#define DESC(...) 1

static uint32_t g_sn_generator = 1;
static struct key_agreement_version g_current_version = { 1, 0, 0 };

#if DESC("interface")
void init_protocol(struct key_agreement_protocol *handle)
{
    check_ptr_return(handle);
    handle->state = PROTOCOL_INIT;
    handle->last_state = PROTOCOL_INIT;
    handle->last_time_sec = 0;
    handle->sn = g_sn_generator++;
}

void set_state(struct key_agreement_protocol *handle, enum protocol_state new_state)
{
    check_ptr_return(handle);
    enum protocol_state ori_state = handle->state;

    DBG_OUT("Object %u state is %u, new state is %u", handle->sn, ori_state, new_state);
    if ((ori_state == PROTOCOL_TIMEOUT) || (ori_state == PROTOCOL_ERROR) || (ori_state == PROTOCOL_FINISH)) {
        LOGE("Object %u state cannot change", handle->sn);
        return; /* not allowed to modify these end status */
    }
    if (new_state < ori_state) {
        LOGE("Object %u state cannot rollback", handle->sn);
        return;
    }

    if (handle->state != new_state) {
        handle->last_state = ori_state;
        handle->state = new_state;
    }
}

void set_last_time_sec(struct key_agreement_protocol *handle)
{
    check_ptr_return(handle);
    handle->last_time_sec = time((time_t *)NULL);
}

struct key_agreement_version get_current_version(void)
{
#ifdef _ENABLE_WIRE_AUTH_
    /* Huawei reads its algorithm mask from the third component of
     * currentVersion ("2.0.31"); a peer still sending "1.0.0" is treated
     * as not supporting PSK_SPEKE. The comparison baseline used by
     * is_peer_support_current_version() stays at 1.0.0 so older self-built
     * peers keep working. */
    if (hichain_wire_is_enabled()) {
        uint32_t v[3] = { 0, 0, 0 };
        struct key_agreement_version out = { 1, 0, 0 };
        hichain_wire_version(v);
        out.first = v[0];
        out.second = v[1];
        out.third = v[2];
        return out;
    }
#endif
    return g_current_version;
}

struct key_agreement_version get_min_version(void)
{
    struct key_agreement_version v = { 1, 0, 0 };
    return v;
}

static bool is_version_bigger(struct key_agreement_version *dst, struct key_agreement_version *src);
static bool is_version_lesser(struct key_agreement_version *dst, struct key_agreement_version *src);
bool is_peer_support_current_version(struct key_agreement_version *peer_version,
    struct key_agreement_version *peer_support_version)
{
    DBG_OUT("Peer version: %u.%u.%u, support version: %u.%u.%u, current version: %u.%u.%u",
            peer_version->first, peer_version->second, peer_version->third,
            peer_support_version->first, peer_support_version->second, peer_support_version->third,
            g_current_version.first, g_current_version.second, g_current_version.third);
    if (is_version_bigger(peer_version, &g_current_version)) {
        return false;
    }
    if (is_version_lesser(peer_support_version, &g_current_version)) {
        return false;
    }
    return true;
}

static bool is_version_bigger(struct key_agreement_version *dst, struct key_agreement_version *src)
{
    uint32_t *dst_ptr = (uint32_t *)dst;
    uint32_t *src_ptr = (uint32_t *)src;

    for (uint32_t i = 0; i < sizeof(struct key_agreement_version) / sizeof(uint32_t); i++) {
        if (src_ptr[i] > dst_ptr[i]) {
            return true;
        }
        if (src_ptr[i] < dst_ptr[i]) {
            return false;
        }
    }
    return false;
}

static bool is_version_lesser(struct key_agreement_version *dst, struct key_agreement_version *src)
{
    uint32_t *dst_ptr = (uint32_t *)dst;
    uint32_t *src_ptr = (uint32_t *)src;

    for (uint32_t i = 0; i < sizeof(struct key_agreement_version) / sizeof(uint32_t); i++) {
        if (src_ptr[i] < dst_ptr[i]) {
            return true;
        }
        if (src_ptr[i] > dst_ptr[i]) {
            return false;
        }
    }
    return false;
}
#endif /* DESC */

