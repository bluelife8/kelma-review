# Third-party notices

Kelma Review is MIT-licensed. Its build uses reviewed third-party dependencies,
including the following principal components. Distribution builds must retain
the complete license texts supplied by their dependency artifacts.

- **kelma-fsrs-v6** — MIT License.
- **Lua 5.4.8** — MIT License; vendored from Lua.org for the portable plugin runtime. The complete license is retained at `native/lua/LICENSE`.
- **Kotlin and kotlinx libraries** — Apache License 2.0.
- **Compose Multiplatform and AndroidX** — Apache License 2.0.
- **Ktor** — Apache License 2.0.
- **SQLDelight** — Apache License 2.0.
- **SQLite** — public domain.
- **OkHttp, Okio, Guava, Touchlab SQLiter/Stately, and kotlinx-io transitive runtime components** — Apache License 2.0.
- **Square Zstd-KMP** — Apache License 2.0; its bundled Zstandard native library is BSD-3-Clause.
- **SLF4J** — MIT License.
- **OpenJFX / JavaFX WebKit desktop runtime** — GNU General Public License 2.0 with the Classpath Exception.
- **JUnit 4** (test only) — Eclipse Public License 1.0.

Development-only FSRS oracle packages and their provenance are documented by
`kelma-fsrs-v6`; Python, PyTorch, pandas, NumPy, and SciPy are not application
runtime dependencies.
