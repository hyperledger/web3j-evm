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

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.EnumSet
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import org.hyperledger.besu.ethereum.core.Gas
import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.vm.ehalt.ExceptionalHaltException

class ConsoleDebugTracer(private val reader: BufferedReader) : OperationTracer {
    private val operations = ArrayList<String>()
    private val skipOperations = AtomicInteger()

    private enum class TERMINAL private constructor(private val escapeSequence: String) {
        ANSI_RESET("\u001B[0m"),
        ANSI_BLACK("\u001B[30m"),
        ANSI_RED("\u001B[31m"),
        ANSI_GREEN("\u001B[32m"),
        ANSI_YELLOW("\u001B[33m"),
        ANSI_BLUE("\u001B[34m"),
        ANSI_PURPLE("\u001B[35m"),
        ANSI_CYAN("\u001B[36m"),
        ANSI_WHITE("\u001B[37m"),
        CLEAR("\u001b[H\u001b[2J");

        override fun toString(): String {
            return escapeSequence
        }
    }

    @JvmOverloads
    constructor(stdin: InputStream = System.`in`) : this(BufferedReader(InputStreamReader(stdin)))

    @Throws(ExceptionalHaltException::class)
    override fun traceExecution(
        messageFrame: MessageFrame,
        optional: Optional<Gas>,
        executeOperation: OperationTracer.ExecuteOperation
    ) {
        val pauseOnNext = skipOperations.get() <= 1

        val sb = StringBuilder()
        val stackOutput = ArrayList<String>()

        if (operations.isNotEmpty()) {
            sb.append(TERMINAL.CLEAR)
        }

        operations.add(String.format(NUMBER_FORMAT, messageFrame.pc) + " " + messageFrame.currentOperation.name)

        for (i in 0 until messageFrame.stackSize()) {
            stackOutput.add(String.format(NUMBER_FORMAT, i) + " " + messageFrame.getStackItem(i))
        }

        for (i in operations.indices) {
            if (i > 0) {
                sb.append('\n')
            }

            val haveActiveLastOpLine = i + 1 == operations.size && pauseOnNext
            val haveActiveStackOutput = i + 2 == operations.size && stackOutput.isNotEmpty()
            val operation =
                (if (haveActiveLastOpLine) "" + TERMINAL.ANSI_GREEN + "> " else "  ") + operations[i] + TERMINAL.ANSI_RESET

            sb.append(operation)

            run {
                var j = cleanString(operation).length
                while (j < OP_CODES_WIDTH && haveActiveStackOutput) {
                    sb.append(" ")
                    j++
                }
            }

            if (haveActiveStackOutput) {
                val stackHeader = "" + TERMINAL.ANSI_GREEN + "-- Stack "
                sb.append(stackHeader)
                sb.append("-".repeat(max(0, FULL_WIDTH - OP_CODES_WIDTH - cleanString(stackHeader).length)))
                sb.append(TERMINAL.ANSI_RESET)
            }

            var j = cleanString(operation).length
            while (j < OP_CODES_WIDTH && i + 1 == operations.size) {
                sb.append(" ")
                j++
            }
        }

        if (stackOutput.isEmpty()) {
            sb.append('\n')
        }

        for (i in stackOutput.indices) {
            if (i > 0) {
                sb.append(" ".repeat(OP_CODES_WIDTH))
            }

            sb.append(stackOutput[i])
            sb.append('\n')
        }

        val opCount = "- " + String.format(NUMBER_FORMAT, operations.size) + " "
        val options = if (pauseOnNext) {
            "--> " +
            TERMINAL.ANSI_YELLOW + "[enter]" + TERMINAL.ANSI_RESET + " = next op, " +
            TERMINAL.ANSI_YELLOW + "[number]" + TERMINAL.ANSI_RESET + " = next X ops, " +
            TERMINAL.ANSI_YELLOW + "end" + TERMINAL.ANSI_RESET + " = run till end, " +
            TERMINAL.ANSI_RED + "abort" + TERMINAL.ANSI_RESET + " = terminate "
        } else ""

        sb.append(opCount)
        sb.append(options)
        sb.append("-".repeat(max(0, FULL_WIDTH - opCount.length - cleanString(options).length)))
        sb.append('\n')

        val finalOutput = nextOption(sb.toString())

        executeOperation.execute()

        if (messageFrame.state != MessageFrame.State.CODE_EXECUTING) {
            skipOperations.set(0)
            operations.clear()
            println(finalOutput)
        }
    }

    @Throws(ExceptionalHaltException::class)
    private fun nextOption(output: String): String {
        if (skipOperations.decrementAndGet() > 0) {
            return output
        }

        try {
            print("$output: ")

            val input = reader.readLine()

            if (input == null) {
                skipOperations.set(Integer.MAX_VALUE)
            } else if (input.trim { it <= ' ' }.toLowerCase() == "abort") {
                val enumSet = EnumSet.allOf(ExceptionalHaltReason::class.java)
                enumSet.add(ExceptionalHaltReason.NONE)
                throw ExceptionalHaltException(enumSet)
            } else if (input.trim { it <= ' ' }.toLowerCase() == "end") {
                skipOperations.set(Integer.MAX_VALUE)
            } else if (!input.trim { it <= ' ' }.isEmpty()) {
                val x = Integer.parseInt(input)
                skipOperations.set(max(x, 1))
            }

            return ""
        } catch (ex: NumberFormatException) {
            return nextOption(output)
        } catch (ex: IOException) {
            val enumSet = EnumSet.allOf(ExceptionalHaltReason::class.java)
            enumSet.add(ExceptionalHaltReason.NONE)
            throw ExceptionalHaltException(enumSet)
        }
    }

    companion object {
        private const val OP_CODES_WIDTH = 30
        private const val FULL_WIDTH = OP_CODES_WIDTH + 77
        private const val NUMBER_FORMAT = "0x%08x"

        private fun cleanString(input: String): String {
            return TERMINAL.values().fold(input) { output, t -> output.replace(t.toString(), "") }
        }
    }
}
