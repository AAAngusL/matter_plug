# app/src/main/jniLibs/arm64-v8a

Native libraries from the connectedhomeip build `out/android-arm64-chip-tool/lib/jni/arm64-v8a/`:

- libCHIPController.so
- libc++_shared.so

Copied by `tools/copy-matter-libs.sh`. Only arm64-v8a is supported (the app sets `abiFilters += "arm64-v8a"`),
so the app cannot run on an x86 emulator. Commit these files; they are the pinned library.
