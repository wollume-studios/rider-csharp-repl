using System.Diagnostics;

namespace CSharpRepl.Host.Tests;

public class ReferenceBuilderTests
{
    private static readonly string Fixtures = FindFixtures();

    private static string FindFixtures()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "CSharpRepl.Host.Tests.csproj"))) return Path.Combine(d.FullName, "Fixtures");
        throw new InvalidOperationException("Fixtures directory not found");
    }

    private static string Fixture(string name) => Path.Combine(Fixtures, name, name + ".csproj");

    private static async Task<ReplEngine> EngineWith(string kind, string? path = null, string? name = null, string? version = null)
    {
        var engine = new ReplEngine();
        await engine.AddReferenceAsync(kind, path, name, version);
        return engine;
    }

    [Fact]
    public async Task Project_LoadsAndRuns()
    {
        var engine = await EngineWith("project", Fixture("SampleLib"));
        var r = await engine.RunAsync("SampleLib.Greeter.Hi(\"x\")");
        Assert.Equal(SubmissionStatus.Ok, r.Status);
        Assert.Equal("\"Hi x\"", r.Result);
        Assert.Contains(new ReferenceInfo("project", Fixture("SampleLib"), null), engine.References);
    }

    [Fact]
    public async Task Project_TransitiveNuGetLoads()
    {
        var engine = await EngineWith("project", Fixture("SampleLib"));
        var r = await engine.RunAsync("Humanizer.StringHumanizeExtensions.Humanize(\"some_text\")");
        Assert.Equal(SubmissionStatus.Ok, r.Status);
        Assert.Equal("\"some text\"", r.Result);
    }

    [Fact]
    public async Task Project_DoesNotLockBin()
    {
        await EngineWith("project", Fixture("SampleLib"));
        var psi = new ProcessStartInfo("dotnet") { RedirectStandardOutput = true, RedirectStandardError = true };
        psi.ArgumentList.Add("build");
        psi.ArgumentList.Add(Fixture("SampleLib"));
        using var p = Process.Start(psi)!;
        var output = await p.StandardOutput.ReadToEndAsync();
        await p.WaitForExitAsync();
        Assert.True(p.ExitCode == 0, output);
    }

    [Fact]
    public async Task NuGet_LoadsPackage()
    {
        var built = await ReferenceBuilder.BuildNuGet("Humanizer.Core", "2.14.1");
        Assert.Contains(built.Assemblies, a => Path.GetFileName(a) == "Humanizer.dll");
        Assert.DoesNotContain(built.Assemblies, a => Path.GetFileName(a) == "Ref.dll");
        var engine = await EngineWith("nuget", name: "Humanizer.Core", version: "2.14.1");
        var r = await engine.RunAsync("Humanizer.StringHumanizeExtensions.Humanize(\"some_text\")");
        Assert.Equal("\"some text\"", r.Result);
    }

    [Fact]
    public async Task WindowsTfm_Rejected()
    {
        var e = await Assert.ThrowsAsync<ReferenceException>(() => ReferenceBuilder.BuildProject(Fixture("WinLib")));
        Assert.Contains("net8.0-windows", e.Message);
    }

    [Fact]
    public async Task OldTfm_Rejected()
    {
        var e = await Assert.ThrowsAsync<ReferenceException>(() => ReferenceBuilder.BuildProject(Fixture("Net6Lib")));
        Assert.Contains("net6.0", e.Message);
        Assert.Contains("net8.0", e.Message);
    }

    [Fact]
    public async Task BuildFailure_ReportsTail()
    {
        var e = await Assert.ThrowsAsync<ReferenceException>(() => ReferenceBuilder.BuildProject(Fixture("Broken")));
        Assert.Contains("error CS", e.Message);
        Assert.True(e.Message.Split('\n').Length - 1 <= 20, e.Message); // first line is the header, the rest the output tail
    }

    [Fact]
    public async Task Dll_Reference()
    {
        var built = await ReferenceBuilder.BuildProject(Fixture("SampleLib"));
        var copy = Path.Combine(Directory.CreateTempSubdirectory().FullName, "SampleLib.dll");
        File.Copy(built.Assemblies.Single(a => Path.GetFileName(a) == "SampleLib.dll"), copy);

        var engine = await EngineWith("dll", copy);
        var r = await engine.RunAsync("SampleLib.Greeter.Hi(\"d\")");
        Assert.Equal("\"Hi d\"", r.Result);
        Assert.Contains(new ReferenceInfo("dll", copy, null), engine.References);
    }

    [Fact]
    public async Task Dll_DoesNotLockOriginal()
    {
        var built = await ReferenceBuilder.BuildProject(Fixture("SampleLib"));
        var bin = Directory.CreateTempSubdirectory().FullName;
        foreach (var f in built.Assemblies.Where(a => Path.GetFileName(a) is "SampleLib.dll" or "Humanizer.dll"))
            File.Copy(f, Path.Combine(bin, Path.GetFileName(f)));

        var engine = await EngineWith("dll", Path.Combine(bin, "SampleLib.dll"));
        Assert.Equal("\"Hi q\"", (await engine.RunAsync("SampleLib.Greeter.Hi(\"q\")")).Result);
        Assert.Equal(Path.Combine(bin, "SampleLib.dll"), Assert.Single(engine.References).PathOrName);
        var copy = ReferenceBuilder.Dll(Path.Combine(bin, "SampleLib.dll")); // a second snapshot, only to inspect what gets copied
        Assert.NotEqual(bin, copy.SnapshotDir);
        Assert.True(File.Exists(Path.Combine(copy.SnapshotDir, "Humanizer.dll")));

        // both the referenced DLL and its loaded sibling must be deletable/overwritable while the session uses them
        File.WriteAllText(Path.Combine(bin, "SampleLib.dll"), "overwritten");
        File.Delete(Path.Combine(bin, "Humanizer.dll"));
        Directory.Delete(bin, true);
    }

    [Fact]
    public async Task SourceDirs_IncludeProjectReferences()
    {
        var built = await ReferenceBuilder.BuildProject(Fixture("AppWithRef"));
        Assert.Equal(
            new[] { Path.Combine(Fixtures, "AppWithRef"), Path.Combine(Fixtures, "SampleLib") }.Order(),
            built.SourceDirs.Select(d => Path.TrimEndingDirectorySeparator(d)).Order());
    }

    [Fact]
    public async Task HashRNuGet_AddsReferenceAndStripsLine()
    {
        var engine = new ReplEngine();
        var r = await engine.RunAsync("#r \"nuget: Humanizer.Core, 2.14.1\"\nHumanizer.StringHumanizeExtensions.Humanize(\"some_text\")");
        Assert.Equal(SubmissionStatus.Ok, r.Status);
        Assert.Equal("\"some text\"", r.Result);
        Assert.DoesNotContain("#r", engine.Submissions[^1].Code);
        Assert.Contains(new ReferenceInfo("nuget", "Humanizer.Core", "2.14.1"), engine.References);
        Assert.Equal([new ReferenceInfo("nuget", "Humanizer.Core", "2.14.1")], r.AddedReferences);
    }

    [Fact]
    public async Task HashRNuGet_AlreadyReferenced_IsNotBuiltAgain()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("#r \"nuget: Humanizer.Core, 2.14.1\"");
        var old = Environment.GetEnvironmentVariable("CSHARPREPL_DOTNET");
        Environment.SetEnvironmentVariable("CSHARPREPL_DOTNET", Path.Combine(Path.GetTempPath(), "no-such-dir", "dotnet.exe")); // a second build would fail
        try
        {
            var r = await engine.RunAsync("#r \"nuget: Humanizer.Core, 2.14.1\"\nHumanizer.StringHumanizeExtensions.Humanize(\"a_b\")");
            Assert.Equal(SubmissionStatus.Ok, r.Status);
            Assert.Equal("\"a b\"", r.Result);
            Assert.Empty(r.AddedReferences);
            Assert.Single(engine.References);
            Assert.Single(Directives.For(engine.References, Path.Combine(Path.GetTempPath(), "x.cs")).Split('\n'), l => l.StartsWith("#:package"));
        }
        finally { Environment.SetEnvironmentVariable("CSHARPREPL_DOTNET", old); }
    }

    [Fact]
    public async Task MissingDotnet_ThrowsReferenceException()
    {
        var old = Environment.GetEnvironmentVariable("CSHARPREPL_DOTNET");
        Environment.SetEnvironmentVariable("CSHARPREPL_DOTNET", Path.Combine(Path.GetTempPath(), "no-such-dir", "dotnet.exe"));
        try
        {
            await Assert.ThrowsAsync<ReferenceException>(() => ReferenceBuilder.BuildProject(Fixture("SampleLib")))
                .WaitAsync(TimeSpan.FromSeconds(30));
        }
        finally { Environment.SetEnvironmentVariable("CSHARPREPL_DOTNET", old); }
    }

    [Fact]
    public void CleanOldSnapshots_DeletesUnusedDirsOlderThanAMinute()
    {
        var refs = Path.Combine(ReferenceBuilder.DataRoot, "refs");
        var (old, fresh) = (Path.Combine(refs, "old-" + Guid.NewGuid()), Path.Combine(refs, "fresh-" + Guid.NewGuid()));
        Directory.CreateDirectory(old);
        Directory.CreateDirectory(fresh);
        Directory.SetLastWriteTimeUtc(old, DateTime.UtcNow.AddMinutes(-2)); // e.g. the previous host's, before a restart
        ReferenceBuilder.CleanOldSnapshots();
        Assert.False(Directory.Exists(old));
        Assert.True(Directory.Exists(fresh));
        Directory.Delete(fresh);
    }

    [Fact]
    public void CleanOldSnapshots_KeepsSnapshotHeldByAnotherHost()
    {
        var refs = Path.Combine(ReferenceBuilder.DataRoot, "refs");
        var (held, free) = (Path.Combine(refs, "held-" + Guid.NewGuid()), Path.Combine(refs, "free-" + Guid.NewGuid()));
        Directory.CreateDirectory(held);
        Directory.CreateDirectory(free);
        using (new FileStream(Path.Combine(held, ".inuse"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) // what a live host holds
        {
            Directory.SetLastWriteTimeUtc(held, DateTime.UtcNow.AddDays(-2));
            Directory.SetLastWriteTimeUtc(free, DateTime.UtcNow.AddDays(-2));
            ReferenceBuilder.CleanOldSnapshots();
            Assert.True(Directory.Exists(held));
            Assert.False(Directory.Exists(free));
        }
        Directory.Delete(held, true);
    }
}
