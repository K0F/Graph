#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

static uint64_t fnv64(const uint8_t *p, size_t n) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < n; i++) {
        h ^= (uint64_t)p[i];
        h *= 0x100000001b3ULL;
    }
    return h;
}

static uint64_t splitmix64(uint64_t *s) {
    uint64_t z = (*s += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}

/* Equivalent to `dada --img noise.png bytes 8 <slot>` for each slot 0..count-1 */
JNIEXPORT jlongArray JNICALL
Java_cz_kof_graph_MainActivity_dadaBytes64(JNIEnv *env, jclass cls,
                                           jbyteArray stoneBytes, jint count, jlong baseSeed) {
    (void)cls;
    if (count <= 0) return (*env)->NewLongArray(env, 0);
    jsize len = (*env)->GetArrayLength(env, stoneBytes);
    jbyte *raw = (*env)->GetByteArrayElements(env, stoneBytes, NULL);
    uint64_t master = fnv64((const uint8_t *)raw, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, stoneBytes, raw, JNI_ABORT);

    jlongArray out = (*env)->NewLongArray(env, count);
    jlong *buf = (*env)->GetLongArrayElements(env, out, NULL);
    for (int i = 0; i < count; i++) {
        char arg[32];
        snprintf(arg, sizeof(arg), "%lld", (long long)baseSeed + i);
        uint64_t s = master ^ fnv64((const uint8_t *)arg, strlen(arg));
        uint64_t val = 0;
        for (int b = 0; b < 8; b++) {
            uint8_t byte_v = (uint8_t)(splitmix64(&s) >> 56);
            val = (val << 8) | byte_v;
        }
        buf[i] = (jlong)(val & 0x7fffffffffffffffULL);
    }
    (*env)->ReleaseLongArrayElements(env, out, buf, 0);
    return out;
}
