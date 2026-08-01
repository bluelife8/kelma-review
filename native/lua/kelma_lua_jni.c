#include "kelma_lua_host.h"

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static kelma_lua_runtime *kelma_runtime(jlong handle) {
    return (kelma_lua_runtime *) (intptr_t) handle;
}

static jbyteArray kelma_new_bytes(JNIEnv *environment, const char *value) {
    jsize size = (jsize) strlen(value);
    jbyteArray result = (*environment)->NewByteArray(environment, size);
    if (result != NULL && size > 0) {
        (*environment)->SetByteArrayRegion(environment, result, 0, size, (const jbyte *) value);
    }
    return result;
}

static jstring kelma_new_string(JNIEnv *environment, const char *value) {
    jclass string_class = (*environment)->FindClass(environment, "java/lang/String");
    jmethodID constructor = (*environment)->GetMethodID(
        environment,
        string_class,
        "<init>",
        "([BLjava/lang/String;)V"
    );
    jbyteArray bytes = kelma_new_bytes(environment, value);
    jstring charset = (*environment)->NewStringUTF(environment, "UTF-8");
    return (jstring) (*environment)->NewObject(environment, string_class, constructor, bytes, charset);
}

static void kelma_throw(JNIEnv *environment, kelma_lua_runtime *runtime, const char *fallback) {
    jclass exception_class = (*environment)->FindClass(environment, "java/lang/IllegalStateException");
    jmethodID constructor = (*environment)->GetMethodID(
        environment,
        exception_class,
        "<init>",
        "(Ljava/lang/String;)V"
    );
    const char *message = runtime == NULL ? fallback : kelma_lua_last_error(runtime);
    jobject exception = (*environment)->NewObject(
        environment,
        exception_class,
        constructor,
        kelma_new_string(environment, message)
    );
    (*environment)->Throw(environment, (jthrowable) exception);
}

static char *kelma_copy_bytes(JNIEnv *environment, jbyteArray value) {
    jsize size = (*environment)->GetArrayLength(environment, value);
    char *result = (char *) malloc((size_t) size + 1);
    if (result == NULL) return NULL;
    if (size > 0) (*environment)->GetByteArrayRegion(environment, value, 0, size, (jbyte *) result);
    result[size] = '\0';
    return result;
}

JNIEXPORT jlong JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeCreate(
    JNIEnv *environment,
    jobject receiver,
    jstring plugin_id,
    jint capabilities,
    jlong memory_limit,
    jlong instruction_limit
) {
    (void) receiver;
    const char *id = (*environment)->GetStringUTFChars(environment, plugin_id, NULL);
    if (id == NULL) return 0;
    kelma_lua_runtime *runtime = kelma_lua_new(
        id,
        (unsigned int) capabilities,
        (size_t) memory_limit,
        (long long) instruction_limit
    );
    (*environment)->ReleaseStringUTFChars(environment, plugin_id, id);
    if (runtime == NULL) kelma_throw(environment, NULL, "Could not create Lua 5.4 runtime");
    return (jlong) (intptr_t) runtime;
}

JNIEXPORT void JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeAddFile(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jstring path,
    jbyteArray source
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    const char *path_value = (*environment)->GetStringUTFChars(environment, path, NULL);
    jbyte *source_value = (*environment)->GetByteArrayElements(environment, source, NULL);
    if (path_value == NULL || source_value == NULL) return;
    jsize source_size = (*environment)->GetArrayLength(environment, source);
    int result = kelma_lua_add_file(
        runtime,
        path_value,
        (const char *) source_value,
        (size_t) source_size
    );
    (*environment)->ReleaseByteArrayElements(environment, source, source_value, JNI_ABORT);
    (*environment)->ReleaseStringUTFChars(environment, path, path_value);
    if (!result) kelma_throw(environment, runtime, "Could not add Lua plugin file");
}

JNIEXPORT void JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeStart(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jbyteArray bootstrap,
    jstring entrypoint
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    jbyte *bootstrap_value = (*environment)->GetByteArrayElements(environment, bootstrap, NULL);
    const char *entrypoint_value = (*environment)->GetStringUTFChars(environment, entrypoint, NULL);
    if (bootstrap_value == NULL || entrypoint_value == NULL) return;
    jsize bootstrap_size = (*environment)->GetArrayLength(environment, bootstrap);
    int result = kelma_lua_start(
        runtime,
        (const char *) bootstrap_value,
        (size_t) bootstrap_size,
        entrypoint_value
    );
    (*environment)->ReleaseByteArrayElements(environment, bootstrap, bootstrap_value, JNI_ABORT);
    (*environment)->ReleaseStringUTFChars(environment, entrypoint, entrypoint_value);
    if (!result) kelma_throw(environment, runtime, "Could not start Lua plugin");
}

