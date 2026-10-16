using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace CSharpRepl.Host;

public sealed record Request(int Id, string Cmd, string? Code, int? Handle = null);

public sealed record AnalyzeResponse(bool Complete, string? Fixed);

public sealed record VariablesResponse(IReadOnlyList<VarInfo> Variables);

public sealed record ExpandResponse(IReadOnlyList<VarInfo> Children);

/// <summary>One JSON object per line; writes are serialized so concurrent handlers never interleave.</summary>
public sealed class ProtocolWriter(Stream output)
{
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
    };

    private readonly StreamWriter _writer = new(output, new System.Text.UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };

    public void Ok(int id, object payload)
    {
        var node = (JsonObject)JsonSerializer.SerializeToNode(payload, payload.GetType(), Json)!;
        node["id"] = id;
        node["ok"] = true;
        Write(node);
    }

    public void Error(int id, string error) => Write(new JsonObject { ["id"] = id, ["ok"] = false, ["error"] = error });

    private void Write(JsonObject node)
    {
        var line = node.ToJsonString(Json);
        lock (_writer) _writer.WriteLine(line);
    }
}
