namespace CSharpRepl.Host;

/// <summary>File-based-app directives that recreate the session's references.</summary>
public static class Directives
{
    /// <summary>One line per reference, joined by "\n" (no trailing newline). Project paths are relative to the directory of <paramref name="filePath"/>.</summary>
    public static string For(IEnumerable<ReferenceInfo> references, string filePath)
    {
        var dir = Path.GetDirectoryName(Path.GetFullPath(filePath))!;
        return string.Join("\n", references.Select(r => r.Kind switch
        {
            "project" => "#:project " + Path.GetRelativePath(dir, r.PathOrName).Replace('\\', '/'),
            "nuget" => $"#:package {r.PathOrName}@{r.Version}",
            _ => "// reference DLL manually: " + r.PathOrName,
        }));
    }
}
