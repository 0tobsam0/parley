using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Parley;

// Where a session runs (docs/RUNTIME.md). Claude Code on Windows mostly runs inside WSL: its
// folders are Linux paths (/home/sir/proj), its transcripts, ~/.claude.json and processes live in
// the distro. The hooks and the mod name the distro in an optional `runtime` field; with it the
// app reaches the distro's files through \\wsl.localhost\<distro>\… and starts / ends sessions
// through wsl.exe. ssh (a remote that names itself with PARLEY_SSH_HOST) is only remembered for
// now — the app can't reach its files yet.
public sealed class SessionRuntime
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = "";
    [JsonPropertyName("distro")] public string? Distro { get; set; }
    [JsonPropertyName("host")] public string? Host { get; set; }
    [JsonPropertyName("home")] public string? Home { get; set; }

    [JsonIgnore] public bool IsWsl => Kind == "wsl" && Runtimes.ValidDistro(Distro) && Runtimes.IsLinuxPath(Home);
}

public static class Runtimes
{
    private static readonly object Gate = new();
    // Folder (Linux path) → the runtime its sessions reported; distro → its home.
    private static readonly Dictionary<string, SessionRuntime> ByCwd = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, string> WslHomes = new(StringComparer.OrdinalIgnoreCase);
    private static SessionRuntime? _last;
    private static bool _loaded;

    // A Linux path as WSL / SSH sessions send it — not Git Bash's /c/… (CwdPath turns that into C:\…).
    public static bool IsLinuxPath(string? p) =>
        p is { Length: > 0 } && p[0] == '/' && !(p.Length >= 2 && char.IsAsciiLetter(p[1]) && (p.Length == 2 || p[2] == '/'));

