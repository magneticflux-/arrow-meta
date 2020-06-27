package arrow.meta.plugins.patternMatching

import arrow.meta.CliPlugin
import arrow.meta.Meta
import arrow.meta.phases.CompilerContext
import arrow.meta.plugin.testing.Assert
import arrow.meta.plugin.testing.CompilerTest
import arrow.meta.plugin.testing.CompilerTest.Companion.allOf
import arrow.meta.plugin.testing.CompilerTest.Companion.evalsTo
import arrow.meta.plugin.testing.CompilerTest.Companion.source
import arrow.meta.plugin.testing.assertThis
import org.junit.Test

open class PatternMatchingPlugin : Meta {
  override fun intercept(ctx: CompilerContext): List<CliPlugin> = listOf(
    patternMatching
  )
}

class PatternMatchingTests {
  private infix fun String.verify(assertion: (CompilerTest.Companion) -> Assert) = also {
    assertThis(CompilerTest(
      config = { listOf(CompilerTest.addMetaPlugins(PatternMatchingPlugin())) },
      code = { it.source }, assert = assertion
    ))
  }

  @Test
  fun `without case pattern match expression`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person("Matt", "Moore") -> "Matched"
           else -> "Not matched"
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo("Matched")
      )
    }
  }

  @Test
  fun `with case pattern match expression`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person(_, "Moore") -> "Matched"
           else -> "Not matched"
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo("Matched")
      )
    }
  }

  @Test
  fun `with case pattern second param match expression`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
           val person = Person("Matt", "Moore")

           val result = when (person) {
             Person("Matt", _) -> "Matched"
             else -> "Not matched"
           }
           """

    code verify {
      allOf(
        "result".source.evalsTo("Matched")
      )
    }
  }

  @Test
  fun `with case pattern captured param results in value`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person(capturedFirstName, _) -> capturedFirstName
           else -> "Not matched"
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo("Matt")
      )
    }
  }

  @Test
  fun `with case pattern captured second param results in value`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person(_, capturedSecondName) -> capturedSecondName
           else -> "Not matched"
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo("Moore")
      )
    }
  }

  @Test
  fun `with case pattern both captured params result in value`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person(capturedFirstName, capturedSecondName) -> capturedFirstName + capturedSecondName
           else -> "Not matched"
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo("MattMoore")
      )
    }
  }

  @Test
  fun `with case pattern both captured params can be used in a call`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")

         val result = when (person) {
           Person(capturedFirstName, capturedSecondName) -> {
             listOf(capturedFirstName, capturedSecondName)
           }
           else -> listOf("Not matched")
         }
         """

    code verify {
      allOf(
        "result".source.evalsTo(listOf("Matt", "Moore"))
      )
    }
  }

  @Test
  fun `with case pattern both captured params inside a function result in value`() {
    val code =
      """data class Person(val firstName: String, val lastName: String)
         val person = Person("Matt", "Moore")
         
         fun resolve(person: Person) =
           when (person) {
             Person(capturedFirstName, capturedSecondName) -> capturedFirstName + capturedSecondName
             else -> "Not matched"
           }

         val result = resolve(person)
         """

    code verify {
      allOf(
        "result".source.evalsTo("MattMoore")
      )
    }
  }
}