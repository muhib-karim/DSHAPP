# Emulator smoke screenshots

From `.github/workflows/emulator-smoke.yml`, run 39612658430 (commit b85205b), Android 14 x86_64 emulator, release APK
`DSH0.3.4-mk.apk` (`com.mkdsh.droid`).

| File | Screen |
|---|---|
| `01-first-screen.png` | First screen after launch: the app's boot layer |
| `02-boot-status.png` | Boot status: on this x86_64 image the engine cannot start (the app ships an arm64 Termux bootstrap only), so the app offers safe mode and View log |
| `03-log-viewer.png` | The app's native log viewer (运行日志) with the cause (`CANNOT LINK EXECUTABLE … libz.so.1` under translation) |

The DSH chat and settings web UI need a real arm64 device; see [FORK_NOTES.md](../../../FORK_NOTES.md).
