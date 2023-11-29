// simple method
void print()
{
    Console.WriteLine("Hello World");
}

bool HasContent(IEnumerable<string> strings)
{
    strings = strings;
    return strings.Count() > 0;
}

// non-compliant example
var start = DateTime.Now;
print();
HasContent(new List<string>() { "a", "b" });
Console.WriteLine($"{(DateTime.Now - start).TotalMilliseconds} ms");
