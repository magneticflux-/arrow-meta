package arrow.meta.plugins.proofs.phases.ir

import arrow.meta.log.Log
import arrow.meta.log.invoke
import arrow.meta.phases.CompilerContext
import arrow.meta.phases.codegen.ir.IrUtils
import arrow.meta.phases.codegen.ir.dfsCalls
import arrow.meta.phases.codegen.ir.returnType
import arrow.meta.phases.codegen.ir.substitutedValueParameters
import arrow.meta.phases.resolve.baseLineTypeChecker
import arrow.meta.phases.resolve.typeArgumentsMap
import arrow.meta.phases.resolve.unwrappedNotNullableType
import arrow.meta.plugins.proofs.phases.ExtensionProof
import arrow.meta.plugins.proofs.phases.GivenProof
import arrow.meta.plugins.proofs.phases.Proof
import arrow.meta.plugins.proofs.phases.extensionProof
import arrow.meta.plugins.proofs.phases.givenProof
import arrow.meta.plugins.proofs.phases.resolve.GivenUpperBound
import org.jetbrains.kotlin.backend.common.ir.returnType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.resolve.calls.inference.components.NewTypeSubstitutorByConstructorMap
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter

@Suppress("RedundantUnitReturnType")
class ProofsIrCodegen(
  val irUtils: IrUtils
) {

  fun IrUtils.matchedCandidateProofCall(
    fn: CallableDescriptor,
    typeSubstitutor: NewTypeSubstitutorByConstructorMap
  ): IrExpression {
    val irTypes = fn.substitutedIrTypes(typeSubstitutor).filterNotNull()
    return fn.irCall().apply {
      if (this is IrMemberAccessExpression<*>) {
        fn.typeParameters.forEachIndexed { n, descriptor ->
          //TODO determine why sometimes type susbtitution returns unbound type args. Ex: fun <A> SecondN<FirstN<A>>.flatten(): Second<A>
          putTypeArgument(n, irTypes.getOrElse(n) { pluginContext.irBuiltIns.nothingType })
        }
      }
    }
  }

  fun CompilerContext.givenProofCall(
    superType: KotlinType
  ): IrExpression? =
    irUtils.run {
      val candidate = givenProof(superType)
      candidate?.let { proof ->
        substitutedProofCall(proof, superType)
      }
    }

  private fun IrUtils.substitutedProofCall(proof: GivenProof, superType: KotlinType): IrExpression? =
    matchedCandidateProofCall(
      fn = proof.callableDescriptor,
      typeSubstitutor = proof.substitutor(superType)
    )

  /**
   * applicable for both  [@Extension] and [@Coercion]
   */
  fun CompilerContext.extensionProofCall(
    subType: KotlinType,
    superType: KotlinType
  ): IrExpression? =
    irUtils.run {
      val candidate = extensionProof(subType, superType)
      candidate?.let {
        matchedCandidateProofCall(
          fn = it.through,
          typeSubstitutor = it.substitutor(subType)
        )
      }
    }


  fun Proof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    fold(
      given = { substitutor(superType) },
      coercion = { substitutor(superType) },
      projection = { substitutor(superType) }
    )

  fun ExtensionProof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    ProofCandidate(
      proofType = from,
      otherType = superType.unwrappedNotNullableType,
      through = through
    ).typeSubstitutor

  fun GivenProof.substitutor(
    superType: KotlinType
  ): NewTypeSubstitutorByConstructorMap =
    ProofCandidate(
      proofType = to,
      otherType = superType.unwrappedNotNullableType,
      through = through
    ).typeSubstitutor


  fun CompilerContext.proveVariable(it: IrVariable): IrVariable? {
    val targetType = it.type.originalKotlinType
    val valueType = it.initializer?.type?.originalKotlinType
    return if (targetType != null && valueType != null) {
      it.apply {
        val proofCall = extensionProofCall(valueType, targetType)
        if (proofCall is IrMemberAccessExpression<*>) {
          proofCall.extensionReceiver = initializer
        }
        proofCall?.also {
          initializer = it
        }
      }
    } else it
  }

  fun CompilerContext.proveNestedCalls(expression: IrCall): IrCall? =
    expression.apply {
      dfsCalls().forEach {
        proveCall(it)
      }
    }

  private fun CompilerContext.proveCall(expression: IrCall): IrCall =
    Log.Verbose({ "insertProof:\n ${expression.dump()} \nresult\n ${this.dump()}" }) {
      val givenTypeParamUpperBound = GivenUpperBound(expression)
      val upperBound = givenTypeParamUpperBound.givenUpperBound
      if (upperBound != null) insertGivenCall(givenTypeParamUpperBound, expression)
      else insertExtensionSyntaxCall(expression)
      expression
    }

  private fun CompilerContext.insertExtensionSyntaxCall(expression: IrCall) = irUtils.run {
    val valueType = expression.dispatchReceiver?.type?.originalKotlinType
      ?: expression.extensionReceiver?.type?.originalKotlinType
      ?: (if (expression.valueArgumentsCount > 0) expression.getValueArgument(0)?.type?.originalKotlinType else null)
    val targetType = expression.symbol.owner.extensionReceiverParameter?.type?.originalKotlinType
      ?: expression.substitutedValueParameters.firstOrNull()?.second?.originalKotlinType
    if (targetType != null && valueType != null && targetType != valueType && !baseLineTypeChecker.isSubtypeOf(valueType, targetType)) {
      expression.apply {
        val proofCall = extensionProofCall(valueType, targetType)
        if (proofCall is IrMemberAccessExpression<*>) {
          when {
            dispatchReceiver != null -> {
              proofCall.extensionReceiver = dispatchReceiver
              dispatchReceiver = proofCall
            }
            extensionReceiver != null -> {
              proofCall.extensionReceiver = extensionReceiver
              dispatchReceiver = null
              extensionReceiver = proofCall
            }
            (valueType != targetType && expression.valueArgumentsCount > 0) -> {
              dispatchReceiver = null

              expression.symbol.owner.valueParameters.forEachIndexed { n: Int, _ ->
                val valueArgument = expression.getValueArgument(n)
                val valueType2 = valueArgument?.type?.originalKotlinType!!
                val targetType2 = expression.substitutedValueParameters[n].second?.originalKotlinType!!
                val proofCall2 = extensionProofCall(valueType2, targetType2) as? IrMemberAccessExpression<*>
                if (proofCall2 != null) {
                  proofCall2.extensionReceiver = valueArgument
                  if (proofCall2.typeArgumentsCount > 0) {
                    proofCall2.putTypeArgument(0, valueType.toIrType())
                  }
                  expression.putValueArgument(n, proofCall2)
                } else {
                  expression.putValueArgument(n, valueArgument)
                }
              }
            }
          }
          symbol.owner
        }
      }
    }
  }

  private fun CompilerContext.insertGivenCall(
    givenUpperBound: GivenUpperBound,
    expression: IrCall
  ): Unit {
    val upperBound = givenUpperBound.givenUpperBound
    if (upperBound != null) {
      givenUpperBound.givenValueParameters.forEachIndexed { index, (_, superType) ->
        givenProofCall(superType?.originalKotlinType!!)?.apply {
          if (expression.getValueArgument(index) == null)
            expression.putValueArgument(index, this)
        }
      }
    }
  }

  fun CompilerContext.proveProperty(it: IrProperty): IrProperty? {
    val targetType = it.getter?.returnType?.originalKotlinType
    val valueType = it.backingField?.initializer?.expression?.type?.originalKotlinType
    return if (targetType != null && valueType != null && targetType != valueType) {
      it.backingField?.let { field ->
        val replacement = field.initializer?.expression?.let {
          extensionProofCall(valueType, targetType)?.apply {
            if (this is IrMemberAccessExpression<*>)
              extensionReceiver = it
          }
        }
        replacement?.let { field.initializer?.expression = it }
        it
      }
    } else it
  }

  fun CompilerContext.proveReturn(it: IrReturn): IrReturn? =
    irUtils.run {
      val targetType = it.returnTargetSymbol.owner.returnType(pluginContext.irBuiltIns).originalKotlinType
      val valueType = it.value.type.originalKotlinType
      return if (targetType != null && valueType != null && targetType != valueType) {
        extensionProofCall(valueType, targetType)?.let { call ->
          if (call is IrMemberAccessExpression<*>)
            call.extensionReceiver = it.value

          IrReturnImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            targetType.toIrType(),
            it.returnTargetSymbol,
            call
          )
        } ?: it
      } else it
    }

  fun CompilerContext.proveTypeOperator(it: IrTypeOperatorCall): IrExpression? {
    val targetType = it.type.originalKotlinType
    val valueType = it.argument.type.originalKotlinType
    return if (targetType != valueType) {
      extensionProofCall(valueType!!, targetType!!)?.let { call ->
        if (call is IrMemberAccessExpression<*>)
          call.extensionReceiver = it.argument
        call
      }
    } else it
  }

  companion object {
    operator fun <A> invoke(irUtils: IrUtils, f: ProofsIrCodegen.() -> A): A =
      f(ProofsIrCodegen(irUtils))
  }
}

val ProofCandidate.typeSubstitutor: NewTypeSubstitutorByConstructorMap
  get() {
    val allArgsMap =
      proofType.typeArgumentsMap(otherType)
        .filter { it.key.type.isTypeParameter() } +
        mapOf(
          through.module.builtIns.nothingType.asTypeProjection() to TypeUtils.DONT_CARE.asTypeProjection()
        )
    return NewTypeSubstitutorByConstructorMap(
      allArgsMap.map {
        it.key.type.constructor to it.value.type.unwrap()
      }.toMap()
    )
  }
