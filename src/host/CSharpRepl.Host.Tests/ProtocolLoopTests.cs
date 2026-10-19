using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace CSharpRepl.Host.Tests;

public class ProtocolLoopTests
{
    private static readonly UTF8Encoding Utf8 = new(false);

    private sealed class Session : IDisposable
    {
        private readonly AnonymousPipeServerStream _inServer = new(PipeDirection.Out);
        private readonly AnonymousPipeServerStream _outServer = new(PipeDirection.Out);
        private readonly StreamWriter _requests;
        private readonly StreamReader _responses;
        public Task Loop { get; }

        public Session()
        {
            _requests = new StreamWriter(_inServer, Utf8) { AutoFlush = true, NewLine = "\n" };
            _responses = new StreamReader(new AnonymousPipeClientStream(PipeDirection.In, _outServer.ClientSafePipeHandle), Utf8);
            var input = Program.CreateInputReader(new AnonymousPipeClientStream(PipeDirection.In, _inServer.ClientSafePipeHandle));
            Loop = Task.Run(() => Program.RunLoop(input, _outServer, new ReplEngine()));
        }

        public Task Send(object request) => _requests.WriteLineAsync(JsonSerializer.Serialize(request));

        /// <summary>Writes one request line as raw UTF-8 bytes with non-ASCII characters left unescaped.</summary>
        public async Task SendRawUtf8(object request)
        {
            var json = JsonSerializer.Serialize(request, ProtocolWriter.Json);
            await _inServer.WriteAsync(Utf8.GetBytes(json + "\n"));
        }

        public async Task<JsonElement> Receive()
        {
            var line = await _responses.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(30));
            return JsonDocument.Parse(line!).RootElement;
        }

        public async Task Finish()
        {
            _requests.Close();
            await Loop.WaitAsync(TimeSpan.FromSeconds(30));
        }

