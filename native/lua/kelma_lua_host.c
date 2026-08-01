#include "kelma_lua_internal.h"

#include "src/lualib.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define KELMA_MAX_FILES 512
#define KELMA_MAX_REGISTRATIONS 512
#define KELMA_MAX_LOGS 500
#define KELMA_MAX_LOG_MESSAGE 2048

char *kelma_duplicate(const char *value) {
    size_t size = strlen(value) + 1;
    char *copy = (char *) malloc(size);
    if (copy != NULL) memcpy(copy, value, size);
    return copy;
}

static char *kelma_duplicate_sized(const char *value, size_t size) {
    char *copy = (char *) malloc(size + 1);
    if (copy != NULL) {
        memcpy(copy, value, size);
        copy[size] = '\0';
    }
    return copy;
}

void kelma_set_error(kelma_lua_runtime *runtime, const char *message) {
    if (message == NULL) message = "Lua runtime failed";
    snprintf(runtime->last_error, sizeof(runtime->last_error), "%s", message);
}

static void kelma_capture_lua_error(kelma_lua_runtime *runtime) {
    kelma_set_error(runtime, lua_tostring(runtime->state, -1));
    lua_settop(runtime->state, 0);
}

static void *kelma_allocator(void *userdata, void *pointer, size_t old_size, size_t new_size) {
    kelma_lua_runtime *runtime = (kelma_lua_runtime *) userdata;
    if (new_size == 0) {
        if (pointer != NULL) {
            runtime->memory_used = old_size <= runtime->memory_used ? runtime->memory_used - old_size : 0;
            free(pointer);
        }
        return NULL;
    }
    size_t growth = pointer == NULL ? new_size : (new_size > old_size ? new_size - old_size : 0);
    if (growth > runtime->memory_limit - runtime->memory_used) return NULL;
    void *result = realloc(pointer, new_size);
    if (result == NULL) return NULL;
    if (pointer == NULL) runtime->memory_used += new_size;
    else if (new_size >= old_size) runtime->memory_used += new_size - old_size;
    else runtime->memory_used -= old_size - new_size;
    return result;
}

static void kelma_instruction_hook(lua_State *state, lua_Debug *debug) {
    (void) debug;
    kelma_lua_runtime *runtime = *(kelma_lua_runtime **) lua_getextraspace(state);
    runtime->instructions_remaining -= 1000;
    if (runtime->instructions_remaining <= 0) luaL_error(state, "plugin instruction limit exceeded");
}

int kelma_pcall(kelma_lua_runtime *runtime, int arguments, int results) {
    runtime->instructions_remaining = runtime->instruction_limit;
    lua_sethook(runtime->state, kelma_instruction_hook, LUA_MASKCOUNT, 1000);
    int status = lua_pcall(runtime->state, arguments, results, 0);
    lua_sethook(runtime->state, NULL, 0, 0);
    if (status != LUA_OK) {
        kelma_capture_lua_error(runtime);
        return 0;
    }
    return 1;
}

