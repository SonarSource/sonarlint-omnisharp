using FluentAssertions;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace SonarLint.OmniSharp.Plugin.UnitTests
{
    [TestClass]
    public class Class1Tests
    {
        [TestMethod]
        public void TestDoStuff()
        {
            var testSubject = new Class1();

            testSubject.DoStuff(1, 2).Should().Be(3);
        }
    }
}