        public void Dispose()
        {
            _responses.Dispose();
            _outServer.Dispose();
            _inServer.Dispose();
        }
    }

    [Fact]
    public async Task Loop_AnswersAnalyzeWhileRunIsBusy()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "System.Threading.Thread.Sleep(2000);" });
        await s.Send(new { id = 2, cmd = "analyze", code = "var r = 0" });

        var first = await s.Receive();
        Assert.Equal(2, first.GetProperty("id").GetInt32());
        Assert.True(first.GetProperty("ok").GetBoolean());
        Assert.False(first.GetProperty("complete").GetBoolean());
        Assert.Equal("var r = 0;", first.GetProperty("fixed").GetString());

        var second = await s.Receive();
        Assert.Equal(1, second.GetProperty("id").GetInt32());
        Assert.Equal("ok", second.GetProperty("status").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_AnswersCompleteWhileRunIsBusy()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "var abc = 1;" });
        Assert.Equal(1, (await s.Receive()).GetProperty("id").GetInt32());
        await s.Send(new { id = 2, cmd = "run", code = "System.Threading.Thread.Sleep(3000); var abd = 2;" });
        await s.Send(new { id = 3, cmd = "complete", code = "ab", position = 2 });

        var first = await s.Receive();
        Assert.Equal(3, first.GetProperty("id").GetInt32());
        Assert.True(first.GetProperty("ok").GetBoolean(), first.ToString());
        Assert.Equal(0, first.GetProperty("start").GetInt32());
        Assert.Equal(2, first.GetProperty("length").GetInt32());
        var labels = first.GetProperty("items").EnumerateArray().Select(i => i.GetProperty("label").GetString()).ToList();
        Assert.Contains("abc", labels);
        Assert.DoesNotContain("abd", labels); // the busy run has not finished: its variables don't exist yet
        Assert.Equal("variable", first.GetProperty("items")[0].GetProperty("kind").GetString());

        Assert.Equal(2, (await s.Receive()).GetProperty("id").GetInt32());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_AnswersClassifyWhileRunIsBusy_AndRunSendsSpans()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "System.Threading.Thread.Sleep(2000); var abc = 1;" });
        await s.Send(new { id = 2, cmd = "classify", code = "Math.Max(1, 2)" });

        var first = await s.Receive();
        Assert.Equal(2, first.GetProperty("id").GetInt32());
        var spans = first.GetProperty("spans").EnumerateArray().Select(e => (e.GetProperty("start").GetInt32(), e.GetProperty("length").GetInt32(), e.GetProperty("kind").GetString())).ToList();
        Assert.Contains((5, 3, "staticMethod"), spans);

        var run = await s.Receive();
        Assert.Equal(1, run.GetProperty("id").GetInt32());
        Assert.Contains(run.GetProperty("spans").EnumerateArray(), e => e.GetProperty("kind").GetString() == "staticMethod" && e.GetProperty("length").GetInt32() == 5); // Sleep
        await s.Finish();
    }

    [Fact]
    public async Task Loop_UnicodeRoundTrip()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "var s = \"ä\\\"\\n😀\";" });
        Assert.Equal("ok", (await s.Receive()).GetProperty("status").GetString());

        await s.Send(new { id = 2, cmd = "variables" });
        var v = Assert.Single((await s.Receive()).GetProperty("variables").EnumerateArray());
        Assert.Equal("s", v.GetProperty("name").GetString());
        Assert.Equal("\"ä\\\"\\n😀\"", v.GetProperty("value").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_RawUtf8Bytes_RoundTrip()
    {
        using var s = new Session();
        await s.SendRawUtf8(new { id = 1, cmd = "run", code = "var s = \"ä😀\"; s" });
        var run = await s.Receive();
        Assert.Equal("ok", run.GetProperty("status").GetString());
        Assert.Equal("\"ä😀\"", run.GetProperty("result").GetString());

        await s.SendRawUtf8(new { id = 2, cmd = "variables" });
        var v = Assert.Single((await s.Receive()).GetProperty("variables").EnumerateArray());
        Assert.Equal("\"ä😀\"", v.GetProperty("value").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_VariablesAndExpand_WaitForRunningSubmission()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "var list = new List<int> { 5 }; System.Threading.Thread.Sleep(1500);" });
        await s.Send(new { id = 2, cmd = "variables" });
        await s.Send(new { id = 3, cmd = "analyze", code = "1" });

        Assert.Equal(3, (await s.Receive()).GetProperty("id").GetInt32()); // analyze stays inline
        Assert.Equal(1, (await s.Receive()).GetProperty("id").GetInt32());
        var variables = await s.Receive();
        Assert.Equal(2, variables.GetProperty("id").GetInt32());
        var handle = variables.GetProperty("variables")[0].GetProperty("handle").GetInt32();

        await s.Send(new { id = 4, cmd = "expand", handle });
        var expand = await s.Receive();
        Assert.True(expand.GetProperty("ok").GetBoolean());
        var child = Assert.Single(expand.GetProperty("children").EnumerateArray());
        Assert.Equal("[0]", child.GetProperty("name").GetString());
        Assert.Equal("5", child.GetProperty("value").GetString());
        Assert.Equal(JsonValueKind.Null, child.GetProperty("handle").ValueKind);

        await s.Send(new { id = 5, cmd = "expand", handle = 9999 });
        Assert.False((await s.Receive()).GetProperty("ok").GetBoolean());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_AddReference_ReturnsAssembliesAndSurvivesErrors()
    {
        using var s = new Session();
        var csproj = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "Fixtures", "SampleLib", "SampleLib.csproj");
        await s.Send(new { id = 1, cmd = "addReference", kind = "project", path = Path.GetFullPath(csproj) });
        var added = await s.Receive();
        Assert.True(added.GetProperty("ok").GetBoolean(), added.ToString());
        Assert.Contains(added.GetProperty("assemblies").EnumerateArray(), a => a.GetString()!.EndsWith("SampleLib.dll"));
        Assert.Single(added.GetProperty("sourceDirs").EnumerateArray());

        await s.Send(new { id = 2, cmd = "addReference", kind = "dll", path = "no-such.dll" });
        var failed = await s.Receive();
        Assert.False(failed.GetProperty("ok").GetBoolean());
        Assert.Contains("no-such.dll", failed.GetProperty("error").GetString());

        await s.Send(new { id = 3, cmd = "run", code = "SampleLib.Greeter.Hi(\"p\")" });
        Assert.Equal("\"Hi p\"", (await s.Receive()).GetProperty("result").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_Run_ReportsAddedReferences()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "1" });
        var none = await s.Receive();
        Assert.Equal(JsonValueKind.Array, none.GetProperty("addedReferences").ValueKind);
        Assert.Equal(0, none.GetProperty("addedReferences").GetArrayLength());

        await s.Send(new { id = 2, cmd = "run", code = "#r \"nuget: Humanizer.Core, 2.14.1\"\n1" });
        var added = Assert.Single((await s.Receive()).GetProperty("addedReferences").EnumerateArray());
        Assert.Equal("nuget", added.GetProperty("kind").GetString());
        Assert.Equal("Humanizer.Core", added.GetProperty("pathOrName").GetString());
        Assert.Equal("2.14.1", added.GetProperty("version").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_Eof_CallsOnEofWithoutWaitingForTheRunningSubmission()
    {
        var eof = new TaskCompletionSource();
        var loop = Program.RunLoop(new StringReader("{\"id\":1,\"cmd\":\"run\",\"code\":\"System.Threading.Thread.Sleep(10000);\"}\n"),
            new MemoryStream(), new ReplEngine(), onEof: () => eof.SetResult());
        await eof.Task.WaitAsync(TimeSpan.FromSeconds(5)); // the host's Main exits here; the run is still sleeping
        Assert.False(loop.IsCompleted);
    }

    [Fact]
    public async Task Loop_UnknownCommand_IsError()
    {
        using var s = new Session();
        await s.Send(new { id = 7, cmd = "x" });
        var unknown = await s.Receive();
        Assert.Equal(7, unknown.GetProperty("id").GetInt32());
        Assert.False(unknown.GetProperty("ok").GetBoolean());
        Assert.Equal("unknown command: x", unknown.GetProperty("error").GetString());
        await s.Finish();
    }

    [Fact]
    public async Task Loop_Export_RunsAfterQueuedRunsAndHonorsExcludeThrown()
    {
        using var s = new Session();
        await s.Send(new { id = 1, cmd = "run", code = "var x = 1;" });
        await s.Send(new { id = 2, cmd = "run", code = "throw new Exception(\"boom\");" });
        await s.Send(new { id = 3, cmd = "export", filePath = Path.Combine(Path.GetTempPath(), "app.cs"), excludeThrown = true });
        await s.Receive();
        await s.Receive();
        var export = await s.Receive();
        Assert.True(export.GetProperty("ok").GetBoolean(), export.ToString());
        var text = export.GetProperty("text").GetString()!;
        Assert.Contains("var x = 1;", text);
        Assert.DoesNotContain("boom", text);
        Assert.Equal(JsonValueKind.Array, export.GetProperty("warnings").ValueKind);
        await s.Finish();
    }
}
