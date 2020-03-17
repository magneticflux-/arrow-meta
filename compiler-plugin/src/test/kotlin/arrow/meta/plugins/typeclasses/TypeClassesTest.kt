package arrow.meta.plugins.typeclasses

import arrow.meta.plugin.testing.CompilerTest
import arrow.meta.plugin.testing.Dependency
import arrow.meta.plugin.testing.assertThis
import org.junit.Test

class TypeClassesTest {

    //@Test
    fun `simple case`() {
        val arrowVersion = System.getProperty("ARROW_VERSION")
        val arrowCoreData = Dependency("arrow-core-data:$arrowVersion")
        val codeSnippet = """
       import arrowx.*
      
       //metadebug
        val aaaa = "1".monoidExt().mcombine("2").monoidExt().mcombine("3").monoidExt().mcombine("4")
        val b = "1".mcombine("2").mcombine("3").mcombine("4").mcombine("5")
        val c = String.mempty()
        val d = c.mcombine(b)
      """

        assertThis(CompilerTest(
            config = {
                metaDependencies + addDependencies(arrowCoreData)
            },
            code = {
                codeSnippet.source
            },
            assert = {
                allOf("d".source.evalsTo("12345"))
            }
        ))
    }

  @Test
  fun `polymorphic constrain`() {
    val arrowVersion = System.getProperty("ARROW_VERSION")
    val arrowCoreData = Dependency("arrow-core-data:$arrowVersion")
    val codeSnippet = """
       import arrowx.*
       import arrowx.given
       
       fun <A: @given Semigroup<A>> A.mappend(b: A): A =
          this@mappend.combine(b)

       //metadebug
        val result1 = String.empty()
        val result2 = "1".combine("1")
        val result3 = "2".mappend("2")
        val result = result1.combine(result2).combine(result3)
      """
    assertThis(CompilerTest(
      config = {
        metaDependencies + addDependencies(arrowCoreData)
      },
      code = {
        codeSnippet.source
      },
      assert = {
        allOf("result".source.evalsTo("1122"))
      }
    ))
  }

  @Test
  fun `given identity`() {
    val arrowVersion = System.getProperty("ARROW_VERSION")
    val arrowCoreData = Dependency("arrow-core-data:$arrowVersion")
    val codeSnippet = """
       import arrowx.*
       import arrowx.given
       import arrow.tuples.*
       
       fun <A> given(evidence: @given A = arrow.given): A =
          evidence

       //metadebug
        val result = given<Monoid<String>>().empty()
      """
    assertThis(CompilerTest(
      config = {
        metaDependencies + addDependencies(arrowCoreData)
      },
      code = {
        codeSnippet.source
      },
      assert = {
        allOf("result".source.evalsTo(""))
      }
    ))
  }
}

