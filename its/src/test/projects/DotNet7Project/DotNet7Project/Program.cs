// See https://aka.ms/new-console-template for more information

// TODO foo
Console.WriteLine("Hello, World!");

public sealed record Foo
{
    public required Bar Baz { get; init; }  // "Bar" is flagged with S1104: Fields should not have public accessibility
}

public sealed record Bar
{
}