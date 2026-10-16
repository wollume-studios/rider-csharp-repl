using CSharpRepl.Host;

public class Poco { public int A { get; set; } public string? B { get; set; } }

public record Rec(int A, string B);

public class Node { public Node? Next { get; set; } }

public enum Color { Red, Green }

[Flags] public enum Perm { R = 1, W = 2 }

public class ValueToCodeTests
{
    private static string Code(object? v) =>
        ValueToCode.TryToCode(v, v?.GetType() ?? typeof(object), out var code) ? code : throw new Xunit.Sdk.XunitException("not reproducible: " + v);

    [Fact]
    public void Scalars()
    {
        Assert.Equal("0", Code(0));
        Assert.Equal("1.5", Code(1.5));
        Assert.Equal("2.0", Code(2.0));
        Assert.Equal("1.5m", Code(1.5m));
        Assert.Equal("2L", Code(2L));
        Assert.Equal("1.5f", Code(1.5f));
        Assert.Equal("float.NaN", Code(float.NaN));
        Assert.Equal("double.NegativeInfinity", Code(double.NegativeInfinity));
        Assert.Equal("int.MinValue", Code(int.MinValue));
        Assert.Equal("(byte)7", Code((byte)7));
        Assert.Equal("true", Code(true));
        Assert.Equal("'\\''", Code('\''));
    }

    [Fact]
    public void Strings_AreEscaped()
    {
        Assert.Equal("\"q\\\"\\\\\\n\\0😀\"", Code("q\"\\\n\0😀"));
        Assert.Equal("\"\\ud800x\\u2028\\u200b\"", Code("\ud800x" + (char)0x2028 + (char)0x200B)); // lone surrogate, line separator, zero-width space
    }

    [Fact]
    public void DateTime_Guid_Enum()
    {
        Assert.Equal("new DateTime(2026, 10, 16, 1, 2, 3, 0, DateTimeKind.Utc)", Code(new DateTime(2026, 10, 16, 1, 2, 3, DateTimeKind.Utc)));
        Assert.Equal("new DateTime(2026, 10, 16, 1, 2, 3, 0, DateTimeKind.Utc).AddTicks(5L)", Code(new DateTime(2026, 10, 16, 1, 2, 3, DateTimeKind.Utc).AddTicks(5)));
        Assert.Equal("new Guid(\"00000000-0000-0000-0000-000000000000\")", Code(Guid.Empty));
        Assert.Equal("DayOfWeek.Monday", Code(DayOfWeek.Monday));
        Assert.Equal("(Perm.R | Perm.W)", Code(Perm.R | Perm.W));
        Assert.Equal("(Color)(-1)", Code((Color)(-1)));
    }

    [Fact]
    public void Collections()
    {
        Assert.Equal("new List<int> { 1, 2 }", Code(new List<int> { 1, 2 }));
        Assert.Equal("new List<int>()", Code(new List<int>()));
        Assert.Equal("new Dictionary<string, int> { [\"a\"] = 1 }", Code(new Dictionary<string, int> { ["a"] = 1 }));
        Assert.Equal("new int[] { 1, 2 }", Code(new[] { 1, 2 }));
        Assert.Equal("new ValueTuple<int, string>(1, \"x\")", Code((1, "x")));
        Assert.Equal("new KeyValuePair<int, int>(1, 2)", Code(new KeyValuePair<int, int>(1, 2)));
    }

    [Fact]
    public void Objects()
    {
        Assert.Equal("new Poco { A = 1, B = \"x\" }", Code(new Poco { A = 1, B = "x" }));
        Assert.Equal("new Rec(1, \"x\")", Code(new Rec(1, "x")));
        Assert.Equal("new { A = 1, B = \"x\" }", Code(new { A = 1, B = "x" }));
    }

    [Fact]
    public void Declared_CastWhenItDiffers_NullIsTyped()
    {
        Assert.True(ValueToCode.TryToCode(1, typeof(object), out var code));
        Assert.Equal("(object)1", code);
        Assert.True(ValueToCode.TryToCode(-1, typeof(object), out code));
        Assert.Equal("(object)(-1)", code);
        Assert.True(ValueToCode.TryToCode(null, typeof(string), out code));
        Assert.Equal("(string?)null", code);
        Assert.True(ValueToCode.TryToCode(null, typeof(int?), out code));
        Assert.Equal("(int?)null", code);
    }

    [Fact]
    public void NotReproducible()
    {
        Assert.False(ValueToCode.TryToCode(new MemoryStream(), typeof(MemoryStream), out _));
        Assert.False(ValueToCode.TryToCode(new Func<int>(() => 1), typeof(Func<int>), out _));
        Assert.False(ValueToCode.TryToCode(typeof(Poco), typeof(Type), out _));
        var cyclic = new Node();
        cyclic.Next = cyclic;
        Assert.False(ValueToCode.TryToCode(cyclic, typeof(Node), out _));
        Assert.False(ValueToCode.TryToCode(new List<object> { new MemoryStream() }, typeof(List<object>), out _));
    }

    [Fact]
    public void MaxDepth_IsFive()
    {
        Node Chain(int links) { var n = new Node(); var head = n; for (var i = 0; i < links; i++) n = n.Next = new Node(); return head; }
        Assert.True(ValueToCode.TryToCode(Chain(5), typeof(Node), out _));  // 6 nodes: depths 0..5
        Assert.False(ValueToCode.TryToCode(Chain(6), typeof(Node), out _));
    }
}
