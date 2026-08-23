#include <jni.h>
#include <stdlib.h>
#include "libclash.h"

static JavaVM *vm;

JNIEXPORT jint JNI_OnLoad(JavaVM *javaVm, void *reserved) {
    (void) reserved;
    vm = javaVm;
    return JNI_VERSION_1_6;
}

static JNIEnv *get_env(int *detach) {
    JNIEnv *env = NULL;
    *detach = 0;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return NULL;
    *detach = 1;
    return env;
}

static void release_env(int detach) {
    if (detach) (*vm)->DetachCurrentThread(vm);
}

static const char *read_string(JNIEnv *env, jstring string) {
    if (string == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, string, NULL);
}

static void release_string(JNIEnv *env, jstring string, const char *value) {
    if (string != NULL && value != NULL) (*env)->ReleaseStringUTFChars(env, string, value);
}

static jstring take_error(JNIEnv *env, char *error) {
    if (error == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, error);
    free(error);
    return result;
}

int protect_socket(void *callback, int fd) {
    int detach;
    JNIEnv *env = get_env(&detach);
    if (env == NULL) return 0;
    jclass type = (*env)->GetObjectClass(env, callback);
    jmethodID method = (*env)->GetMethodID(env, type, "protectSocket", "(I)Z");
    jboolean result = method == NULL ? JNI_FALSE : (*env)->CallBooleanMethod(env, callback, method, fd);
    (*env)->DeleteLocalRef(env, type);
    release_env(detach);
    return result == JNI_TRUE;
}

int query_socket_uid(void *callback, int protocol, char *source, char *target) {
    int detach;
    JNIEnv *env = get_env(&detach);
    if (env == NULL) return -1;
    jclass type = (*env)->GetObjectClass(env, callback);
    jmethodID method = (*env)->GetMethodID(env, type, "querySocketUid", "(ILjava/lang/String;Ljava/lang/String;)I");
    jstring sourceString = (*env)->NewStringUTF(env, source);
    jstring targetString = (*env)->NewStringUTF(env, target);
    jint result = method == NULL ? -1 : (*env)->CallIntMethod(env, callback, method, protocol, sourceString, targetString);
    (*env)->DeleteLocalRef(env, sourceString);
    (*env)->DeleteLocalRef(env, targetString);
    (*env)->DeleteLocalRef(env, type);
    release_env(detach);
    return result;
}

void release_callback(void *callback) {
    int detach;
    JNIEnv *env = get_env(&detach);
    if (env != NULL) {
        (*env)->DeleteGlobalRef(env, callback);
        release_env(detach);
    }
}

JNIEXPORT void JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeInit(
    JNIEnv *env, jobject self, jstring home, jint sdkVersion) {
    (void) self;
    const char *homeValue = read_string(env, home);
    coreInit((char *) homeValue, sdkVersion);
    release_string(env, home, homeValue);
}

JNIEXPORT jstring JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeValidate(
    JNIEnv *env, jobject self, jstring content, jstring controller, jstring secret) {
    (void) self;
    const char *contentValue = read_string(env, content);
    const char *controllerValue = read_string(env, controller);
    const char *secretValue = read_string(env, secret);
    char *error = validateConfig((char *) contentValue, (char *) controllerValue, (char *) secretValue);
    release_string(env, content, contentValue);
    release_string(env, controller, controllerValue);
    release_string(env, secret, secretValue);
    return take_error(env, error);
}

JNIEXPORT jstring JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeDescribeProxyGroups(
    JNIEnv *env, jobject self, jstring content) {
    (void) self;
    const char *contentValue = read_string(env, content);
    char *response = describeProxyGroups((char *) contentValue);
    release_string(env, content, contentValue);
    return take_error(env, response);
}

JNIEXPORT jstring JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeLoad(
    JNIEnv *env, jobject self, jstring content, jstring controller, jstring secret, jint httpProxyPort) {
    (void) self;
    const char *contentValue = read_string(env, content);
    const char *controllerValue = read_string(env, controller);
    const char *secretValue = read_string(env, secret);
    char *error = loadConfig((char *) contentValue, (char *) controllerValue, (char *) secretValue, httpProxyPort);
    release_string(env, content, contentValue);
    release_string(env, controller, controllerValue);
    release_string(env, secret, secretValue);
    return take_error(env, error);
}

JNIEXPORT jstring JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeSetMode(
    JNIEnv *env, jobject self, jstring mode) {
    (void) self;
    const char *modeValue = read_string(env, mode);
    char *error = setMode((char *) modeValue);
    release_string(env, mode, modeValue);
    return take_error(env, error);
}

JNIEXPORT jstring JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeStartTun(
    JNIEnv *env, jobject self, jint fd, jstring stack, jstring gateway, jstring dns) {
    (void) self;
    const char *stackValue = read_string(env, stack);
    const char *gatewayValue = read_string(env, gateway);
    const char *dnsValue = read_string(env, dns);
    char *error = startTun(fd, (char *) stackValue, (char *) gatewayValue, (char *) dnsValue);
    release_string(env, stack, stackValue);
    release_string(env, gateway, gatewayValue);
    release_string(env, dns, dnsValue);
    return take_error(env, error);
}

JNIEXPORT void JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativePrepareTun(
    JNIEnv *env, jobject self, jobject callback) {
    (void) self;
    jobject callbackGlobal = (*env)->NewGlobalRef(env, callback);
    prepareTun(callbackGlobal);
}

JNIEXPORT void JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeStopTun(JNIEnv *env, jobject self) {
    (void) env; (void) self; stopTun();
}

JNIEXPORT void JNICALL Java_io_nekohasekai_sfa_mihomo_MihomoNativeBridge_nativeStopCore(JNIEnv *env, jobject self) {
    (void) env; (void) self; stopCore();
}
