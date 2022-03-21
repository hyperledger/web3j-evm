/*
 * Copyright 2022 Web3 Labs Ltd.
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
package org.web3j.evm.utils

import com.beust.klaxon.Klaxon
import java.io.File
import java.util.SortedMap
import java.util.TreeMap
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.min
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.web3j.evm.entity.ContractMapping
import org.web3j.evm.entity.ContractMeta
import org.web3j.evm.entity.source.SourceFile
import org.web3j.evm.entity.source.SourceLine
import org.web3j.evm.entity.source.SourceMapElement

object SourceMappingUtils {

    fun loadContractMapping(metaFile: File?, contractCreation: Boolean, bytecode: String): ContractMapping {
        if (metaFile == null || !metaFile.exists()) {
            return ContractMapping(emptyMap(), emptyMap())
        }

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
            .map { Pair(it.index, SourceFile(it.value, FileUtils.loadFile(it.value))) }
            .toMap()

        val sourceMapElements = decompressSourceMap(srcmap)
        val opCodeGroups = opCodeGroups(bytecode)
        val pcSourceMappings = pcSourceMap(sourceMapElements, opCodeGroups)

        return ContractMapping(idxSource, pcSourceMappings)
    }

    private fun opCodeGroups(bytecode: String): List<String> {
        return bytecode
            .split("(?<=\\G.{2})".toRegex())
            .foldIndexed(Pair(0, ArrayList<String>())) { index, state, opCode ->
                if (opCode.isBlank()) return@foldIndexed state

                val acc = state.first
                val groups = state.second

                if (index >= acc) {
                    Pair(acc + opCodeToOpSize(opCode), groups.apply { add(opCode) })
                } else {
                    Pair(acc, groups.apply { set(size - 1, last() + opCode) })
                }
            }.second
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

    /**
     *
     * Loads the metadata files from a directory into a map as keys
     * In the value section it extracts the bin, bin-runtime, srcmap, srcmap-runtime
     *
     */
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

    private fun pcSourceMap(
        sourceMapElements: List<SourceMapElement>,
        opCodeGroups: List<String>
    ): Map<Int, SourceMapElement> {
        val mappings = HashMap<Int, SourceMapElement>()
        var location = 0

        for (i in 0 until min(opCodeGroups.size, sourceMapElements.size)) {
            mappings[location] = sourceMapElements[i]
            location += (opCodeGroups[i].length / 2)
        }
        return mappings
    }

    /**
     * Breaks down the src map from a contract's metadata file.
     * The sequences are separated by ; e.g 122:136; 8:9:-1;
     * If the current sequence is empty 122:136;; then it will be assigned the value of the previous one
     * Each section is then a SourceMapElement
     */

    private fun decompressSourceMap(sourceMap: String): List<SourceMapElement> {
        fun foldOp(elements: MutableList<SourceMapElement>, sourceMapPart: String): MutableList<SourceMapElement> {
            val prevSourceMapElement = if (elements.isNotEmpty()) elements.last() else SourceMapElement()
            val parts = sourceMapPart.split(":")

            val sourceFileByteOffset =
                if (parts.isNotEmpty() && parts[0].isNotBlank()) parts[0].toInt() else prevSourceMapElement.sourceFileByteOffset
            val lengthOfSourceRange =
                if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toInt() else prevSourceMapElement.lengthOfSourceRange
            val sourceIndex =
                if (parts.size > 2 && parts[2].isNotBlank()) parts[2].toInt() else prevSourceMapElement.sourceIndex
            val jumpType = if (parts.size > 3 && parts[3].isNotBlank()) parts[3] else prevSourceMapElement.jumpType
            return elements.apply {
                add(
                    SourceMapElement(
                        sourceFileByteOffset,
                        lengthOfSourceRange,
                        sourceIndex,
                        jumpType
                    )
                )
            }
        }

        return sourceMap.split(";").fold(java.util.ArrayList(), ::foldOp)
    }

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

    fun findSourceNear(
        idxSource: Map<Int, SourceFile>,
        sourceMapElement: SourceMapElement,
        bodyTransform: (key: Int, value: String) -> Pair<Int, String>
    ): SourceFile {
        val sourceFile = idxSource[sourceMapElement.sourceIndex] ?: return SourceFile()
        val sourceContent = sourceFile.sourceContent
        val sourceLength = sourceSize(sourceContent)

        val from = sourceMapElement.sourceFileByteOffset
        val to = from + sourceMapElement.lengthOfSourceRange

        val head = sourceRange(sourceContent, 0, from - 1)

        val body = sourceRange(sourceContent, from, to - 1).map {
            bodyTransform(it.key, it.value)
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

    fun sourceAtMessageFrame(
        messageFrame: MessageFrame,
        metaFile: File?,
        lastSourceFile: SourceFile,
        byteCodeContractMapping: java.util.HashMap<Pair<String, Boolean>, ContractMapping>,
        sourceFileBodyTransform: (key: Int, value: String) -> Pair<Int, String>
    ): Pair<SourceMapElement?, SourceFile> {
        val pc = messageFrame.pc
        val contractCreation = MessageFrame.Type.CONTRACT_CREATION == messageFrame.type
        val bytecode = StringUtils.toUnprefixedString(messageFrame.code.bytes)

        var sourceFile = lastSourceFile

        val (idxSource, pcSourceMappings) = byteCodeContractMapping.getOrPut(Pair(bytecode, contractCreation)) {
            loadContractMapping(
                metaFile,
                contractCreation,
                bytecode
            )
        }

        val sourceFileSelection = findSourceNear(
            idxSource,
            pcSourceMappings[pc] ?: return Pair(pcSourceMappings[pc], sourceFile),
            sourceFileBodyTransform
        )

        if (sourceFileSelection.sourceContent.isNotEmpty()) {
            sourceFile = sourceFileSelection
        }

        val outputSourceFile = if (sourceFile.sourceContent.isEmpty()) {
            SourceFile(sourceContent = sortedMapOf(0 to SourceLine("No source available")))
        } else sourceFile

        return Pair(pcSourceMappings[pc], outputSourceFile)
    }
}
