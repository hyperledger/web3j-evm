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

import java.io.BufferedReader
import java.io.FileReader
import java.util.SortedMap
import java.util.TreeMap
import org.web3j.evm.entity.source.SourceLine

object FileUtils {
    fun loadFile(path: String): SortedMap<Int, SourceLine> {
        return BufferedReader(FileReader(path)).use { reader ->
            reader.lineSequence()
                .withIndex()
                .map { indexedLine -> Pair(indexedLine.index + 1, SourceLine(indexedLine.value)) }
                .toMap(TreeMap())
        }
    }
}
