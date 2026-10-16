namespace SampleLib;

public static class Greeter
{
    public static string Hi(string n) => $"Hi {n}";
}

public class Person
{
    public string Name { get; set; } = "";
    public int Age { get; set; }
}
