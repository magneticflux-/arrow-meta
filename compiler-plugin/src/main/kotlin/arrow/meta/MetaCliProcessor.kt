package arrow.meta

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * CLI bootstrap service
 */
class MetaCliProcessor : CommandLineProcessor {

  /**
   * The Arrow Meta Compiler Plugin Id
   */
  override val pluginId: String = "arrow.meta.plugin.compiler"

  override val pluginOptions: Collection<CliOption> = emptyList()

  override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {}
}
