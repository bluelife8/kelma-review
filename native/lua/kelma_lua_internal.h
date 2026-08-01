#ifndef KELMA_LUA_INTERNAL_H
#define KELMA_LUA_INTERNAL_H

#include "kelma_lua_host.h"
#include "src/lauxlib.h"

struct kelma_file {
    char *path;
    char *source;
    size_t source_size;
};

struct kelma_registration {
    char *id;
    char *title;
    int reference;
};

struct kelma_event {
    char *name;
    int reference;
};

struct kelma_log {
    char *level;
    char *message;
};

struct kelma_lua_runtime {
    lua_State *state;
    char *plugin_id;
    unsigned int capabilities;
    size_t memory_used;
    size_t memory_limit;
    long long instruction_limit;
    long long instructions_remaining;
    struct kelma_file *files;
    int file_count;
    struct kelma_registration *commands;
    int command_count;
    struct kelma_event *events;
    int event_count;
    struct kelma_registration *renderers;
    int renderer_count;
    struct kelma_log *logs;
    int log_count;
    int started;
    char last_error[2048];
};

char *kelma_duplicate(const char *value);
void kelma_set_error(kelma_lua_runtime *runtime, const char *message);
int kelma_pcall(kelma_lua_runtime *runtime, int arguments, int results);
int kelma_push_decoded(kelma_lua_runtime *runtime, const char *json);
int kelma_encode_top(kelma_lua_runtime *runtime, char **result_json);

#endif
