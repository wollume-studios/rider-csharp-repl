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
            var input = new StreamReader(new AnonymousPipeClientStream(PipeDirection.In, _inServer.ClientSafePipeHandle), Utf8);
            Loop = Task.Run(() => Program.RunLoop(input, _outServer, new ReplEngine()));
        }

        public Task Send(object request) => _requests.WriteLineAsync(JsonSerializer.Serialize(request));

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
}