static int kelma_safe_identifier(const char *identifier) {
    size_t size = strlen(identifier);
    if (size == 0 || size > 200) return 0;
    for (size_t index = 0; index < size; index++) {
        char value = identifier[index];
        if (!((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') || value == '.' || value == '_' || value == '-')) return 0;
    }
    return 1;
}

static int kelma_has_namespace(kelma_lua_runtime *runtime, const char *identifier) {
    size_t prefix_size = strlen(runtime->plugin_id);
    return kelma_safe_identifier(identifier) && strncmp(identifier, runtime->plugin_id, prefix_size) == 0 &&
        identifier[prefix_size] == '.';
}

static kelma_lua_runtime *kelma_upvalue_runtime(lua_State *state) {
    return (kelma_lua_runtime *) lua_touserdata(state, lua_upvalueindex(1));
}

static int kelma_register_command(lua_State *state) {
    kelma_lua_runtime *runtime = kelma_upvalue_runtime(state);
    if (runtime->started) return luaL_error(state, "commands must be registered during startup");
    if ((runtime->capabilities & KELMA_LUA_CAP_COMMANDS) == 0) return luaL_error(state, "Commands capability denied");
    const char *id = luaL_checkstring(state, 1);
    size_t title_size = 0;
    const char *title = luaL_checklstring(state, 2, &title_size);
    luaL_checktype(state, 3, LUA_TFUNCTION);
    if (!kelma_has_namespace(runtime, id)) return luaL_error(state, "command id must be namespaced by plugin id");
    if (title_size == 0 || title_size > 200) return luaL_error(state, "command title is invalid");
    if (runtime->command_count >= KELMA_MAX_REGISTRATIONS) return luaL_error(state, "too many plugin commands");
    for (int index = 0; index < runtime->command_count; index++) {
        if (strcmp(runtime->commands[index].id, id) == 0) return luaL_error(state, "command is already registered");
    }
    struct kelma_registration *resized = (struct kelma_registration *) realloc(
        runtime->commands,
        sizeof(struct kelma_registration) * (size_t) (runtime->command_count + 1)
    );
    if (resized == NULL) return luaL_error(state, "could not register command");
    runtime->commands = resized;
    struct kelma_registration *command = &runtime->commands[runtime->command_count++];
    command->id = kelma_duplicate(id);
    command->title = kelma_duplicate(title);
    lua_pushvalue(state, 3);
    command->reference = luaL_ref(state, LUA_REGISTRYINDEX);
    if (command->id == NULL || command->title == NULL) return luaL_error(state, "could not register command");
    return 0;
}

static int kelma_subscribe_event(lua_State *state) {
    kelma_lua_runtime *runtime = kelma_upvalue_runtime(state);
    if (runtime->started) return luaL_error(state, "events must be registered during startup");
    if ((runtime->capabilities & KELMA_LUA_CAP_EVENTS) == 0) return luaL_error(state, "Events capability denied");
    const char *name = luaL_checkstring(state, 1);
    luaL_checktype(state, 2, LUA_TFUNCTION);
    if (!kelma_safe_identifier(name)) return luaL_error(state, "event name is invalid");
    if (runtime->event_count >= KELMA_MAX_REGISTRATIONS) return luaL_error(state, "too many event subscriptions");
    struct kelma_event *resized = (struct kelma_event *) realloc(
        runtime->events,
        sizeof(struct kelma_event) * (size_t) (runtime->event_count + 1)
    );
    if (resized == NULL) return luaL_error(state, "could not subscribe to event");
    runtime->events = resized;
    struct kelma_event *event = &runtime->events[runtime->event_count++];
    event->name = kelma_duplicate(name);
    lua_pushvalue(state, 2);
    event->reference = luaL_ref(state, LUA_REGISTRYINDEX);
    if (event->name == NULL) return luaL_error(state, "could not subscribe to event");
    return 0;
}

static int kelma_register_renderer(lua_State *state) {
    kelma_lua_runtime *runtime = kelma_upvalue_runtime(state);
    if (runtime->started) return luaL_error(state, "renderers must be registered during startup");
    if ((runtime->capabilities & KELMA_LUA_CAP_UI) == 0) return luaL_error(state, "Ui capability denied");
    const char *id = luaL_checkstring(state, 1);
    luaL_checktype(state, 2, LUA_TFUNCTION);
    if (!kelma_has_namespace(runtime, id)) return luaL_error(state, "renderer id must be namespaced by plugin id");
    if (runtime->renderer_count >= KELMA_MAX_REGISTRATIONS) return luaL_error(state, "too many renderers");
    struct kelma_registration *resized = (struct kelma_registration *) realloc(
        runtime->renderers,
        sizeof(struct kelma_registration) * (size_t) (runtime->renderer_count + 1)
    );
    if (resized == NULL) return luaL_error(state, "could not register renderer");
    runtime->renderers = resized;
    struct kelma_registration *renderer = &runtime->renderers[runtime->renderer_count++];
    renderer->id = kelma_duplicate(id);
    renderer->title = kelma_duplicate(id);
    lua_pushvalue(state, 2);
    renderer->reference = luaL_ref(state, LUA_REGISTRYINDEX);
    if (renderer->id == NULL || renderer->title == NULL) return luaL_error(state, "could not register renderer");
    return 0;
}

static int kelma_write_log(lua_State *state) {
    kelma_lua_runtime *runtime = kelma_upvalue_runtime(state);
    const char *level = luaL_checkstring(state, 1);
    size_t message_size = 0;
    const char *message = luaL_checklstring(state, 2, &message_size);
    if (message_size > KELMA_MAX_LOG_MESSAGE) {
        message_size = KELMA_MAX_LOG_MESSAGE;
        while (message_size > 0 && ((unsigned char) message[message_size] & 0xc0u) == 0x80u) {
            message_size--;
        }
    }
    if (runtime->log_count == KELMA_MAX_LOGS) {
        free(runtime->logs[0].level);
        free(runtime->logs[0].message);
        memmove(runtime->logs, runtime->logs + 1, sizeof(struct kelma_log) * (KELMA_MAX_LOGS - 1));
        runtime->log_count--;
    }
    char *level_copy = kelma_duplicate(level);
    char *message_copy = kelma_duplicate_sized(message, message_size);
    if (level_copy == NULL || message_copy == NULL) {
        free(level_copy);
        free(message_copy);
        return luaL_error(state, "could not write plugin log");
    }
    struct kelma_log *resized = (struct kelma_log *) realloc(
        runtime->logs,
        sizeof(struct kelma_log) * (size_t) (runtime->log_count + 1)
    );
    if (resized == NULL) {
        free(level_copy);
        free(message_copy);
        return luaL_error(state, "could not write plugin log");
    }
    runtime->logs = resized;
    runtime->logs[runtime->log_count].level = level_copy;
    runtime->logs[runtime->log_count].message = message_copy;
    runtime->log_count++;
    return 0;
}

static const struct kelma_file *kelma_find_file(kelma_lua_runtime *runtime, const char *path) {
    for (int index = runtime->file_count - 1; index >= 0; index--) {
        if (strcmp(runtime->files[index].path, path) == 0) return &runtime->files[index];
    }
    return NULL;
}

static int kelma_module_searcher(lua_State *state) {
    kelma_lua_runtime *runtime = kelma_upvalue_runtime(state);
    const char *module = luaL_checkstring(state, 1);
    if (strstr(module, "..") != NULL || strchr(module, '/') != NULL || strchr(module, '\\') != NULL) {
        lua_pushliteral(state, "\n\tinvalid Kelma module name");
        return 1;
    }
    char path[1024];
    size_t module_size = strlen(module);
    if (module_size > 900) {
        lua_pushliteral(state, "\n\tKelma module name is too long");
        return 1;
    }
    char translated[920];
    for (size_t index = 0; index <= module_size; index++) {
        translated[index] = module[index] == '.' ? '/' : module[index];
    }
    const struct kelma_file *file = NULL;
    snprintf(path, sizeof(path), "lua/%s.lua", translated);
    file = kelma_find_file(runtime, path);
    if (file == NULL) {
        snprintf(path, sizeof(path), "lua/%s/init.lua", translated);
        file = kelma_find_file(runtime, path);
    }
    if (file == NULL) {
        lua_pushfstring(state, "\n\tno Kelma module '%s'", module);
        return 1;
    }
    char chunk_name[1200];
    snprintf(chunk_name, sizeof(chunk_name), "@%s:%s", runtime->plugin_id, path);
    if (luaL_loadbufferx(state, file->source, file->source_size, chunk_name, "t") != LUA_OK) return 1;
    return 1;
}

static void kelma_open_safe_libraries(kelma_lua_runtime *runtime) {
    static const luaL_Reg libraries[] = {
        {LUA_GNAME, luaopen_base},
        {LUA_LOADLIBNAME, luaopen_package},
        {LUA_COLIBNAME, luaopen_coroutine},
        {LUA_TABLIBNAME, luaopen_table},
        {LUA_STRLIBNAME, luaopen_string},
        {LUA_MATHLIBNAME, luaopen_math},
        {LUA_UTF8LIBNAME, luaopen_utf8},
        {NULL, NULL},
    };
    for (const luaL_Reg *library = libraries; library->func != NULL; library++) {
        luaL_requiref(runtime->state, library->name, library->func, 1);
        lua_pop(runtime->state, 1);
    }
    const char *removed_globals[] = {
        "dofile", "loadfile", "load", "collectgarbage", "io", "os", "debug", NULL,
    };
    for (const char **name = removed_globals; *name != NULL; name++) {
        lua_pushnil(runtime->state);
        lua_setglobal(runtime->state, *name);
    }
    lua_getglobal(runtime->state, LUA_STRLIBNAME);
    lua_pushnil(runtime->state);
    lua_setfield(runtime->state, -2, "dump");
    lua_pop(runtime->state, 1);
    lua_getglobal(runtime->state, LUA_LOADLIBNAME);
    lua_pushnil(runtime->state);
    lua_setfield(runtime->state, -2, "loadlib");
    lua_pushnil(runtime->state);
    lua_setfield(runtime->state, -2, "searchpath");
    lua_pushliteral(runtime->state, "");
    lua_setfield(runtime->state, -2, "path");
    lua_pushliteral(runtime->state, "");
    lua_setfield(runtime->state, -2, "cpath");
    lua_newtable(runtime->state);
    lua_pushlightuserdata(runtime->state, runtime);
    lua_pushcclosure(runtime->state, kelma_module_searcher, 1);
    lua_rawseti(runtime->state, -2, 1);
    lua_setfield(runtime->state, -2, "searchers");
    lua_pop(runtime->state, 1);
}

static void kelma_set_native_function(kelma_lua_runtime *runtime, const char *name, lua_CFunction function) {
    lua_pushlightuserdata(runtime->state, runtime);
    lua_pushcclosure(runtime->state, function, 1);
    lua_setfield(runtime->state, -2, name);
}

static void kelma_create_host_table(kelma_lua_runtime *runtime) {
    lua_newtable(runtime->state);
    kelma_set_native_function(runtime, "_register_command", kelma_register_command);
    kelma_set_native_function(runtime, "_subscribe_event", kelma_subscribe_event);
    kelma_set_native_function(runtime, "_register_renderer", kelma_register_renderer);
    kelma_set_native_function(runtime, "_write_log", kelma_write_log);
    lua_newtable(runtime->state);
    lua_pushboolean(runtime->state, (runtime->capabilities & KELMA_LUA_CAP_COMMANDS) != 0);
    lua_setfield(runtime->state, -2, "commands");
    lua_pushboolean(runtime->state, (runtime->capabilities & KELMA_LUA_CAP_EVENTS) != 0);
    lua_setfield(runtime->state, -2, "events");
    lua_pushboolean(runtime->state, (runtime->capabilities & KELMA_LUA_CAP_UI) != 0);
    lua_setfield(runtime->state, -2, "ui");
    lua_setfield(runtime->state, -2, "capabilities");
    lua_setglobal(runtime->state, "kelma");
}

int kelma_push_decoded(kelma_lua_runtime *runtime, const char *json) {
    lua_getglobal(runtime->state, "kelma");
    lua_getfield(runtime->state, -1, "_decode");
    lua_remove(runtime->state, -2);
    if (!lua_isfunction(runtime->state, -1)) {
        kelma_set_error(runtime, "Kelma JSON decoder is unavailable");
        lua_settop(runtime->state, 0);
        return 0;
    }
    lua_pushstring(runtime->state, json);
    return kelma_pcall(runtime, 1, 1);
}

int kelma_encode_top(kelma_lua_runtime *runtime, char **result_json) {
    lua_getglobal(runtime->state, "kelma");
    lua_getfield(runtime->state, -1, "_encode");
    lua_remove(runtime->state, -2);
    lua_insert(runtime->state, -2);
    if (!kelma_pcall(runtime, 1, 1)) return 0;
    const char *encoded = lua_tostring(runtime->state, -1);
    if (encoded == NULL) {
        kelma_set_error(runtime, "Plugin returned a value that cannot cross the API boundary");
        lua_settop(runtime->state, 0);
        return 0;
    }
    *result_json = kelma_duplicate(encoded);
    lua_settop(runtime->state, 0);
    if (*result_json == NULL) {
        kelma_set_error(runtime, "Could not allocate plugin result");
        return 0;
    }
    return 1;
}

kelma_lua_runtime *kelma_lua_new(
    const char *plugin_id,
    unsigned int capabilities,
    size_t memory_limit,
    long long instruction_limit
) {
    if (plugin_id == NULL || memory_limit < 1024 * 1024 || instruction_limit < 1000) return NULL;
    kelma_lua_runtime *runtime = (kelma_lua_runtime *) calloc(1, sizeof(kelma_lua_runtime));
    if (runtime == NULL) return NULL;
    runtime->plugin_id = kelma_duplicate(plugin_id);
    runtime->capabilities = capabilities;
    runtime->memory_limit = memory_limit;
    runtime->instruction_limit = instruction_limit;
    if (runtime->plugin_id == NULL) {
        kelma_lua_close(runtime);
        return NULL;
    }
    runtime->state = lua_newstate(kelma_allocator, runtime);
    if (runtime->state == NULL) {
        kelma_lua_close(runtime);
        return NULL;
    }
    *(kelma_lua_runtime **) lua_getextraspace(runtime->state) = runtime;
    kelma_open_safe_libraries(runtime);
    kelma_create_host_table(runtime);
    return runtime;
}

int kelma_lua_add_file(
    kelma_lua_runtime *runtime,
    const char *path,
    const char *source,
    size_t source_size
) {
    if (runtime == NULL || path == NULL || source == NULL || runtime->started ||
        runtime->file_count >= KELMA_MAX_FILES) {
        return 0;
    }
    char *path_copy = kelma_duplicate(path);
    char *source_copy = kelma_duplicate_sized(source, source_size);
    if (path_copy == NULL || source_copy == NULL) {
        free(path_copy);
        free(source_copy);
        return 0;
    }
    struct kelma_file *resized = (struct kelma_file *) realloc(
        runtime->files,
        sizeof(struct kelma_file) * (size_t) (runtime->file_count + 1)
    );
    if (resized == NULL) {
        free(path_copy);
        free(source_copy);
        return 0;
    }
    runtime->files = resized;
    struct kelma_file *file = &runtime->files[runtime->file_count];
    file->path = path_copy;
    file->source = source_copy;
    file->source_size = source_size;
    runtime->file_count++;
    return 1;
}

int kelma_lua_start(
    kelma_lua_runtime *runtime,
    const char *bootstrap,
    size_t bootstrap_size,
    const char *entrypoint
) {
    if (runtime == NULL || bootstrap == NULL || entrypoint == NULL || runtime->started) return 0;
    if (luaL_loadbufferx(runtime->state, bootstrap, bootstrap_size, "@kelma/bootstrap.lua", "t") != LUA_OK ||
        !kelma_pcall(runtime, 0, 0)) {
        if (runtime->last_error[0] == '\0') kelma_capture_lua_error(runtime);
        return 0;
    }
    const struct kelma_file *file = kelma_find_file(runtime, entrypoint);
    if (file == NULL) {
        kelma_set_error(runtime, "Plugin entrypoint is missing");
        return 0;
    }
    char chunk_name[1200];
    snprintf(chunk_name, sizeof(chunk_name), "@%s:%s", runtime->plugin_id, entrypoint);
    if (luaL_loadbufferx(runtime->state, file->source, file->source_size, chunk_name, "t") != LUA_OK) {
        kelma_capture_lua_error(runtime);
        return 0;
    }
    if (!kelma_pcall(runtime, 0, 0)) return 0;
    runtime->started = 1;
    return 1;
}

int kelma_lua_log_count(const kelma_lua_runtime *runtime) { return runtime == NULL ? 0 : runtime->log_count; }
const char *kelma_lua_log_level(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->log_count ? NULL : runtime->logs[index].level;
}
const char *kelma_lua_log_message(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->log_count ? NULL : runtime->logs[index].message;
}
void kelma_lua_clear_logs(kelma_lua_runtime *runtime) {
    if (runtime == NULL) return;
    for (int index = 0; index < runtime->log_count; index++) {
        free(runtime->logs[index].level);
        free(runtime->logs[index].message);
    }
    free(runtime->logs);
    runtime->logs = NULL;
    runtime->log_count = 0;
}
const char *kelma_lua_last_error(const kelma_lua_runtime *runtime) {
    return runtime == NULL || runtime->last_error[0] == '\0' ? "Lua runtime failed" : runtime->last_error;
}
void kelma_lua_free_string(char *value) { free(value); }

void kelma_lua_close(kelma_lua_runtime *runtime) {
    if (runtime == NULL) return;
    if (runtime->state != NULL) lua_close(runtime->state);
    for (int index = 0; index < runtime->file_count; index++) {
        free(runtime->files[index].path);
        free(runtime->files[index].source);
    }
    for (int index = 0; index < runtime->command_count; index++) {
        free(runtime->commands[index].id);
        free(runtime->commands[index].title);
    }
    for (int index = 0; index < runtime->event_count; index++) free(runtime->events[index].name);
    for (int index = 0; index < runtime->renderer_count; index++) {
        free(runtime->renderers[index].id);
        free(runtime->renderers[index].title);
    }
    kelma_lua_clear_logs(runtime);
    free(runtime->plugin_id);
    free(runtime->files);
    free(runtime->commands);
    free(runtime->events);
    free(runtime->renderers);
    free(runtime);
}
