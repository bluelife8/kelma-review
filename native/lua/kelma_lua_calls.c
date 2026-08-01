#include "kelma_lua_internal.h"

#include <stdlib.h>
#include <string.h>

int kelma_lua_command_count(const kelma_lua_runtime *runtime) {
    return runtime == NULL ? 0 : runtime->command_count;
}

const char *kelma_lua_command_id(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->command_count ? NULL : runtime->commands[index].id;
}

const char *kelma_lua_command_title(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->command_count ? NULL : runtime->commands[index].title;
}

int kelma_lua_invoke_command(
    kelma_lua_runtime *runtime,
    const char *command_id,
    const char *arguments_json,
    char **result_json
) {
    if (runtime == NULL || command_id == NULL || arguments_json == NULL || result_json == NULL || !runtime->started) {
        return 0;
    }
    *result_json = NULL;
    for (int index = 0; index < runtime->command_count; index++) {
        if (strcmp(runtime->commands[index].id, command_id) != 0) continue;
        lua_rawgeti(runtime->state, LUA_REGISTRYINDEX, runtime->commands[index].reference);
        if (!kelma_push_decoded(runtime, arguments_json)) return 0;
        if (!kelma_pcall(runtime, 1, 1)) return 0;
        return kelma_encode_top(runtime, result_json);
    }
    kelma_set_error(runtime, "Plugin command is not registered");
    return 0;
}

int kelma_lua_event_count(const kelma_lua_runtime *runtime) {
    return runtime == NULL ? 0 : runtime->event_count;
}

const char *kelma_lua_event_name(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->event_count ? NULL : runtime->events[index].name;
}

int kelma_lua_publish_event(
    kelma_lua_runtime *runtime,
    const char *event_name,
    const char *attributes_json
) {
    if (runtime == NULL || event_name == NULL || attributes_json == NULL || !runtime->started) return 0;
    for (int index = 0; index < runtime->event_count; index++) {
        if (strcmp(runtime->events[index].name, event_name) != 0) continue;
        lua_rawgeti(runtime->state, LUA_REGISTRYINDEX, runtime->events[index].reference);
        lua_newtable(runtime->state);
        lua_pushstring(runtime->state, event_name);
        lua_setfield(runtime->state, -2, "name");
        if (!kelma_push_decoded(runtime, attributes_json)) return 0;
        lua_setfield(runtime->state, -2, "attributes");
        if (!kelma_pcall(runtime, 1, 0)) return 0;
    }
    lua_settop(runtime->state, 0);
    return 1;
}

int kelma_lua_renderer_count(const kelma_lua_runtime *runtime) {
    return runtime == NULL ? 0 : runtime->renderer_count;
}

const char *kelma_lua_renderer_id(const kelma_lua_runtime *runtime, int index) {
    return runtime == NULL || index < 0 || index >= runtime->renderer_count ? NULL : runtime->renderers[index].id;
}

int kelma_lua_render(
    kelma_lua_runtime *runtime,
    const char *renderer_id,
    const char *html,
    const char *css,
    char **result_html,
    char **result_css
) {
    if (runtime == NULL || renderer_id == NULL || html == NULL || css == NULL || result_html == NULL ||
        result_css == NULL || !runtime->started) return 0;
    *result_html = NULL;
    *result_css = NULL;
    for (int index = 0; index < runtime->renderer_count; index++) {
        if (strcmp(runtime->renderers[index].id, renderer_id) != 0) continue;
        lua_rawgeti(runtime->state, LUA_REGISTRYINDEX, runtime->renderers[index].reference);
        lua_newtable(runtime->state);
        lua_pushstring(runtime->state, renderer_id);
        lua_setfield(runtime->state, -2, "rendererId");
        lua_pushstring(runtime->state, html);
        lua_setfield(runtime->state, -2, "html");
        lua_pushstring(runtime->state, css);
        lua_setfield(runtime->state, -2, "css");
        if (!kelma_pcall(runtime, 1, 1)) return 0;
        if (!lua_istable(runtime->state, -1)) {
            kelma_set_error(runtime, "Plugin renderer must return a table");
            lua_settop(runtime->state, 0);
            return 0;
        }
        lua_getfield(runtime->state, -1, "html");
        lua_getfield(runtime->state, -2, "css");
        const char *rendered_html = lua_tostring(runtime->state, -2);
        const char *rendered_css = lua_tostring(runtime->state, -1);
        if (rendered_html == NULL || rendered_css == NULL) {
            kelma_set_error(runtime, "Plugin renderer result must contain html and css strings");
            lua_settop(runtime->state, 0);
            return 0;
        }
        *result_html = kelma_duplicate(rendered_html);
        *result_css = kelma_duplicate(rendered_css);
        lua_settop(runtime->state, 0);
        if (*result_html != NULL && *result_css != NULL) return 1;
        free(*result_html);
        free(*result_css);
        *result_html = NULL;
        *result_css = NULL;
        kelma_set_error(runtime, "Could not allocate plugin renderer result");
        return 0;
    }
    kelma_set_error(runtime, "Plugin renderer is not registered");
    return 0;
}
