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
package org.web3j.evm.utils

import java.io.IOException
import java.io.Reader
import java.nio.CharBuffer

internal class NullReader : Reader() {
    @Volatile
    private var closed = false

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (closed) {
            throw IOException("Stream closed")
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        ensureOpen()
        return -1
    }

    @Throws(IOException::class)
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        ensureOpen()
        return when (len) {
            0 -> 0
            else -> -1
        }
    }

    @Throws(IOException::class)
    override fun read(target: CharBuffer): Int {
        ensureOpen()
        return when {
            target.hasRemaining() -> -1
            else -> 0
        }
    }

    @Throws(IOException::class)
    override fun ready(): Boolean {
        ensureOpen()
        return false
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        ensureOpen()
        return 0L
    }

    override fun close() {
        closed = true
    }
}
