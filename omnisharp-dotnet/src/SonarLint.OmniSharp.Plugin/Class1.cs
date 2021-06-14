using System;

namespace SonarLint.OmniSharp.Plugin
{
    internal class Class1
    {
        // TODO

        public int DoStuff(int a, int b)
        {
            if (a > 100)
            {
                throw new ArgumentException();
            }

            return a + b;
        }
    }
}
