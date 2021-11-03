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

import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.crypto.KeyPair
import org.hyperledger.besu.crypto.SignatureAlgorithm
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.web3j.abi.datatypes.Address
import java.net.URL

/**
 *
 */
class Configuration @JvmOverloads constructor(
    val selfAddress: Address,
    val ethFund: Long,
    val genesisFileUrl: URL? = null
) {
    private val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithmFactory.getInstance()

    // TODO configurable
    // Test Account 1 (address 0xfe3b557e8fb62b89f4916b721be55ceb828dbd73)
    val keyPair: KeyPair =
        signatureAlgorithm.createKeyPair(
            // Default test account?
            signatureAlgorithm.createPrivateKey(
                Bytes32.fromHexString("0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
            )
        )
}
