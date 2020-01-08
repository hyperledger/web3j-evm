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
import java.util.Optional
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.max
import kotlin.collections.HashMap
import kotlin.collections.ArrayList
import org.hyperledger.besu.ethereum.core.Gas
import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.vm.ehalt.ExceptionalHaltException
import com.beust.klaxon.Klaxon

internal data class ContractMeta(val contracts: Map<String, Map<String, String>>, val sourceList: List<String>)

internal data class SourceMapElement(val sourceFileByteOffset: Int = 0, val lengthOfSourceRange: Int = 0, val sourceIndex: Int = 0, val jumpType: String = "")

internal data class ContractMapping(val idxSource: Map<Int, Pair<String, SortedMap<Int, String>>>, val pcSourceMappings: Map<Int, SourceMapElement>)

open class ConsoleDebugTracer(protected val metaFile: File?, private val reader: BufferedReader) : OperationTracer {
    private val operations = ArrayList<String>()
    private val skipOperations = AtomicInteger()

    private var lastSourceFile: String? = null
    private var lastSourceSubsection = emptyList<String>()
    private var lastSourceMapElement: SourceMapElement? = null

    internal val byteCodeContractMapping = HashMap<Pair<String, Boolean>, ContractMapping>()

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
            file.isFile && file.name.endsWith(".json") -> {
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

    private fun loadFile(path: String): SortedMap<Int, String> {
        return BufferedReader(FileReader(path)).use { reader ->
            reader.lineSequence()
                .withIndex()
                .map { indexedLine -> Pair(indexedLine.index + 1, indexedLine.value) }
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
            .map { Pair(it.index, Pair(it.value, loadFile(it.value))) }
            .toMap()

        val sourceMapElements = decompressSourceMap(srcmap)
        val opCodeGroups = opCodeGroups(bytecode)
        val pcSourceMappings = pcSourceMap(sourceMapElements, opCodeGroups)

        return ContractMapping(idxSource, pcSourceMappings)
    }

    private fun sourceSize(source: SortedMap<Int, String>) = source.values
        // Doing +1 to include newline
        .map { it.length + 1 }
        .sum()

    private fun sourceRange(source: SortedMap<Int, String>, from: Int, to: Int): SortedMap<Int, String> {
        return source.entries.fold(Pair(0, TreeMap<Int, String>())) { acc, entry ->
            val subsection = entry.value
                .withIndex()
                .filter { acc.first + it.index in from..to }
                .map { it.value }
                .joinToString(separator = "")

            val accMin = acc.first
            val accMax = acc.first + entry.value.length
            val overlap = accMin in from..to || accMax in from..to || from in accMin..accMax || to in accMin..accMax

            if (overlap) acc.second[entry.key] = subsection

            return@fold Pair(acc.first + entry.value.length + 1, acc.second)
        }.second
    }

    private fun findSourceNear(idxSource: Map<Int, Pair<String, SortedMap<Int, String>>>, sourceMapElement: SourceMapElement): String {
        val source = idxSource[sourceMapElement.sourceIndex]?.second ?: return ""
        val sourceLength = sourceSize(source)

        val from = sourceMapElement.sourceFileByteOffset
        val to = from + sourceMapElement.lengthOfSourceRange

        val head = sourceRange(source, 0, from - 1)

        val body = sourceRange(source, from, to - 1).map {
            Pair(it.key, "${TERMINAL.ANSI_YELLOW}${it.value}${TERMINAL.ANSI_RESET}")
        }.toMap(TreeMap())

        val tail = sourceRange(source, to, sourceLength)

        val subsection = TreeMap<Int, String>()

        head.entries.reversed().take(2).forEach { (lineNumber, newLine) ->
            subsection[lineNumber] = newLine
        }

        body.forEach { (lineNumber, newLine) ->
            subsection.compute(lineNumber) { _, line -> if (line == null) newLine else line + newLine }
        }

        tail.entries.take(2).forEach { (lineNumber, newLine) ->
            subsection.compute(lineNumber) { _, line -> if (line == null) newLine else line + newLine }
        }

        return subsection.entries.joinToString(separator = "\n") {
            val lineNumber = ("%" + subsection.lastKey().toString().length + "s").format(it.key.toString())
            val lineNumberSpacing = "%-4s".format(lineNumber)
            return@joinToString "$lineNumberSpacing ${it.value}"
        }
    }

    internal fun sourceAtMessageFrame(messageFrame: MessageFrame): Pair<SourceMapElement?, Pair<String?, List<String>>> {
        val pc = messageFrame.pc
        val contractCreation = MessageFrame.Type.CONTRACT_CREATION == messageFrame.type
        val bytecode = messageFrame.code.bytes.toUnprefixedString()
        val (idxSource, pcSourceMappings) = byteCodeContractMapping.getOrPut(Pair(bytecode, contractCreation)) {
            loadContractMapping(
                contractCreation,
                bytecode
            )
        }

        val selection = findSourceNear(idxSource, pcSourceMappings[pc] ?: return Pair(pcSourceMappings[pc], Pair(lastSourceFile, lastSourceSubsection)))

        if (selection.isNotBlank()) {
            lastSourceFile = idxSource[pcSourceMappings[pc]?.sourceIndex]?.first
            lastSourceSubsection = selection.split("\n").toList().take(10)
        }

        return Pair(pcSourceMappings[pc], if (lastSourceSubsection.isEmpty()) Pair(null, listOf("No source available")) else Pair(lastSourceFile, lastSourceSubsection))
    }

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

            if (haveActiveStackOutput) {
                sb.append(" ".repeat((cleanString(operation).length..OP_CODES_WIDTH).count() - 1))
                sb.append(STACK_HEADER)
                sb.append("-".repeat(max(0, FULL_WIDTH - OP_CODES_WIDTH - cleanString(STACK_HEADER).length)))
                sb.append(TERMINAL.ANSI_RESET)
            }

            if (i + 1 == operations.size) {
                sb.append(" ".repeat((cleanString(operation).length..OP_CODES_WIDTH).count() - 1))
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

        // Source code section start
        val (sourceMapElement, sourceDetails) = sourceAtMessageFrame(messageFrame)
        val (sourceFile, sourceSection) = sourceDetails

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
                    append(" - ")
                    if (sourceFile == null)
                        append("Unknown source file")
                    else
                        append(sourceFile)
                    append(" ")
                }

                sb.append(subText)
                sb.append("-".repeat(FULL_WIDTH - subText.length))
            } else {
                sb.append("-".repeat(FULL_WIDTH))
            }

            sb.append('\n')

            sourceSection
                .dropWhile { it.isBlank() }
                .reversed()
                .dropWhile { it.isBlank() }
                .reversed()
                .forEach {
                    sb.append(it)
                    sb.append('\n')
                }
            sb.append(TERMINAL.ANSI_RESET)
        }
        // Source code section end