JNIEXPORT jint JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeCount(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jint kind
) {
    (void) environment;
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    if (kind == 0) return kelma_lua_command_count(runtime);
    if (kind == 1) return kelma_lua_event_count(runtime);
    if (kind == 2) return kelma_lua_renderer_count(runtime);
    if (kind == 3) return kelma_lua_log_count(runtime);
    return 0;
}

JNIEXPORT jbyteArray JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeMetadata(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jint kind,
    jint index,
    jint field
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    const char *value = NULL;
    if (kind == 0) value = field == 0 ? kelma_lua_command_id(runtime, index) : kelma_lua_command_title(runtime, index);
    else if (kind == 1) value = kelma_lua_event_name(runtime, index);
    else if (kind == 2) value = kelma_lua_renderer_id(runtime, index);
    else if (kind == 3) {
        value = field == 0 ? kelma_lua_log_level(runtime, index) : kelma_lua_log_message(runtime, index);
    }
    if (value == NULL) {
        kelma_throw(environment, runtime, "Lua plugin metadata is unavailable");
        return NULL;
    }
    return kelma_new_bytes(environment, value);
}

JNIEXPORT jbyteArray JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeInvoke(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jstring command_id,
    jbyteArray arguments_json
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    const char *id = (*environment)->GetStringUTFChars(environment, command_id, NULL);
    char *arguments = kelma_copy_bytes(environment, arguments_json);
    if (id == NULL || arguments == NULL) {
        free(arguments);
        return NULL;
    }
    char *result = NULL;
    int status = kelma_lua_invoke_command(runtime, id, arguments, &result);
    free(arguments);
    (*environment)->ReleaseStringUTFChars(environment, command_id, id);
    if (!status) {
        kelma_throw(environment, runtime, "Lua command failed");
        return NULL;
    }
    jbyteArray encoded = kelma_new_bytes(environment, result);
    kelma_lua_free_string(result);
    return encoded;
}

JNIEXPORT void JNICALL Java_tech_kelma_app_LuaNativeBridge_nativePublish(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jstring event_name,
    jbyteArray attributes_json
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    const char *name = (*environment)->GetStringUTFChars(environment, event_name, NULL);
    char *attributes = kelma_copy_bytes(environment, attributes_json);
    if (name == NULL || attributes == NULL) {
        free(attributes);
        return;
    }
    int status = kelma_lua_publish_event(runtime, name, attributes);
    free(attributes);
    (*environment)->ReleaseStringUTFChars(environment, event_name, name);
    if (!status) kelma_throw(environment, runtime, "Lua event failed");
}

JNIEXPORT jobjectArray JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeRender(
    JNIEnv *environment,
    jobject receiver,
    jlong handle,
    jstring renderer_id,
    jbyteArray html,
    jbyteArray css
) {
    (void) receiver;
    kelma_lua_runtime *runtime = kelma_runtime(handle);
    const char *id_value = (*environment)->GetStringUTFChars(environment, renderer_id, NULL);
    char *html_value = kelma_copy_bytes(environment, html);
    char *css_value = kelma_copy_bytes(environment, css);
    if (id_value == NULL || html_value == NULL || css_value == NULL) {
        free(html_value);
        free(css_value);
        if (id_value != NULL) (*environment)->ReleaseStringUTFChars(environment, renderer_id, id_value);
        return NULL;
    }
    char *result_html = NULL;
    char *result_css = NULL;
    int status = kelma_lua_render(runtime, id_value, html_value, css_value, &result_html, &result_css);
    free(html_value);
    free(css_value);
    (*environment)->ReleaseStringUTFChars(environment, renderer_id, id_value);
    if (!status) {
        kelma_lua_free_string(result_html);
        kelma_lua_free_string(result_css);
        kelma_throw(environment, runtime, "Lua renderer failed");
        return NULL;
    }
    jclass byte_array_class = (*environment)->FindClass(environment, "[B");
    jobjectArray result = (*environment)->NewObjectArray(environment, 2, byte_array_class, NULL);
    (*environment)->SetObjectArrayElement(environment, result, 0, kelma_new_bytes(environment, result_html));
    (*environment)->SetObjectArrayElement(environment, result, 1, kelma_new_bytes(environment, result_css));
    kelma_lua_free_string(result_html);
    kelma_lua_free_string(result_css);
    return result;
}

JNIEXPORT void JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeClearLogs(
    JNIEnv *environment,
    jobject receiver,
    jlong handle
) {
    (void) environment;
    (void) receiver;
    kelma_lua_clear_logs(kelma_runtime(handle));
}

JNIEXPORT void JNICALL Java_tech_kelma_app_LuaNativeBridge_nativeClose(
    JNIEnv *environment,
    jobject receiver,
    jlong handle
) {
    (void) environment;
    (void) receiver;
    kelma_lua_close(kelma_runtime(handle));
}
