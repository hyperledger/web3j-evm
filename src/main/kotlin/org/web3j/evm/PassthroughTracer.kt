/*
 * Copyright 2019 Web3 Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.web3j.evm

import java.util.Optional
import org.hyperledger.besu.ethereum.core.Gas
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.vm.ehalt.ExceptionalHaltException
import org.web3j.evm.utils.NullReader
import java.io.BufferedReader
import java.io.File
import java.lang.StringBuilder

data class PassthroughTracerContext(val source: String = "")

class PassthroughTracer(metaFile: File? = File("build/resources/main/solidity")) : ConsoleDebugTracer(metaFile, BufferedReader(
    NullReader()
)) {
    private var passthroughTracerContext: PassthroughTracerContext = PassthroughTracerContext()

    fun lastContext() = passthroughTracerContext

    fun resetContext() {
        passthroughTracerContext = PassthroughTracerContext()
    }

    @Throws(ExceptionalHaltException::class)
    override fun traceExecution(
        messageFrame: MessageFrame,
        optional: Optional<Gas>,
        executeOperation: OperationTracer.ExecuteOperation
    ) {
        if (metaFile != null && metaFile.exists()) {
            val (sourceMapElement, sourceSection) = sourceAtMessageFrame(messageFrame)

            val sb = StringBuilder()

            if (sourceMapElement != null) sb.append("At ${sourceMapElement.sourceFileByteOffset}:${sourceMapElement.lengthOfSourceRange}:${sourceMapElement.sourceIndex}:")
            else sb.append("At unknown location:")

            sb.append('\n')
            sb.append('\n')

            sourceSection
                .dropWhile { it.isBlank() }
                .reversed()
                .dropWhile { it.isBlank() }
                .reversed()

            passthroughTracerContext = if (sourceSection.isEmpty())
                PassthroughTracerContext()
            else
                PassthroughTracerContext(sb.append(sourceSection.joinToString("\n")).toString())
        }

        executeOperation.execute()
    }
}
