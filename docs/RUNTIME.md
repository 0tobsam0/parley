# Runtime — where a session runs

On macOS, Parley and Claude Code share one filesystem and one process table. On Windows,
Claude Code usually runs inside **WSL**, while `Parley.exe` runs on Windows. The two
only talk over HTTP (`127.0.0.1:8787`). A session's folder is then a Linux path
(`/home/sir/proj`), and its transcripts, `~/.claude.json` and processes live in the distro.

## The field

Every payload that carries a `cwd` may also carry `runtime`:

| Sender | Endpoints |
|---|---|
| `plugin/scripts/stop-hook.sh` | `/turn`, `/wake` |
| `plugin/scripts/permission-hook.sh` | `/permission` |
| `plugin/scripts/greet.sh` | `/ready` |
| `plugin-ui` mod | `/presence`, `/turn` |
| the Zentrale (optional) | `/dispatch` |

```json
{ "kind": "wsl", "distro": "Ubuntu-24.04", "home": "/home/sir" }
{ "kind": "ssh", "host": "root-box", "home": "/root" }
```

- `wsl` is sent when `WSL_DISTRO_NAME` is set.
- `ssh` is sent when `SSH_CONNECTION` and `PARLEY_SSH_HOST` are both set. The remote names
  itself with `PARLEY_SSH_HOST`, typically set in its shell profile.
- Otherwise, on macOS and native Windows, the field is absent and nothing changes.
- The macOS app ignores the field.

## Windows (`windows/Runtime.cs`)

- **Memory.** The app remembers the runtime per folder, plus the last WSL runtime. The last
  one is persisted in the config as `wslDistro` / `wslHome`. A dispatch to a folder no session
  has reported yet (the Zentrale, "Hey Parley, start …") starts there.
- **Paths.** Linux paths stay Linux paths (`CwdPath`) and compare case-sensitively. To reach
  one, the app maps it:
  - `/mnt/c/…` becomes `C:\…`
  - everything else becomes `\\wsl.localhost\<distro>\…`
- **Transcripts.** The app searches `%USERPROFILE%\.claude\projects` first, then
  `<home>/.claude/projects` of each known distro. This covers resume, `/past`, the watchdog,
  the phone's history and `<slide image="last">`.
- **Start / resume.** The command is:

  ```
  wt wsl.exe -d <distro> --cd <dir> -- bash -lc 'exec "$@"' bash claude [--resume id] /parley:voice
  ```

  If Windows Terminal is missing, the same command runs under `cmd /c start`.
- **Close.** The pid comes from the distro's `~/.claude/sessions/<pid>.json`. The app then
  runs `wsl.exe -d <distro> -- kill <pid>`.
- **Trust.** The app writes the distro's own `~/.claude.json`, keyed by the Linux path.

## Not yet

- **ssh** is only remembered. The app can't reach a remote's files or start sessions there.
  Turns from a remote reach the app through an SSH reverse tunnel
  (`ssh -R 8787:127.0.0.1:8787 …`).
- **The Zentrale** itself still runs as a Windows session.
- **WSL in NAT mode** isn't reachable, because the server binds loopback only. Use mirrored
  networking.
