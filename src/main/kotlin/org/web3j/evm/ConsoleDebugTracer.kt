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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.SortedMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.web3j.evm.entity.ContractMapping
import org.web3j.evm.entity.source.SourceFile
import org.web3j.evm.entity.source.SourceLine
import org.web3j.evm.entity.source.SourceMapElement
import org.web3j.evm.utils.SourceMappingUtils

open class ConsoleDebugTracer(protected val metaFile: File?, private val reader: BufferedReader) : OperationTracer {
    private val operations = ArrayList<String>()
    private val skipOperations = AtomicInteger()
    private val breakPoints = mutableMapOf<String, MutableSet<Int>>()
    private val commandOutputs = mutableListOf<String>()
    private val byteCodeContractMapping = HashMap<Pair<String, Boolean>, ContractMapping>()

    private var runTillEnd = false
    private var showOpcodes = true
    private var showStack = true
    private var lastSourceFile = SourceFile()
    private var lastSourceMapElement: SourceMapElement? = null

    private enum class TERMINAL constructor(private val escapeSequence: String) {
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
    constructor(metaFile: File? = File("build/resources/main/solidity"), stdin: InputStream = System.`in`) : this(metaFile, BufferedReader(
        InputStreamReader(stdin)
    ))

    protected fun sourceAtMessageFrame(messageFrame: MessageFrame): Pair<SourceMapElement?, SourceFile> {
        val sourceFileBodyTransform = fun(key: Int, value: String): Pair<Int, String> = Pair(key, "${TERMINAL.ANSI_YELLOW}${value}${TERMINAL.ANSI_RESET}")

        return SourceMappingUtils.sourceAtMessageFrame(
            messageFrame,
            metaFile,
            lastSourceFile,
            byteCodeContractMapping,
            sourceFileBodyTransform
        )
    }

    protected fun mergeSourceContent(sourceContent: SortedMap<Int, SourceLine>): List<String> {
        return sourceContent
            .entries
            .map {
                if (it.key > 0) {
                    val lineNumber = ("%" + sourceContent.lastKey().toString().length + "s").format(it.key.toString())
                    val lineNumberSpacing = "%-4s".format(lineNumber)
                    return@map "$lineNumberSpacing ${it.value.line}"
                } else {
                    return@map it.value.line
                }
        }.toList()
    }

    private fun isBreakPointActive(filePath: String?, activeLines: Set<Int>): Boolean {
        val relevantBreakPoints = if (filePath != null) breakPoints
            .entries
            .filter { filePath.endsWith(it.key) }
            .flatMap { it.value }
        else return false

        return activeLines.any { relevantBreakPoints.contains(it) }
    }

    private fun parseBreakPointOption(input: String) {
        val inputParts = input.split(" +".toRegex())
        if (inputParts.size < 2) return

        when (inputParts[1].toLowerCase()) {
            "clear" -> {
                commandOutputs.add("${TERMINAL.ANSI_CYAN}Cleared ${breakPoints.size} breakpoints: ${breakPoints.entries.sortedBy { it.key }.joinToString { it.key + ": " + it.value.sorted() }}${TERMINAL.ANSI_RESET}")
                breakPoints.clear()
            }
            "list" -> {
                if (breakPoints.values.none { it.isNotEmpty() })
                    commandOutputs.add("${TERMINAL.ANSI_CYAN}No active breakpoints${TERMINAL.ANSI_RESET}")
                else
                    commandOutputs.add("${TERMINAL.ANSI_CYAN}Active breakpoints: ${breakPoints.entries.filter { it.value.isNotEmpty() }.sortedBy { it.key }.joinToString { it.key + ": " + it.value.sorted() }}${TERMINAL.ANSI_RESET}")
            }
            else -> {
                if (inputParts.size != 3) return

                val file = inputParts[1]
                val line = Integer.parseInt(inputParts[2])
                if (breakPoints[file]?.contains(line) == true) {
                    breakPoints[file]?.remove(line)
                    commandOutputs.add("${TERMINAL.ANSI_CYAN}Removed breakpoint on $file:$line${TERMINAL.ANSI_RESET}")
                } else {
                    breakPoints.getOrPut(file) { mutableSetOf() }.add(line)
                    commandOutputs.add("${TERMINAL.ANSI_CYAN}Added breakpoint on $file:$line${TERMINAL.ANSI_RESET}")
                }
            }
        }
    }

