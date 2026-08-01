#ifndef KELMA_LUA_HOST_H
#define KELMA_LUA_HOST_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kelma_lua_runtime kelma_lua_runtime;

enum {
    KELMA_LUA_CAP_COMMANDS = 1u << 0,
    KELMA_LUA_CAP_EVENTS = 1u << 1,
    KELMA_LUA_CAP_UI = 1u << 2,
};

kelma_lua_runtime *kelma_lua_new(
    const char *plugin_id,
    unsigned int capabilities,
    size_t memory_limit,
    long long instruction_limit
);
int kelma_lua_add_file(
    kelma_lua_runtime *runtime,
    const char *path,
    const char *source,
    size_t source_size
);
int kelma_lua_start(
    kelma_lua_runtime *runtime,
    const char *bootstrap,
    size_t bootstrap_size,
    const char *entrypoint
);

int kelma_lua_command_count(const kelma_lua_runtime *runtime);
const char *kelma_lua_command_id(const kelma_lua_runtime *runtime, int index);
const char *kelma_lua_command_title(const kelma_lua_runtime *runtime, int index);
int kelma_lua_invoke_command(
    kelma_lua_runtime *runtime,
    const char *command_id,
    const char *arguments_json,
    char **result_json
);

int kelma_lua_event_count(const kelma_lua_runtime *runtime);
const char *kelma_lua_event_name(const kelma_lua_runtime *runtime, int index);
int kelma_lua_publish_event(
    kelma_lua_runtime *runtime,
    const char *event_name,
    const char *attributes_json
);

int kelma_lua_renderer_count(const kelma_lua_runtime *runtime);
const char *kelma_lua_renderer_id(const kelma_lua_runtime *runtime, int index);
int kelma_lua_render(
    kelma_lua_runtime *runtime,
    const char *renderer_id,
    const char *html,
    const char *css,
    char **result_html,
    char **result_css
);

int kelma_lua_log_count(const kelma_lua_runtime *runtime);
const char *kelma_lua_log_level(const kelma_lua_runtime *runtime, int index);
const char *kelma_lua_log_message(const kelma_lua_runtime *runtime, int index);
void kelma_lua_clear_logs(kelma_lua_runtime *runtime);
const char *kelma_lua_last_error(const kelma_lua_runtime *runtime);
void kelma_lua_free_string(char *value);
void kelma_lua_close(kelma_lua_runtime *runtime);

#ifdef __cplusplus
}
#endif

#endif
