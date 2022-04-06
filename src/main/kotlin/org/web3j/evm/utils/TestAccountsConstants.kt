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

import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.crypto.SignatureAlgorithm
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory

object TestAccountsConstants {
    private val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithmFactory.getInstance()

    val TEST_ACCOUNTS = mapOf(
        "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73".toLowerCase()
                to newKeyPair("0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"),
        "0x627306090abaB3A6e1400e9345bC60c78a8BEf57".toLowerCase()
                to newKeyPair("0xc87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3"),
        "0xf17f52151EbEF6C7334FAD080c5704D77216b732".toLowerCase()
                to newKeyPair("0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f")
    )

    private fun newKeyPair(privKey: String) = signatureAlgorithm.createKeyPair(
        signatureAlgorithm.createPrivateKey(
            Bytes32.fromHexString(privKey)
        )
    )
}
