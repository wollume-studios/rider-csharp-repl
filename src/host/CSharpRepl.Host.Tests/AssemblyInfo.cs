// Engine runs redirect the process-wide Console, so test classes must not run in parallel.
[assembly: CollectionBehavior(DisableTestParallelization = true)]

// Reference snapshots and NuGet temp projects go to a throwaway dir, not the user profile.
internal static class TestDataRoot
{
    [System.Runtime.CompilerServices.ModuleInitializer]
    internal static void Init() =>
        Environment.SetEnvironmentVariable("CSHARPREPL_DATA", Path.Combine(Path.GetTempPath(), "CSharpReplTests"));
}