        val opCount = "- " + String.format(NUMBER_FORMAT, operations.size) + " "
        val options = if (pauseOnNext) {
            "--> " +
                    TERMINAL.ANSI_YELLOW + "[enter]" + TERMINAL.ANSI_RESET + " = next section, " +
                    TERMINAL.ANSI_YELLOW + "[number]" + TERMINAL.ANSI_RESET + " = next X ops, " +
                    TERMINAL.ANSI_YELLOW + "end" + TERMINAL.ANSI_RESET + " = run till end, " +
                    TERMINAL.ANSI_RED + "abort" + TERMINAL.ANSI_RESET + " = terminate "
        } else ""

        sb.append(opCount)
        sb.append(options)
        sb.append("-".repeat(max(0, FULL_WIDTH - opCount.length - cleanString(options).length)))
        sb.append('\n')

        val finalOutput = nextOption(sourceMapElement, sb.toString())

        executeOperation.execute()

        if (messageFrame.state != MessageFrame.State.CODE_EXECUTING) {
            skipOperations.set(0)
            operations.clear()
            println(finalOutput)
        }
    }

    @Throws(ExceptionalHaltException::class)
    private fun nextOption(currentSourceMapElement: SourceMapElement?, output: String): String {
        if (skipOperations.decrementAndGet() > 0) {
            return output
        }

        if (
            lastSourceMapElement != null &&
            currentSourceMapElement != null &&
            lastSourceMapElement!!.sourceFileByteOffset == currentSourceMapElement.sourceFileByteOffset &&
            lastSourceMapElement!!.lengthOfSourceRange == currentSourceMapElement.lengthOfSourceRange &&
            lastSourceMapElement!!.sourceIndex == currentSourceMapElement.sourceIndex
        ) {
            return output
        } else if (currentSourceMapElement != null && currentSourceMapElement.sourceIndex < 0) {
            return output
        }

        try {
            print("$output: ")

            val input = reader.readLine()

            when {
                input == null -> {
                    skipOperations.set(Integer.MAX_VALUE)
                }
                input.trim().toLowerCase() == "abort" -> {
                    val enumSet = EnumSet.allOf(ExceptionalHaltReason::class.java)
                    enumSet.add(ExceptionalHaltReason.NONE)
                    throw ExceptionalHaltException(enumSet)
                }
                input.trim().toLowerCase() == "end" -> {
                    skipOperations.set(Integer.MAX_VALUE)
                }
                input.isNotBlank() -> {
                    val x = Integer.parseInt(input)
                    skipOperations.set(max(x, 1))
                }
                else -> {
                    lastSourceMapElement = currentSourceMapElement
                }
            }

            return ""
        } catch (ex: NumberFormatException) {
            return nextOption(currentSourceMapElement, output)
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
        private val STACK_HEADER = "" + TERMINAL.ANSI_GREEN + "-- Stack "

        private fun cleanString(input: String): String {
            return TERMINAL.values().fold(input) { output, t -> output.replace(t.toString(), "") }
        }
    }
}
