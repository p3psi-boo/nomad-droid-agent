#include <jni.h>
#include <stdlib.h>

extern int NomadStart(const char *raw);
extern void NomadStop(void);
extern char *NomadStatus(void);
extern void NomadFree(char *value);

JNIEXPORT jint JNICALL
Java_com_nomad_droid_runtime_NomadNative_nativeStart(
    JNIEnv *env,
    jobject receiver,
    jstring config_json) {
  (void)receiver;
  if (config_json == NULL) {
    return 2;
  }
  const char *raw = (*env)->GetStringUTFChars(env, config_json, NULL);
  if (raw == NULL) {
    return 2;
  }
  int result = NomadStart(raw);
  (*env)->ReleaseStringUTFChars(env, config_json, raw);
  return result;
}

JNIEXPORT void JNICALL
Java_com_nomad_droid_runtime_NomadNative_nativeStop(
    JNIEnv *env,
    jobject receiver) {
  (void)env;
  (void)receiver;
  NomadStop();
}

JNIEXPORT jstring JNICALL
Java_com_nomad_droid_runtime_NomadNative_nativeStatus(
    JNIEnv *env,
    jobject receiver) {
  (void)receiver;
  char *status = NomadStatus();
  if (status == NULL) {
    return (*env)->NewStringUTF(env, "{\"state\":\"failed\"}");
  }
  jstring result = (*env)->NewStringUTF(env, status);
  NomadFree(status);
  return result;
}
