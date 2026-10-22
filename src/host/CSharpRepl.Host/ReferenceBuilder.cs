using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using System.Security;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CSharpRepl.Host;

public sealed class ReferenceException(string message) : Exception(message);

public sealed record BuiltReference(string[] Assemblies, string[] SourceDirs, string SnapshotDir);

/// <summary>
/// Builds projects and NuGet packages into private snapshot directories so the user's own bin/ and obj/ stay unlocked
/// (loaded assemblies are locked, so the host must only ever load from snapshots).
/// </summary>
public static partial class ReferenceBuilder
{
    /// <summary>`CSHARPREPL_DATA` overrides the default `%LOCALAPPDATA%\CSharpRepl` (tests use it).</summary>
    public static string DataRoot =>
        Environment.GetEnvironmentVariable("CSHARPREPL_DATA") is { Length: > 0 } d
            ? d
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CSharpRepl");

    private static string RefsRoot => Path.Combine(DataRoot, "refs");

    private const string InUse = ".inuse";

    // Every snapshot this host creates holds an exclusive lock file for the process lifetime, so another host's cleanup leaves it alone.
    private static readonly List<FileStream> Locks = new();

    private static string NewSnapshot(string name)
    {
        var snapshot = Path.Combine(RefsRoot, $"{name}-{DateTime.Now:yyyyMMddHHmmssfff}");
        Directory.CreateDirectory(snapshot);
        var marker = new FileStream(Path.Combine(snapshot, InUse), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        lock (Locks) Locks.Add(marker);
        return snapshot;
    }

    /// <summary>
    /// Deletes every snapshot no live host holds (`.inuse` lock), so restarts don't pile them up: the frontend kills the
    /// old host before starting the next one. Snapshots younger than a minute are skipped (another host may be creating one).
    /// </summary>
    public static void CleanOldSnapshots()
    {
        if (!Directory.Exists(RefsRoot)) return;
        foreach (var dir in Directory.EnumerateDirectories(RefsRoot))
        {
            try
            {
                if (Directory.GetLastWriteTimeUtc(dir) >= DateTime.UtcNow.AddMinutes(-1)) continue;
                using (new FileStream(Path.Combine(dir, InUse), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) { } // throws while held
                Directory.Delete(dir, true);
            }
            catch (IOException) { } // held by a live host, or a file inside is in use: leave it
            catch (UnauthorizedAccessException) { }
        }
    }

    public static async Task<BuiltReference> BuildProject(string csprojPath)
    {
        csprojPath = Path.GetFullPath(csprojPath);
        if (!File.Exists(csprojPath)) throw new ReferenceException($"Project not found: {csprojPath}");

        var tfm = await PickTargetFramework(csprojPath);
        var (snapshot, items) = await Build(csprojPath, Path.GetFileNameWithoutExtension(csprojPath), tfm);

        var sourceDirs = new List<string> { Path.GetDirectoryName(csprojPath)! };
        await Closure(ProjectReferences(items), new HashSet<string>(StringComparer.OrdinalIgnoreCase) { csprojPath }, sourceDirs);
        return new BuiltReference(ManagedDlls(snapshot), sourceDirs.ToArray(), snapshot);
    }

    public static async Task<BuiltReference> BuildNuGet(string name, string version)
    {
        if (!PackageName().IsMatch(name)) throw new ReferenceException($"Invalid NuGet package name: {name}");
        if (version.Length == 0 || version.Any(char.IsWhiteSpace)) throw new ReferenceException($"Invalid NuGet package version: {version}");

        var safe = string.Concat($"{name}-{version}".Select(c => Path.GetInvalidFileNameChars().Contains(c) ? '_' : c));
        var dir = Path.Combine(DataRoot, "nuget", safe);
        Directory.CreateDirectory(dir);
        var proj = Path.Combine(dir, "Ref.csproj");
        File.WriteAllText(proj, $"""
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFramework>net{Environment.Version.Major}.0</TargetFramework></PropertyGroup>
              <ItemGroup><PackageReference Include="{SecurityElement.Escape(name)}" Version="{SecurityElement.Escape(version)}" /></ItemGroup>
            </Project>
            """);

        var (snapshot, _) = await Build(proj, name, null);
        return new BuiltReference(
            ManagedDlls(snapshot).Where(a => !Path.GetFileName(a).Equals("Ref.dll", StringComparison.OrdinalIgnoreCase)).ToArray(),
            [], snapshot);
    }

    /// <summary>A prebuilt DLL is copied with its sibling DLLs/PDBs into a snapshot, so the original (usually a bin folder) is never locked.</summary>
    public static BuiltReference Dll(string path)
    {
        path = Path.GetFullPath(path);
        if (!File.Exists(path)) throw new ReferenceException($"File not found: {path}");
        if (!IsManaged(path)) throw new ReferenceException($"Not a managed assembly: {path}");

        var snapshot = NewSnapshot(Path.GetFileNameWithoutExtension(path));
        try
        {
            var dir = Path.GetDirectoryName(path)!;
            // ponytail: top-level files only (no satellite/runtimes subfolders); a huge folder is copied whole
            foreach (var f in Directory.EnumerateFiles(dir, "*.dll").Concat(Directory.EnumerateFiles(dir, "*.pdb")))
                File.Copy(f, Path.Combine(snapshot, Path.GetFileName(f)), true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            TryDelete(snapshot);
            throw new ReferenceException($"Could not copy {path} to a snapshot: {e.Message}");
        }
        return new BuiltReference([Path.Combine(snapshot, Path.GetFileName(path))], [], snapshot);
    }

    public static bool IsManaged(string file)
    {
        try { AssemblyName.GetAssemblyName(file); return true; }
        catch (BadImageFormatException) { return false; }
    }

    // ponytail: resource satellites live in subdirectories and are skipped; add if localized packages matter
    private static string[] ManagedDlls(string dir) =>
        Directory.EnumerateFiles(dir, "*.dll").Where(IsManaged).ToArray();

    private static async Task<string> PickTargetFramework(string csproj)
    {
        var what = $"Reading target framework of {Path.GetFileName(csproj)}";
        var p = ParseJson(await Dotnet(what, "msbuild", csproj, "-getProperty:TargetFramework", "-getProperty:TargetFrameworks"), what).GetProperty("Properties");
        var candidates = (p.GetProperty("TargetFramework").GetString() + ";" + p.GetProperty("TargetFrameworks").GetString())
            .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var best = candidates.Select(t => (Tfm: t, Rank: Rank(t))).Where(t => t.Rank != null).OrderByDescending(t => t.Rank).FirstOrDefault().Tfm;
        return best ?? throw new ReferenceException(
            $"Unsupported target framework '{string.Join(", ", candidates)}'. Supported: net8.0 up to net{Environment.Version.Major}.0 and netstandard2.x; " +
            ".NET Framework, Windows-specific (-windows) and newer-than-installed targets are rejected.");
    }

    private static int? Rank(string tfm)
    {
        if (tfm.StartsWith("netstandard2.", StringComparison.OrdinalIgnoreCase)) return 0;
        var m = Plain().Match(tfm); // no platform suffix, so -windows etc. never match; net48 has no dot
        if (!m.Success) return null;
        var major = int.Parse(m.Groups[1].Value);
        return major >= 8 && major <= Environment.Version.Major ? major * 100 + int.Parse(m.Groups[2].Value) : null;
    }

    private static async Task<(string Snapshot, JsonElement Items)> Build(string csproj, string name, string? tfm)
    {
        var snapshot = NewSnapshot(name);
        var args = new List<string>
        {
            "build", csproj, "-c", "Debug", "-t:Build", // -getProperty alone only evaluates; -t:Build makes it build as well
            $"-p:OutDir={snapshot}{Path.DirectorySeparatorChar}", "-p:CopyLocalLockFileAssemblies=true",
            "-nodeReuse:false", "-p:UseSharedCompilation=false", // no build servers left holding files
            "-getProperty:TargetPath", "-getItem:ProjectReference",
        };
        if (tfm != null) args.Add($"-p:TargetFramework={tfm}");
        string stdout;
        try { stdout = await Dotnet($"Build of {Path.GetFileName(csproj)}", args.ToArray()); }
        catch { TryDelete(snapshot); throw; }

        var json = ParseJson(stdout, $"Build of {Path.GetFileName(csproj)}");
        var target = json.GetProperty("Properties").GetProperty("TargetPath").GetString();
        if (target == null || !File.Exists(target)) throw new ReferenceException($"Build of {Path.GetFileName(csproj)} produced no output assembly ({target}).");
        return (snapshot, json.GetProperty("Items"));
    }

    private static IEnumerable<string> ProjectReferences(JsonElement items) =>
        items.GetProperty("ProjectReference").EnumerateArray().Select(i => i.GetProperty("FullPath").GetString()!);

    private static async Task Closure(IEnumerable<string> refs, HashSet<string> seen, List<string> dirs)
    {
        foreach (var r in refs.ToArray())
        {
            if (!seen.Add(r)) continue;
            dirs.Add(Path.GetDirectoryName(r)!);
            var what = $"Reading references of {Path.GetFileName(r)}";
            var doc = ParseJson(await Dotnet(what, "msbuild", r, "-getItem:ProjectReference"), what);
            await Closure(ProjectReferences(doc.GetProperty("Items")), seen, dirs);
        }
    }

    /// <summary>Runs dotnet and returns stdout; a failure (including a missing dotnet) becomes a ReferenceException with the last 20 output lines.</summary>
    private static async Task<string> Dotnet(string what, params string[] args)
    {
        var psi = new ProcessStartInfo(Environment.GetEnvironmentVariable("CSHARPREPL_DOTNET") is { Length: > 0 } d ? d : "dotnet")
        {
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            UseShellExecute = false, CreateNoWindow = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        psi.Environment["DOTNET_CLI_UI_LANGUAGE"] = "en";
        psi.Environment["DOTNET_NOLOGO"] = "1";

        Process process;
        try { process = Process.Start(psi)!; }
        catch (Exception e) when (e is Win32Exception or FileNotFoundException)
        {
            throw new ReferenceException($"{what} failed: cannot run '{psi.FileName}' ({e.Message}). Is the .NET SDK installed?");
        }

        using (process)
        {
            process.StandardInput.Close(); // never wait for input
            var (stdout, stderr) = (process.StandardOutput.ReadToEndAsync(), process.StandardError.ReadToEndAsync());
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(10));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException)
            {
                process.Kill(true);
                throw new ReferenceException($"{what} timed out after 10 minutes.");
            }
            var (so, se) = (await stdout, await stderr);
            if (process.ExitCode != 0) throw new ReferenceException($"{what} failed:\n{Tail(so, se)}");
            return so;
        }
    }

    /// <summary>Splits stdout into build log and the trailing `-getProperty/-getItem` JSON block (the last bare "{" line).</summary>
    private static (string Log, string Json) SplitJson(string stdout)
    {
        var s = "\n" + stdout.Replace("\r\n", "\n");
        var i = s.LastIndexOf("\n{\n", StringComparison.Ordinal);
        return i < 0 ? (s, "") : (s[..i], s[(i + 1)..]);
    }

    private static JsonElement ParseJson(string stdout, string what)
    {
        try { return JsonDocument.Parse(SplitJson(stdout).Json).RootElement; } // ponytail: documents are never disposed; GC reclaims them
        catch (JsonException) { throw new ReferenceException($"{what} returned no usable output:\n{Tail(stdout, "")}"); }
    }

    private static string Tail(string stdout, string stderr) =>
        string.Join("\n", (SplitJson(stdout).Log + "\n" + stderr.Replace("\r\n", "\n")).Split('\n').Where(l => l.Trim().Length > 0).TakeLast(20));

    private static void TryDelete(string dir)
    {
        lock (Locks)
            foreach (var l in Locks.Where(l => l.Name == Path.Combine(dir, InUse)).ToArray()) { l.Dispose(); Locks.Remove(l); }
        try { Directory.Delete(dir, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    [GeneratedRegex(@"^net(\d+)\.(\d+)$")]
    private static partial Regex Plain();

    [GeneratedRegex(@"^[A-Za-z0-9_.\-]+$")]
    private static partial Regex PackageName();
}
