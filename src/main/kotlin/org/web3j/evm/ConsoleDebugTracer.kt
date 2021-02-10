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
import java.io.FileReader
import java.util.SortedMap
import java.util.TreeMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.max
import kotlin.collections.HashMap
import kotlin.collections.ArrayList
import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import com.beust.klaxon.Klaxon

private data class ContractMeta(val contracts: Map<String, Map<String, String>>, val sourceList: List<String>)

private data class ContractMapping(val idxSource: Map<Int, SourceFile>, val pcSourceMappings: Map<Int, SourceMapElement>)

data class SourceMapElement(val sourceFileByteOffset: Int = 0, val lengthOfSourceRange: Int = 0, val sourceIndex: Int = 0, val jumpType: String = "")

data class SourceLine(val line: String, val selected: Boolean = false, val offset: Int = 0)

data class SourceFile(val filePath: String? = null, val sourceContent: SortedMap<Int, SourceLine> = Collections.emptySortedMap())

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

    private fun maybeContractMap(bytecode: String, contractMeta: ContractMeta): Map<String, String> {
        return contractMeta
            .contracts
            .values
            .firstOrNull { contractProps ->
                contractProps.filter { propEntry ->
                    propEntry.key.startsWith("bin")
                }.values.any { v ->
                    bytecode.startsWith(v)
                }
            } ?: emptyMap()
    }

    private fun loadContractMeta(file: File): List<ContractMeta> {
        return when {
            file.isFile && file.name.endsWith(".json") && !file.name.endsWith("meta.json") -> {
                listOf(Klaxon().parse<ContractMeta>(file) ?: ContractMeta(emptyMap(), emptyList()))
            }
            file.isDirectory -> {
                file.listFiles()
                    ?.map { loadContractMeta(it) }
                    ?.flatten() ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun decompressSourceMap(sourceMap: String): List<SourceMapElement> {
        fun foldOp(elements: MutableList<SourceMapElement>, sourceMapPart: String): MutableList<SourceMapElement> {
            val prevSourceMapElement = if (elements.isNotEmpty()) elements.last() else SourceMapElement()
            val parts = sourceMapPart.split(":")
            val s = if (parts.size > 0 && parts[0].isNotBlank()) parts[0].toInt() else prevSourceMapElement.sourceFileByteOffset
            val l = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toInt() else prevSourceMapElement.lengthOfSourceRange
            val f = if (parts.size > 2 && parts[2].isNotBlank()) parts[2].toInt() else prevSourceMapElement.sourceIndex
            val j = if (parts.size > 3 && parts[3].isNotBlank()) parts[3] else prevSourceMapElement.jumpType
            return elements.apply { add(SourceMapElement(s, l, f, j)) }
        }

        return sourceMap.split(";").fold(ArrayList<SourceMapElement>(), ::foldOp)
    }

    private fun opCodeGroups(bytecode: String): List<String> {
        return bytecode
            .split("(?<=\\G.{2})".toRegex())
            .foldIndexed(Pair(0, ArrayList<String>()), { index, state, opCode ->
                if (opCode.isBlank()) return@foldIndexed state

                val acc = state.first
                val groups = state.second

                if (index >= acc) {
                    Pair(acc + opCodeToOpSize(opCode), groups.apply { add(opCode) })
                } else {
                    Pair(acc, groups.apply { set(size - 1, last() + opCode) })
                }
            }).second
    }

    private fun opCodeToOpSize(opCode: String): Int {
        return when (opCode.toUpperCase()) {
            "60" -> 2
            "61" -> 3
            "62" -> 4
            "63" -> 5
            "64" -> 6
            "65" -> 7
            "66" -> 8
            "67" -> 9
            "68" -> 10
            "69" -> 11
            "6A" -> 12
            "6B" -> 13
            "6C" -> 14
            "6D" -> 15
            "6E" -> 16
            "6F" -> 17
            "70" -> 18
            "71" -> 19
            "72" -> 20
            "73" -> 21
            "74" -> 22
            "75" -> 23
            "76" -> 24
            "77" -> 25
            "78" -> 26
            "79" -> 27
            "7A" -> 28
            "7B" -> 29
            "7C" -> 30
            "7D" -> 31
            "7E" -> 32
            "7F" -> 33
            else -> 1
        }
    }

    private fun loadFile(path: String): SortedMap<Int, SourceLine> {
        return BufferedReader(FileReader(path)).use { reader ->
            reader.lineSequence()
                .withIndex()
                .map { indexedLine -> Pair(indexedLine.index + 1, SourceLine(indexedLine.value)) }
                .toMap(TreeMap())
        }
    }

    private fun pcSourceMap(sourceMapElements: List<SourceMapElement>, opCodeGroups: List<String>): Map<Int, SourceMapElement> {
        val mappings = HashMap<Int, SourceMapElement>()

        var location = 0

        for (i in 0 until min(opCodeGroups.size, sourceMapElements.size)) {
            mappings[location] = sourceMapElements[i]
            location += (opCodeGroups[i].length / 2)
        }

        return mappings
    }

    private fun loadContractMapping(contractCreation: Boolean, bytecode: String): ContractMapping {
        if (metaFile == null || !metaFile.exists())
            return ContractMapping(emptyMap(), emptyMap())

        val contractMetas = loadContractMeta(metaFile)

        val (contract, sourceList) = contractMetas
            .map { Pair(maybeContractMap(bytecode, it), it.sourceList) }
            .firstOrNull { it.first.isNotEmpty() } ?: return ContractMapping(emptyMap(), emptyMap())

        val srcmap = if (contractCreation) {
            contract["srcmap"]
        } else {
            contract["srcmap-runtime"]
        } ?: return ContractMapping(emptyMap(), emptyMap())

        val idxSource = sourceList
            .withIndex()
            .map { Pair(it.index, SourceFile(it.value, loadFile(it.value))) }
            .toMap()

        val sourceMapElements = decompressSourceMap(srcmap)
        val opCodeGroups = opCodeGroups(bytecode)
        val pcSourceMappings = pcSourceMap(sourceMapElements, opCodeGroups)

        return ContractMapping(idxSource, pcSourceMappings)
    }

    private fun sourceSize(sourceContent: SortedMap<Int, SourceLine>) = sourceContent.values
        // Doing +1 to include newline
        .map { it.line.length + 1 }
        .sum()

    private fun sourceRange(sourceContent: SortedMap<Int, SourceLine>, from: Int, to: Int): SortedMap<Int, String> {
        return sourceContent.entries.fold(Pair(0, TreeMap<Int, String>())) { acc, entry ->
            val subsection = entry
                .value
                .line
                .withIndex()
                .filter { acc.first + it.index in from..to }
                .map { it.value }
                .joinToString(separator = "")

            val accMin = acc.first
            val accMax = acc.first + entry.value.line.length
            val overlap = accMin in from..to || accMax in from..to || from in accMin..accMax || to in accMin..accMax

            if (overlap) acc.second[entry.key] = subsection

            return@fold Pair(acc.first + entry.value.line.length + 1, acc.second)
        }.second
    }

    private fun findSourceNear(idxSource: Map<Int, SourceFile>, sourceMapElement: SourceMapElement): SourceFile {
        val sourceFile = idxSource[sourceMapElement.sourceIndex] ?: return SourceFile()
        val sourceContent = sourceFile.sourceContent
        val sourceLength = sourceSize(sourceContent)

        val from = sourceMapElement.sourceFileByteOffset
        val to = from + sourceMapElement.lengthOfSourceRange

        val head = sourceRange(sourceContent, 0, from - 1)

        val body = sourceRange(sourceContent, from, to - 1).map {
            Pair(it.key, "${TERMINAL.ANSI_YELLOW}${it.value}${TERMINAL.ANSI_RESET}")
        }.toMap(TreeMap())

        val tail = sourceRange(sourceContent, to, sourceLength)

        val subsection = TreeMap<Int, SourceLine>()

        head.entries.reversed().take(2).forEach { (lineNumber, newLine) ->
            subsection[lineNumber] = SourceLine(newLine)
        }

        body.forEach { (lineNumber, newLine) ->
            subsection.compute(lineNumber) { _, sourceLine ->
                if (sourceLine == null) {
                    SourceLine(newLine, true, 0)
                } else {
                    val offset = if (sourceLine.selected) sourceLine.offset else sourceLine.line.length

                    SourceLine(sourceLine.line + newLine, true, offset)
                }
            }
        }

        tail.entries.take(2).forEach { (lineNumber, newLine) ->
            subsection.compute(lineNumber) { _, sourceLine ->
                if (sourceLine == null)
                    SourceLine(newLine)
                else
                    SourceLine(sourceLine.line + newLine, sourceLine.selected, sourceLine.offset)
            }
        }

        return SourceFile(sourceFile.filePath, subsection)
    }

    protected fun sourceAtMessageFrame(messageFrame: MessageFrame): Pair<SourceMapElement?, SourceFile> {
        val pc = messageFrame.pc
        val contractCreation = MessageFrame.Type.CONTRACT_CREATION == messageFrame.type
        val bytecode = toUnprefixedString(messageFrame.code.bytes)
        val (idxSource, pcSourceMappings) = byteCodeContractMapping.getOrPut(Pair(bytecode, contractCreation)) {
            loadContractMapping(
                contractCreation,
                bytecode
            )
        }

        val sourceFileSelection = findSourceNear(idxSource, pcSourceMappings[pc] ?: return Pair(pcSourceMappings[pc], lastSourceFile))

        if (sourceFileSelection.sourceContent.isNotEmpty()) {
            lastSourceFile = sourceFileSelection
        }

        val outputSourceFile = if (lastSourceFile.sourceContent.isEmpty()) {
            SourceFile(sourceContent = sortedMapOf(0 to SourceLine("No source available")))
        } else lastSourceFile

        return Pair(pcSourceMappings[pc], outputSourceFile)
    }

    private fun toUnprefixedString(bytes: org.apache.tuweni.bytes.Bytes): String {
        val prefixedHex = bytes.toString()
        return if (prefixedHex.startsWith("0x")) prefixedHex.substring(2) else prefixedHex
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