    private fun parseShowOption(input: String) {
        val inputParts = input.split(" +".toRegex())
        if (inputParts.size < 2) return

        when (inputParts[1].toLowerCase()) {
            "opcodes" -> {
                showOpcodes = true
                commandOutputs.add("${TERMINAL.ANSI_CYAN}Showing opcodes${TERMINAL.ANSI_RESET}")
            }
            "stack" -> {
                showStack = true
                commandOutputs.add("${TERMINAL.ANSI_CYAN}Showing stack${TERMINAL.ANSI_RESET}")
            }
        }
    }

    private fun parseHideOption(input: String) {
        val inputParts = input.split(" +".toRegex())
        if (inputParts.size < 2) return

        when (inputParts[1].toLowerCase()) {
            "opcodes" -> {
                showOpcodes = false
                commandOutputs.add("${TERMINAL.ANSI_CYAN}Hiding opcodes${TERMINAL.ANSI_RESET}")
            }
            "stack" -> {
                showStack = false
                commandOutputs.add("${TERMINAL.ANSI_CYAN}Hiding stack${TERMINAL.ANSI_RESET}")
            }
        }
    }

    private fun addHelp(command: String, desc: String) {
        commandOutputs.add(command + " ".repeat(40 - cleanString(command).length) + desc)
    }

    private fun showHelp() {
        addHelp("${TERMINAL.ANSI_YELLOW}[enter]${TERMINAL.ANSI_RESET}", "Continue running until next code section")
        addHelp("${TERMINAL.ANSI_YELLOW}[number]${TERMINAL.ANSI_RESET}", "Step forward X number of opcodes")
        addHelp("${TERMINAL.ANSI_YELLOW}next${TERMINAL.ANSI_RESET}", "Run until the next breakpoint")
        addHelp("${TERMINAL.ANSI_YELLOW}end${TERMINAL.ANSI_RESET}", "Run until the end of current transaction")
        addHelp("${TERMINAL.ANSI_RED}abort${TERMINAL.ANSI_RESET}", "Terminate the function call")
        commandOutputs.add("")
        addHelp("${TERMINAL.ANSI_YELLOW}show|hide opcodes${TERMINAL.ANSI_RESET}", "Show or hide opcodes")
        addHelp("${TERMINAL.ANSI_YELLOW}show|hide stack${TERMINAL.ANSI_RESET}", "Show or hide the stack")
        commandOutputs.add("")
        addHelp("${TERMINAL.ANSI_YELLOW}break [file name] [line number]${TERMINAL.ANSI_RESET}", "Add or remove a breakpoint")
        addHelp("${TERMINAL.ANSI_YELLOW}break list${TERMINAL.ANSI_RESET}", "Show all breakpoint")
        addHelp("${TERMINAL.ANSI_YELLOW}break clear${TERMINAL.ANSI_RESET}", "Remove all breakpoint")
    }

