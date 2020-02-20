/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data

import com.sun.jdi.*
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.idea.debugger.SUSPEND_LAMBDA_CLASSES
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.AsyncStackTraceContext
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.isSubtype
import org.jetbrains.kotlin.idea.debugger.safeVisibleVariableByName

data class ContinuationHolder(val continuation: ObjectReference) {
    fun context(context: ExecutionContext, method: Method) =
        AsyncStackTraceContext(context, method, this)

    fun referenceType(): ClassType? =
        continuation.referenceType() as? ClassType

    fun value() =
        continuation

    fun field(field: Field): Value? =
        continuation.getValue(field)

    private fun findBaseContinuationSuperSupertype(): ClassType? {
        return findBaseContinuationSuperSupertype(continuation.referenceType() as? ClassType ?: return null)
    }

    private fun findBaseContinuationSuperSupertype(type: ClassType): ClassType? {
        var t: ClassType? = type;
        do {
            if (t?.name() == BASE_CONTINUATION_IMPL_CLASS_NAME)
                return type
            t = t?.superclass();
        } while (t != null)
        return null
    }

    fun findCompletion(): ContinuationHolder? {
        val baseContinuationSupertype = findBaseContinuationSuperSupertype() ?: return null
        val completionField = baseContinuationSupertype.fieldByName("completion") ?: return null
        return ContinuationHolder(continuation.getValue(completionField) as? ObjectReference ?: return null)
    }

    companion object {
        const val BASE_CONTINUATION_IMPL_CLASS_NAME = "kotlin.coroutines.jvm.internal.BaseContinuationImpl"

        fun lookup(context: ExecutionContext, method: Method): ContinuationHolder? {
            if (isInvokeSuspendMethod(method)) {
                val tmp = context.frameProxy.thisObject() ?: return null
                if (!isSuspendLambda(tmp.referenceType()))
                    return null
                return ContinuationHolder(tmp)
            } else if (isContinuationProvider(method)) {
                val frameProxy = context.frameProxy
                val continuationVariable = frameProxy.safeVisibleVariableByName(CONTINUATION_VARIABLE_NAME) ?: return null
                val tmp = frameProxy.getValue(continuationVariable) as? ObjectReference ?: return null
                context.keepReference(tmp)
                return ContinuationHolder(tmp)
            } else {
                return null
            }
        }

        private fun isInvokeSuspendMethod(method: Method): Boolean =
            method.name() == "invokeSuspend" && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;"

        private fun isContinuationProvider(method: Method): Boolean =
            "Lkotlin/coroutines/Continuation;)" in method.signature()

        private fun isSuspendLambda(referenceType: ReferenceType): Boolean =
            SUSPEND_LAMBDA_CLASSES.any { referenceType.isSubtype(it) }

        /**
         * Find continuation for the [frame]
         * Gets current CoroutineInfo.lastObservedFrame and finds next frames in it until null or needed stackTraceElement is found
         * @return null if matching continuation is not found or is not BaseContinuationImpl
         */
        fun lookup(context: ExecutionContext, initialContinuation: ObjectReference?, frame: StackTraceElement): ContinuationHolder? {
            var continuation = initialContinuation ?: return null
            val classLine = ClassNameLineNumber(frame.className, frame.lineNumber)

            do {
                val position = getClassAndLineNumber(context, continuation)
                // while continuation is BaseContinuationImpl and it's frame equals to the current
                continuation = getNextFrame(context, continuation) ?: return null
            } while (continuation.type().isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME) && position != classLine)


            return if (continuation.type().isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME))
                ContinuationHolder(continuation)
            else
                return null
        }

        /**
         * Finds previous Continuation for this Continuation (completion field in BaseContinuationImpl)
         * @return null if given ObjectReference is not a BaseContinuationImpl instance or completion is null
         */
        private fun getNextFrame(context: ExecutionContext, continuation: ObjectReference ): ObjectReference? {
            val type = continuation.type() as ClassType
            if (!type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME))
                return null
            val next = type.concreteMethodByName("getCompletion", "()Lkotlin/coroutines/Continuation;")
            return context.invokeMethod(continuation, next, emptyList()) as? ObjectReference
        }

        data class ClassNameLineNumber(val className: String?, val lineNumber: Int?)

        private fun getClassAndLineNumber(context: ExecutionContext, continuation: ObjectReference): ClassNameLineNumber {
            val objectReference = findStackTraceElement(context, continuation) ?: return ClassNameLineNumber(null, null)
            val classStackTraceElement = context.findClass("java.lang.StackTraceElement") as ClassType
            val getClassName = classStackTraceElement.concreteMethodByName("getClassName", "()Ljava/lang/String;")
            val getLineNumber = classStackTraceElement.concreteMethodByName("getLineNumber", "()I")
            val className = (context.invokeMethod(objectReference, getClassName, emptyList()) as StringReference).value()
            val lineNumber = (context.invokeMethod(objectReference, getLineNumber, emptyList()) as IntegerValue).value()
            return ClassNameLineNumber(className, lineNumber)
        }

        private fun findStackTraceElement(context: ExecutionContext, continuation: ObjectReference): ObjectReference? {
            val classType = continuation.type() as ClassType
            val methodGetStackTraceElement = classType.concreteMethodByName("getStackTraceElement", "()Ljava/lang/StackTraceElement;")
            return context.invokeMethod(continuation, methodGetStackTraceElement, emptyList()) as? ObjectReference
        }

    }
}

