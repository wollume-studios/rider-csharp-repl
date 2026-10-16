using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace CSharpRepl.Host;

public static class Program
{
    public static async Task Main()
    {
        // The protocol owns the real stdout; anything else writing to it would corrupt the stream.
        var protocolOut = Console.OpenStandardOutput();
        Console.SetOut(TextWriter.Null);
        Console.SetError(TextWriter.Null);
        Console.SetIn(TextReader.Null); // user Console.ReadLine() must not consume protocol input
        try { ReferenceBuilder.CleanOldSnapshots(); } catch (Exception) { } // housekeeping must never stop the host
        var input = CreateInputReader(Console.OpenStandardInput());
        // stdin EOF means Rider is gone: exit now, even with a submission still running (it must not outlive the IDE).
        await RunLoop(input, protocolOut, new ReplEngine(), onEof: () => Environment.Exit(0));
    }

    public static StreamReader CreateInputReader(Stream stream) => new(stream, new UTF8Encoding(false));

    /// <summary>Reads requests until EOF, then calls <paramref name="onEof"/> (if any) before waiting for the queued work. `run`, `variables`, `expand`, `dump`, `restoreScript`, `export` (which can execute user code) and `addReference` (which mutates the script options) go through one sequential queue; `analyze` is answered immediately.</summary>
    public static async Task RunLoop(TextReader input, Stream output, ReplEngine engine, Action? onEof = null)
    {
        var writer = new ProtocolWriter(output);
        var runs = Channel.CreateUnbounded<Request>();
        var worker = Task.Run(async () =>
        {
            await foreach (var request in runs.Reader.ReadAllAsync())
                await Handle(request, writer, engine);
        });

        while (await input.ReadLineAsync() is { } line)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            Request? request;
            try { request = JsonSerializer.Deserialize<Request>(line, ProtocolWriter.Json); }
            catch (JsonException e) { writer.Error(0, "invalid request: " + e.Message); continue; }
            if (request == null) { writer.Error(0, "invalid request"); continue; }

            if (request.Cmd is "run" or "variables" or "expand" or "addReference" or "dump" or "restoreScript" or "export") await runs.Writer.WriteAsync(request);
            else await Handle(request, writer, engine);
        }

        runs.Writer.Complete();
        onEof?.Invoke();
        await worker;
    }

    private static async Task Handle(Request request, ProtocolWriter writer, ReplEngine engine)
    {
        try
        {
            switch (request.Cmd)
            {
                case "analyze":
                    var (complete, fixedCode) = InputAnalyzer.Analyze(request.Code ?? "");
                    writer.Ok(request.Id, new AnalyzeResponse(complete, fixedCode));
                    break;
                case "run":
                    writer.Ok(request.Id, await engine.RunAsync(request.Code ?? ""));
                    break;
                case "variables":
                    writer.Ok(request.Id, new VariablesResponse(engine.GetVariables()));
                    break;
                case "expand":
                    writer.Ok(request.Id, new ExpandResponse(engine.Expand(request.Handle ?? throw new ArgumentException("handle required"))));
                    break;
                case "addReference":
                    var built = await engine.AddReferenceAsync(request.Kind ?? "", request.Path, request.Name, request.Version);
                    writer.Ok(request.Id, new AddReferenceResponse(built.Assemblies, built.SourceDirs));
                    break;
                case "dump":
                    var (text, dumpBad) = engine.Dump(request.FilePath ?? throw new ArgumentException("filePath required"));
                    writer.Ok(request.Id, new DumpResponse(text, dumpBad));
                    break;
                case "restoreScript":
                    var (code, restoreBad) = engine.RestoreScript();
                    writer.Ok(request.Id, new RestoreScriptResponse(code, restoreBad));
                    break;
                case "export":
                    var (exported, warnings) = engine.Export(request.FilePath ?? throw new ArgumentException("filePath required"), request.ExcludeThrown ?? false);
                    writer.Ok(request.Id, new ExportResponse(exported, warnings));
                    break;
                default:
                    writer.Error(request.Id, "unknown command: " + request.Cmd);
                    break;
            }
        }
        catch (ReferenceException e)
        {
            writer.Error(request.Id, e.Message);
        }
        catch (Exception e)
        {
            writer.Error(request.Id, $"{e.GetType().Name}: {e.Message}");
        }
    }
}