    // Distro names go into a UNC path and wsl.exe's argv: letters, digits, . _ - only.
    public static bool ValidDistro(string? d) =>
        d is { Length: > 0 and <= 64 } && d.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '_' or '-');

    // Any POST body: remember where its sender runs (runtime + cwd), cheap and silent.
    public static void SeenIn(string body)
    {
        if (!body.Contains("\"runtime\"", StringComparison.Ordinal)) return;
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (!doc.RootElement.TryGetProperty("runtime", out var rt) || rt.ValueKind != JsonValueKind.Object) return;
            var cwd = doc.RootElement.TryGetProperty("cwd", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() : null;
            Seen(cwd, rt.Deserialize<SessionRuntime>());
        }
        catch (JsonException) { }
    }

    public static void Seen(string? cwd, SessionRuntime? rt)
    {
        if (rt is null || (!rt.IsWsl && rt.Kind != "ssh")) return;
        bool newLast;
        lock (Gate)
        {
            Load();
            if (IsLinuxPath(cwd)) ByCwd[CwdPath.Normalize(cwd)] = rt;
            if (!rt.IsWsl) return;
            WslHomes[rt.Distro!] = rt.Home!;
            newLast = _last?.Distro != rt.Distro || _last?.Home != rt.Home;
            _last = rt;
        }
        if (!newLast) return;
        // The last WSL runtime, kept: a dispatch to a folder no session reported yet (the Zentrale,
        // "Hey Parley, start …") starts there.
        Config.Merge(new Dictionary<string, string> { ["wslDistro"] = rt.Distro!, ["wslHome"] = rt.Home! });
        Log.Write($"runtime: WSL {rt.Distro} ({rt.Home})");
    }

    private static void Load()
    {
        if (_loaded) return;
        _loaded = true;
        var c = Config.Load();
        var rt = new SessionRuntime { Kind = "wsl", Distro = c.WslDistro, Home = c.WslHome };
        if (!rt.IsWsl) return;
        _last = rt;
        WslHomes[rt.Distro!] = rt.Home!;
    }

    // The runtime of a folder: the one its sessions reported, else (a Linux path) the last WSL one.
    // null = this PC (a Windows path), or a Linux path with no WSL known.
    public static SessionRuntime? For(string? cwd, SessionRuntime? given = null)
    {
        if (given is { IsWsl: true } || given?.Kind == "ssh") return given;
        if (!IsLinuxPath(cwd)) return null;
        lock (Gate)
        {
            Load();
            return ByCwd.TryGetValue(CwdPath.Normalize(cwd), out var rt) ? rt : _last;
        }
    }

    // A WSL session's Linux path as Windows reaches it: /mnt/c/… is C:\…, the rest goes through
    // \\wsl.localhost\<distro>\…. A Windows path stays as it is; null = not reachable from here.
    public static string? WindowsPath(string? path, SessionRuntime? rt = null)
    {
        if (string.IsNullOrEmpty(path)) return null;
        if (!IsLinuxPath(path)) return path;
        var drive = System.Text.RegularExpressions.Regex.Match(path, @"^/mnt/([A-Za-z])(/.*)?$");
        if (drive.Success)
            return drive.Groups[1].Value.ToUpperInvariant() + ":" + (drive.Groups[2].Success ? drive.Groups[2].Value.Replace('/', '\\') : "\\");
        rt ??= For(path);
        return rt is { IsWsl: true } ? $@"\\wsl.localhost\{rt.Distro}" + path.Replace('/', '\\') : null;
    }

    // Claude Code installed on Windows itself (PATH, or the native installer's ~/.local/bin)? A
    // Windows PC whose Claude lives only in WSL has none: the Zentrale then runs in WSL. Asked once.
    public static bool ClaudeOnWindows => _claudeOnWindows.Value;
    private static readonly Lazy<bool> _claudeOnWindows = new(() =>
    {
        var dirs = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries).ToList();
        dirs.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "bin"));
        foreach (var d in dirs)
            foreach (var exe in new[] { "claude.exe", "claude.cmd", "claude.ps1" })
                try { if (File.Exists(Path.Combine(d.Trim('"'), exe))) return true; } catch (ArgumentException) { }
        return false;
    });

    public static bool DirExists(string? path, SessionRuntime? rt = null)
    {
        try { return WindowsPath(path, rt) is { } p && Directory.Exists(p); }
        catch (Exception) { return false; }
    }

    // Every WSL distro the app has heard from, with its home.
    public static List<(string Distro, string Home)> Wsl()
    {
        lock (Gate)
        {
            Load();
            return WslHomes.Select(kv => (kv.Key, kv.Value)).ToList();
        }
    }

    // A file under a distro's home (~/.claude/projects, ~/.claude.json, …) as Windows reaches it.
    public static string HomeFile(string distro, string home, string relative) =>
        $@"\\wsl.localhost\{distro}" + (home.TrimEnd('/') + "/" + relative).Replace('/', '\\');

    // Where Claude Code keeps transcripts: this PC's ~/.claude/projects, then each known distro's.
    public static IEnumerable<string> TranscriptRoots()
    {
        yield return Transcript.Projects;
        foreach (var (d, h) in Wsl()) yield return HomeFile(d, h, ".claude/projects");
    }

    // Starts Claude Code in a WSL folder in a new Windows Terminal tab (else a console window):
    // wsl.exe -d <distro> --cd <dir> --exec bash -lc 'exec "$@"' bash claude …. A login shell, so
    // ~/.local/bin (the native installer's claude) is on PATH; the arguments go through "$@",
    // never through a command string.
    public static bool StartWsl(SessionRuntime rt, string dir, string[] claudeArgs)
    {
        var wsl = new[] { "wsl.exe", "-d", rt.Distro!, "--cd", dir, "--exec", "bash", "-lc", "exec \"$@\"", "bash" }.Concat(claudeArgs).ToArray();
        try
        {
            var wt = new ProcessStartInfo("wt.exe") { UseShellExecute = false };
            foreach (var a in wsl) wt.ArgumentList.Add(a);
            Process.Start(wt)?.Dispose();
            Log.Write($"session: starting one in {rt.Distro}:{dir} (Windows Terminal, WSL)");
            return true;
        }
        catch (Exception e) { Log.Write($"session: Windows Terminal unavailable ({e.Message}) → cmd (WSL)"); }
        try
        {
            var cmd = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true };
            foreach (var a in new[] { "/c", "start", "" }.Concat(wsl)) cmd.ArgumentList.Add(a);
            Process.Start(cmd)?.Dispose();
            Log.Write($"session: starting one in {rt.Distro}:{dir} (cmd, WSL)");
            return true;
        }
        catch (Exception e)
        {
            Log.Write($"session: could not start one in {rt.Distro}:{dir} ({e.Message})");
            return false;
        }
    }

    // Ends a WSL session: its pid from the distro's ~/.claude/sessions/<pid>.json, killed inside
    // the distro (the tab's bash exec'd claude, so the tab closes with it). null = closing.
    public static string? CloseWsl(string id)
    {
        foreach (var (distro, home) in Wsl())
        {
            var dir = HomeFile(distro, home, ".claude/sessions");
            int? pid = null;
            try
            {
                if (!Directory.Exists(dir)) continue;
                foreach (var f in Directory.EnumerateFiles(dir, "*.json"))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(f));
                    if (doc.RootElement.TryGetProperty("sessionId", out var s) && s.GetString() == id
                        && doc.RootElement.TryGetProperty("pid", out var p) && p.TryGetInt32(out var n) && n > 1)
                    { pid = n; break; }
                }
            }
            catch (Exception) { continue; }
            if (pid is null) continue;
            try
            {
                var psi = new ProcessStartInfo("wsl.exe") { UseShellExecute = false, CreateNoWindow = true };
                foreach (var a in new[] { "-d", distro, "--", "kill", pid.Value.ToString() }) psi.ArgumentList.Add(a);
                using var k = Process.Start(psi)!;
                k.WaitForExit(10_000);
                if (k.ExitCode != 0) return $"could not end it (kill {pid} in {distro} failed)";
                Log.Write($"close: ended claude {pid} in {distro}");
                return null;
            }
            catch (Exception e) { return "could not end it: " + e.Message; }
        }
        return "no running claude process found for that session";
    }
}
