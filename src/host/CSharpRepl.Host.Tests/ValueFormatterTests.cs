using static CSharpRepl.Host.ValueFormatter;

namespace CSharpRepl.Host.Tests;

public class ValueFormatterTests
{
    private static int _advanced;

    private static IEnumerable<int> InfiniteEnumerable()
    {
        while (true) { _advanced++; yield return 1; }
    }

    private sealed class Throwing { public override string ToString() => throw new InvalidOperationException("boom"); }

    private sealed class ThrowingGetter
    {
        public int Ok => 1;
        public int P => throw new InvalidOperationException("boom");
    }

    private sealed class Plain { public int X = 1; }

    [Fact]
    public void Display_Scalars()
    {
        Assert.Equal("null", Display(null));
        Assert.Equal("\"a\\\"b\"", Display("a\"b"));
        Assert.Equal("\"l1\\nl2\"", Display("l1\nl2"));
        Assert.Equal("'x'", Display('x'));
        Assert.Equal("1.5", Display(1.5m));
        Assert.Equal("true", Display(true));
        Assert.Equal("1.5", Display(1.5));
    }

    [Fact]
    public void Display_Collections_AreCountOrTypeAndNeverEnumerated()
    {
        Assert.Equal("Count = 2", Display(new List<int> { 1, 2 }));
        Assert.Equal("Count = 2", Display(new HashSet<int> { 1, 2 }));
        _advanced = 0;
        Assert.Equal("{IEnumerable<int>}", Display(InfiniteEnumerable()));
        Assert.Equal(0, _advanced);
    }

    [Fact]
    public void Display_Objects()
    {
        Assert.Equal("{InvalidOperationException: boom}", Display(new Throwing()));
        Assert.Equal("{FormatException: m}", Display(new FormatException("m")));
        Assert.Equal("Plain", Display(new Plain()));
        Assert.Equal("{ A = 1 }", Display(new { A = 1 }));
    }

    [Fact]
    public void Display_LongString_IsTruncated()
    {
        var shown = Display(new string('x', 5000));
        Assert.True(shown.Length <= 1002);
        Assert.Contains("…", shown);
    }

    [Fact]
    public void TypeName_UsesKeywordsAndGenericSyntax()
    {
        Assert.Equal("Dictionary<string, List<int?>>", TypeName(typeof(Dictionary<string, List<int?>>)));
        Assert.Equal("int[]", TypeName(typeof(int[])));
        Assert.Equal("int[,]", TypeName(typeof(int[,])));
    }

    [Fact]
    public void Children_OfObject_AreMembers()
    {
        Assert.Contains(Children(new { A = 1 }), c => c.Name == "A" && (int)c.Value! == 1);
        Assert.Contains(Children(new Plain()), c => c.Name == "X");
    }

    [Fact]
    public void Children_GetterException_IsShownAsValue()
    {
        var children = Children(new ThrowingGetter()).ToList();
        Assert.Equal("1", Display(children.Single(c => c.Name == "Ok").Value));
        Assert.Equal("{InvalidOperationException: boom}", Display(children.Single(c => c.Name == "P").Value));
    }

    [Fact]
    public void Children_OfArray_AreItems()
    {
        Assert.Equal(["[0]", "[1]", "[2]"], Children(new[] { 1, 2, 3 }).Select(c => c.Name));
    }

    [Fact]
    public void Children_OfBigList_AreCappedWithMoreEntry()
    {
        var children = Children(Enumerable.Range(0, 500).ToList()).ToList();
        Assert.Equal(101, children.Count);
        Assert.Equal("[99]", children[99].Name);
        Assert.Equal("…", children[100].Name);
        Assert.Equal("400 more", Display(children[100].Value));
        Assert.False(HasChildren(children[100].Value));
    }

    [Fact]
    public void Children_OfLazyEnumerable_TakeOnly100()
    {
        _advanced = 0;
        var children = Children(InfiniteEnumerable()).ToList();
        Assert.Equal(101, children.Count);
        Assert.Equal("…", children[100].Name);
        Assert.True(_advanced <= 101);
    }

    [Fact]
    public void Children_OfException_HaveMessageStackTraceInner()
    {
        var names = Children(new FormatException("m", new Exception("inner"))).Select(c => c.Name).ToList();
        Assert.Contains("Message", names);
        Assert.Contains("StackTrace", names);
        Assert.Contains("InnerException", names);
    }

    [Fact]
    public void HasChildren_OnlyForCompositeValues()
    {
        Assert.False(HasChildren(null));
        Assert.False(HasChildren(1));
        Assert.False(HasChildren("s"));
        Assert.False(HasChildren(1.5m));
        Assert.False(HasChildren(DayOfWeek.Monday));
        Assert.False(HasChildren(DateTime.Now));
        Assert.False(HasChildren(new List<int>()));
        Assert.True(HasChildren(new List<int> { 1 }));
        Assert.True(HasChildren(new Plain()));
        Assert.True(HasChildren(new Exception("x")));
    }
}
