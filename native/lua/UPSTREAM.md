# Lua 5.4 upstream

- Version: 5.4.8
- Release date: 2025-05-21
- Source: https://www.lua.org/ftp/lua-5.4.8.tar.gz
- SHA-256: `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`
- License: MIT

The vendored inventory contains the upstream headers and library sources. The
standalone `lua`/`luac` programs and upstream Makefile are omitted. Kelma does
not link `liolib.c`, `loslib.c`, `ldblib.c`, or `linit.c`; the host opens only
its explicitly allowlisted libraries.
