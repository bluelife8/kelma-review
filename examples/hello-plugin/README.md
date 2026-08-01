# Kelma Hello plugin

Build the example from this directory:

```bash
mkdir -p ../../build
zip -r ../../build/kelma-hello.kelmaplugin manifest.json plugin lua README.md LICENSE
```

Then open **Options → Plugins → Install .kelmaplugin**. Kelma previews the
requested capabilities before installation. The example registers one command,
two lifecycle observers, and one pure HTML/CSS renderer. Run **Greet from Lua**
from Cmd/Ctrl+K (or the mobile Commands action), and assign the border renderer
to a deck or note type from Plugin Manager.

The example source is released under the same MIT license as Kelma Review.
