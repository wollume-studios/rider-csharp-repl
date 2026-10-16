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
        var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        await RunLoop(input, protocolOut, new ReplEngine());
    }

    /// <summary>Reads requests until EOF. `run` goes through one sequential queue; every other command is answered immediately.</summary>
    public static async Task RunLoop(TextReader input, Stream output, ReplEngine engine)
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

            if (request.Cmd == "run") await runs.Writer.WriteAsync(request);
            else await Handle(request, writer, engine);
        }

        runs.Writer.Complete();
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
                default:
                    writer.Error(request.Id, "unknown command: " + request.Cmd);
                    break;
            }
        }
        catch (Exception e)
        {
            writer.Error(request.Id, $"{e.GetType().Name}: {e.Message}");
        }
    }
}
