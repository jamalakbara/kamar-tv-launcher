// JNI bridge between the RAOP server and com.tvkamar.launcher.airplay.RaopServer.

#include <jni.h>
#include <pthread.h>
#include <cstring>
#include "airplay/raop.h"
#include "airplay/logger.h"
#include "log.h"

static JavaVM *g_vm;
static pthread_key_t g_detach_key;
static jmethodID g_on_video, g_on_audio, g_on_volume, g_on_video_stop;

static void detach_thread(void *) {
    g_vm->DetachCurrentThread();
}

// Native threads attach once and detach when they exit, instead of on every frame.
static JNIEnv *env_for_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    pthread_setspecific(g_detach_key, env);
    return env;
}

extern "C" {

static void video_process(void *cls, h264_decode_struct *data) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jbyteArray bytes = env->NewByteArray(data->data_len);
    if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, data->data_len, reinterpret_cast<jbyte *>(data->data));
    env->CallVoidMethod(static_cast<jobject>(cls), g_on_video, bytes, (jint) data->frame_type,
                        (jlong) data->pts);
    env->DeleteLocalRef(bytes);
}

static void video_stop(void *cls) {
    JNIEnv *env = env_for_thread();
    if (env) env->CallVoidMethod(static_cast<jobject>(cls), g_on_video_stop);
}

static void audio_process(void *cls, pcm_data_struct *data) {
    JNIEnv *env = env_for_thread();
    if (!env) return;
    jshortArray samples = env->NewShortArray(data->data_len);
    if (!samples) return;
    env->SetShortArrayRegion(samples, 0, data->data_len, reinterpret_cast<jshort *>(data->data));
    env->CallVoidMethod(static_cast<jobject>(cls), g_on_audio, samples, (jlong) data->pts);
    env->DeleteLocalRef(samples);
}

static void audio_set_volume(void *cls, void *, float volume) {
    JNIEnv *env = env_for_thread();
    if (env) env->CallVoidMethod(static_cast<jobject>(cls), g_on_volume, (jfloat) volume);
}

static void log_callback(void *, int level, const char *msg) {
    switch (level) {
        case LOGGER_DEBUG: LOGD("%s", msg); break;
        case LOGGER_INFO: LOGI("%s", msg); break;
        case LOGGER_WARNING: LOGW("%s", msg); break;
        default: LOGE("%s", msg); break;
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_tvkamar_launcher_airplay_RaopServer_nativeStart(JNIEnv *env, jobject thiz, jbyteArray seed) {
    jclass cls = env->GetObjectClass(thiz);
    g_on_video = env->GetMethodID(cls, "onVideo", "([BIJ)V");
    g_on_audio = env->GetMethodID(cls, "onAudio", "([SJ)V");
    g_on_volume = env->GetMethodID(cls, "onVolume", "(F)V");
    g_on_video_stop = env->GetMethodID(cls, "onVideoStop", "()V");
    env->DeleteLocalRef(cls);

    unsigned char key_seed[32];
    env->GetByteArrayRegion(seed, 0, 32, reinterpret_cast<jbyte *>(key_seed));

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.cls = env->NewGlobalRef(thiz);
    cbs.audio_process = audio_process;
    cbs.audio_set_volume = audio_set_volume;
    cbs.video_process = video_process;
    cbs.video_stop = video_stop;
    raop_t *raop = raop_init(10, &cbs, key_seed);
    if (!raop) {
        env->DeleteGlobalRef(static_cast<jobject>(cbs.cls));
        LOGE("raop_init failed");
        return 0;
    }
    raop_set_log_callback(raop, log_callback, nullptr);
    raop_set_log_level(raop, RAOP_LOG_INFO);
    return reinterpret_cast<jlong>(raop);
}

JNIEXPORT jbyteArray JNICALL
Java_com_tvkamar_launcher_airplay_RaopServer_nativePublicKey(JNIEnv *env, jobject, jlong handle) {
    unsigned char pk[32];
    raop_get_public_key(reinterpret_cast<raop_t *>(handle), pk);
    jbyteArray out = env->NewByteArray(32);
    env->SetByteArrayRegion(out, 0, 32, reinterpret_cast<jbyte *>(pk));
    return out;
}

JNIEXPORT void JNICALL
Java_com_tvkamar_launcher_airplay_RaopServer_nativeSetInfo(
        JNIEnv *env, jobject, jlong handle, jstring name, jstring device_id, jlong features,
        jint width, jint height, jint max_fps, jbyteArray airplay_txt, jbyteArray raop_txt) {
    const char *c_name = env->GetStringUTFChars(name, nullptr);
    const char *c_id = env->GetStringUTFChars(device_id, nullptr);
    jbyte *a_txt = env->GetByteArrayElements(airplay_txt, nullptr);
    jbyte *r_txt = env->GetByteArrayElements(raop_txt, nullptr);
    raop_set_device_info(reinterpret_cast<raop_t *>(handle), c_name, c_id, (uint64_t) features,
                         width, height, max_fps,
                         reinterpret_cast<unsigned char *>(a_txt), env->GetArrayLength(airplay_txt),
                         reinterpret_cast<unsigned char *>(r_txt), env->GetArrayLength(raop_txt));
    env->ReleaseByteArrayElements(raop_txt, r_txt, JNI_ABORT);
    env->ReleaseByteArrayElements(airplay_txt, a_txt, JNI_ABORT);
    env->ReleaseStringUTFChars(device_id, c_id);
    env->ReleaseStringUTFChars(name, c_name);
}

// Starts listening on the standard AirPlay port (any free port if taken) and returns it, or 0.
JNIEXPORT jint JNICALL
Java_com_tvkamar_launcher_airplay_RaopServer_nativeListen(JNIEnv *, jobject, jlong handle) {
    raop_t *raop = reinterpret_cast<raop_t *>(handle);
    unsigned short port = 7000;
    if (raop_start(raop, &port) < 0) {
        port = 0;
        if (raop_start(raop, &port) < 0) return 0;
    }
    raop_set_port(raop, port);
    return port;
}

JNIEXPORT void JNICALL
Java_com_tvkamar_launcher_airplay_RaopServer_nativeStop(JNIEnv *env, jobject, jlong handle) {
    raop_t *raop = reinterpret_cast<raop_t *>(handle);
    jobject cls = static_cast<jobject>(raop_get_callback_cls(raop));
    raop_destroy(raop);
    env->DeleteGlobalRef(cls);
}

}