    @Throws(ExceptionalHaltException::class)
    private fun nextOption(messageFrame: MessageFrame, rerender: Boolean = false): String {
        val stackOutput = ArrayList<String>()

        for (i in 0 until messageFrame.stackSize()) {
            stackOutput.add(String.format(NUMBER_FORMAT, i) + " " + messageFrame.getStackItem(i))
        }

        val sb = StringBuilder()

        if (operations.isNotEmpty()) {
            sb.append(TERMINAL.CLEAR)
        }

        if (!rerender) operations.add(String.format(NUMBER_FORMAT, messageFrame.pc) + " " + messageFrame.currentOperation.name)

        for (i in operations.indices) {
            val haveActiveLastOpLine = i + 1 == operations.size
            val haveActiveStackOutput = i + 2 == operations.size && stackOutput.isNotEmpty()

            if (showOpcodes && i > 0) {
                sb.append('\n')
            } else if (showStack && haveActiveLastOpLine) {
                sb.append('\n')
            }

            val operation =
                (if (haveActiveLastOpLine) "" + TERMINAL.ANSI_GREEN + "> " else "  ") + operations[i] + TERMINAL.ANSI_RESET

            if (showOpcodes) {
                sb.append(operation)
            } else if (showStack && (i + 1 == operations.size || i + 2 == operations.size)) {
                sb.append(" ".repeat(cleanString(operation).length))
            }

            if (haveActiveStackOutput && showStack) {
                sb.append(" ".repeat((cleanString(operation).length..OP_CODES_WIDTH).count() - 1))
                sb.append(STACK_HEADER)
                sb.append("-".repeat(max(0, FULL_WIDTH - OP_CODES_WIDTH - cleanString(STACK_HEADER).length)))
                sb.append(TERMINAL.ANSI_RESET)
            }

            if (i + 1 == operations.size && showStack) {
                sb.append(" ".repeat((cleanString(operation).length..OP_CODES_WIDTH).count() - 1))
            }
        }

        if (showStack) {
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
        } else if (showOpcodes) {
            sb.append('\n')
        }

        // Source code section start
        val (sourceMapElement, sourceFile) = sourceAtMessageFrame(messageFrame)

        val (filePath, sourceSection) = sourceFile

        if (metaFile != null && metaFile.exists()) {
            if (sourceMapElement != null) {
                val subText = StringBuilder()

                with(subText) {
                    append("- ")
                    append(sourceMapElement.sourceFileByteOffset)
                    append(":")
                    append(sourceMapElement.lengthOfSourceRange)
                    append(":")
                    append(sourceMapElement.sourceIndex)
                    append(":")
                    append(sourceMapElement.jumpType)
                    append(" ")

                    if (filePath == null) {
                        append("Unknown source file")
                    } else {
                        val firstSelectedLine = sourceSection.entries.filter { it.value.selected }.map { it.key }.min() ?: 0
                        val firstSelectedOffset = sourceSection[firstSelectedLine]?.offset ?: 0

                        append(filePath)
                        append(": (")
                        append(firstSelectedLine)
                        append(", ")
                        append(firstSelectedOffset)
                        append(")")
                    }
                    append(" ")
                }

                sb.append(subText)
                sb.append("-".repeat(FULL_WIDTH - subText.length))
            } else {
                sb.append("-".repeat(FULL_WIDTH))
            }

            sb.append('\n')

            mergeSourceContent(sourceSection)
                .dropWhile { it.isBlank() }
                .reversed()
                .dropWhile { it.isBlank() }
                .reversed()
                .take(10)
                .forEach {
                    sb.append(it)
                    sb.append('\n')
                }
            sb.append(TERMINAL.ANSI_RESET)
        }
        // Source code section end

        val activeLines = sourceSection
            .entries
            .filter { it.value.selected }
            .map { it.key }
            .toSet()

        val haveCommandOutput = commandOutputs.isNotEmpty()
        val haveActiveBreakPoint = isBreakPointActive(filePath, activeLines)

        val pauseOnNext = skipOperations.decrementAndGet() <= 0 || haveCommandOutput || haveActiveBreakPoint

        val opCount = "- " + String.format(NUMBER_FORMAT, operations.size) + " "
        val options = if (pauseOnNext && !runTillEnd) {
            val nextSection = if (breakPoints.values.any { it.isNotEmpty() }) {
                "" + TERMINAL.ANSI_YELLOW + "next" + TERMINAL.ANSI_RESET + " = run till next, "
            } else {
                "" + TERMINAL.ANSI_YELLOW + "end" + TERMINAL.ANSI_RESET + " = run till end, "
            }

            "--> " +
                    TERMINAL.ANSI_YELLOW + "[enter]" + TERMINAL.ANSI_RESET + " = next section, " +
                    nextSection +
                    TERMINAL.ANSI_RED + "abort" + TERMINAL.ANSI_RESET + " = terminate, " +
                    TERMINAL.ANSI_YELLOW + "help" + TERMINAL.ANSI_RESET + " = options "
        } else ""

        sb.append(opCount)
        sb.append(options)
        sb.append("-".repeat(max(0, FULL_WIDTH - opCount.length - cleanString(options).length)))
        sb.append('\n')

        if (runTillEnd) {
            return sb.toString()
        } else if (!pauseOnNext) {
            return sb.toString()
        } else if (
            lastSourceMapElement != null &&
            sourceMapElement != null &&
            lastSourceMapElement!!.sourceFileByteOffset == sourceMapElement.sourceFileByteOffset &&
            lastSourceMapElement!!.lengthOfSourceRange == sourceMapElement.lengthOfSourceRange &&
            lastSourceMapElement!!.sourceIndex == sourceMapElement.sourceIndex
        ) {
            return sb.toString()
        } else if (lastSourceMapElement != null && sourceMapElement != null && sourceMapElement.sourceIndex < 0) {
            return sb.toString()
        }

        try {
            print(sb.toString())
            if (commandOutputs.isNotEmpty()) {
                commandOutputs.forEach(::println)
                commandOutputs.clear()
            }
            print(": ")

            val input = reader.readLine()

            when {
                input == null -> {
                    skipOperations.set(Integer.MAX_VALUE)
                    breakPoints.clear()
                }
                input.trim().toLowerCase() == "abort" -> {
                    throw ExceptionalHaltException(ExceptionalHaltReason.NONE)
                }
                input.trim().toLowerCase() == "next" -> {
                    if (breakPoints.values.any { it.isNotEmpty() }) skipOperations.set(Int.MAX_VALUE)
                    else {
                        commandOutputs.add("${TERMINAL.ANSI_CYAN}No breakpoints found${TERMINAL.ANSI_RESET}")
                        return nextOption(messageFrame, true)
                    }
                }
                input.trim().toLowerCase() == "end" -> {
                    runTillEnd = true
                }
                input.trim().toLowerCase().startsWith("break") -> {
                    parseBreakPointOption(input)
                    return nextOption(messageFrame, true)
                }
                input.trim().toLowerCase().startsWith("show") -> {
                    parseShowOption(input)
                    return nextOption(messageFrame, true)
                }
                input.trim().toLowerCase().startsWith("hide") -> {
                    parseHideOption(input)
                    return nextOption(messageFrame, true)
                }
                input.trim().toLowerCase() == "help" -> {
                    showHelp()
                    return nextOption(messageFrame, true)
                }
                input.isNotBlank() -> {
                    val x = Integer.parseInt(input)
                    skipOperations.set(max(x, 1))
                    lastSourceMapElement = null
                }
                else -> {
                    lastSourceMapElement = sourceMapElement
                }
            }

            return ""
        } catch (ex: NumberFormatException) {
            return nextOption(messageFrame, true)
        } catch (ex: IOException) {
            throw ExceptionalHaltException(ExceptionalHaltReason.NONE)
        }
    }

    companion object {
        private const val OP_CODES_WIDTH = 30
        private const val FULL_WIDTH = OP_CODES_WIDTH + 77
        private const val NUMBER_FORMAT = "0x%08x"
        private val STACK_HEADER = "" + TERMINAL.ANSI_GREEN + "-- Stack "

        private fun cleanString(input: String): String {
            return TERMINAL.values().fold(input) { output, t -> output.replace(t.toString(), "") }
        }
    }

    override fun traceExecution(messageFrame: MessageFrame, executeOperation: OperationTracer.ExecuteOperation?) {
        val finalOutput = nextOption(messageFrame)

        executeOperation?.execute()

        if (messageFrame.state != MessageFrame.State.CODE_EXECUTING) {
            skipOperations.set(0)
            operations.clear()
            runTillEnd = false
            println(finalOutput)
        }
    }
}
