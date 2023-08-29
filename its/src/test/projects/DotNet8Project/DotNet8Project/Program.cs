// simple method
void print()
{
    Console.WriteLine("Hello World");
}

// non-compliant example
var start = DateTime.Now;
print();
Console.WriteLine($"{(DateTime.Now - start).TotalMilliseconds} ms");
