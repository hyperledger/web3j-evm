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

import io.reactivex.Flowable
import org.hyperledger.besu.ethereum.core.Hash
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.slf4j.LoggerFactory
import org.web3j.abi.datatypes.Address
import org.web3j.protocol.Web3jService
import org.web3j.protocol.core.BatchRequest
import org.web3j.protocol.core.BatchResponse
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthCall
import org.web3j.protocol.core.methods.response.EthEstimateGas
import org.web3j.protocol.core.methods.response.EthGasPrice
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthGetBlockTransactionCountByHash
import org.web3j.protocol.core.methods.response.EthGetBlockTransactionCountByNumber
import org.web3j.protocol.core.methods.response.EthGetCode
import org.web3j.protocol.core.methods.response.EthGetTransactionCount
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.core.methods.response.EthSyncing
import org.web3j.protocol.core.methods.response.NetVersion
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.core.methods.response.Web3ClientVersion
import org.web3j.protocol.websocket.events.Notification
import org.web3j.utils.Async
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class EmbeddedWeb3jService(configuration: Configuration, operationTracer: OperationTracer) : Web3jService {
    private val embeddedEthereum: EmbeddedEthereum = EmbeddedEthereum(configuration, operationTracer)

    constructor(configuration: Configuration) : this(configuration, PassthroughTracer()) {
    }

    @Throws(IOException::class)
    override fun <T : Response<*>> send(request: Request<*, *>, responseType: Class<T>): T {
        return responseType.cast(send(request))
    }

    private fun send(request: Request<*, *>): Response<*> {
        LOG.trace("About to execute: " + request.method + " with params " + request.params)

        return when (request.method) {
            "admin_nodeInfo" -> throw UnsupportedOperationException(request.method)
            "db_getHex" -> throw UnsupportedOperationException(request.method)
            "db_getString" -> throw UnsupportedOperationException(request.method)
            "db_putHex" -> throw UnsupportedOperationException(request.method)
            "db_putString" -> throw UnsupportedOperationException(request.method)
            "eth_accounts" -> throw UnsupportedOperationException(request.method)
            "eth_blockNumber" -> ethBlockNumber()
            "eth_call" -> ethCall(request.params)
            "eth_coinbase" -> throw UnsupportedOperationException(request.method)
            "eth_compileLLL" -> throw UnsupportedOperationException(request.method)
            "eth_compileSerpent" -> throw UnsupportedOperationException(request.method)
            "eth_compileSolidity" -> throw UnsupportedOperationException(request.method)
            "eth_estimateGas" -> estimateGas(request.params)
            "eth_gasPrice" -> ethGasPrice()
            "eth_getBalance" -> ethGetBalance(request.params)
            "eth_getBlockByHash" -> ethBlockByHash(request.params)
            "eth_getBlockByNumber" -> ethBlockByNumber(request.params)
            "eth_getBlockTransactionCountByHash" -> ethGetBlockTransactionCountByHash(request.params)
            "eth_getBlockTransactionCountByNumber" -> ethGetBlockTransactionCountByNumber(request.params)
            "eth_getCode" -> ethGetCode(request.params)
            "eth_getCompilers" -> throw UnsupportedOperationException(request.method)
            "eth_getFilterChanges" -> throw UnsupportedOperationException(request.method)
            "eth_getFilterLogs" -> throw UnsupportedOperationException(request.method)
            "eth_getLogs" -> throw UnsupportedOperationException(request.method)
            "eth_getStorageAt" -> throw UnsupportedOperationException(request.method)
            "eth_getTransactionByBlockHashAndIndex" -> throw UnsupportedOperationException(request.method)
            "eth_getTransactionByBlockNumberAndIndex" -> throw UnsupportedOperationException(request.method)
            "eth_getTransactionByHash" -> throw UnsupportedOperationException(request.method)
            "eth_getTransactionCount" -> ethGetTransactionCount(request.params)
            "eth_getTransactionReceipt" -> ethGetTransactionReceipt(request.params)
            "eth_getUncleByBlockHashAndIndex" -> throw UnsupportedOperationException(request.method)
            "eth_getUncleByBlockNumberAndIndex" -> throw UnsupportedOperationException(request.method)
            "eth_getUncleCountByBlockHash" -> throw UnsupportedOperationException(request.method)
            "eth_getUncleCountByBlockNumber" -> throw UnsupportedOperationException(request.method)
            "eth_getWork" -> throw UnsupportedOperationException(request.method)
            "eth_hashrate" -> throw UnsupportedOperationException(request.method)
            "eth_mining" -> throw UnsupportedOperationException(request.method)
            "eth_newBlockFilter" -> throw UnsupportedOperationException(request.method)
            "eth_newFilter" -> throw UnsupportedOperationException(request.method)
            "eth_newPendingTransactionFilter" -> throw UnsupportedOperationException(request.method)
            "eth_protocolVersion" -> throw UnsupportedOperationException(request.method)
            "eth_sendRawTransaction" -> ethSendRawTransaction(request.params)
            "eth_sendTransaction" -> ethSendTransaction(request.params)
            "eth_sign" -> throw UnsupportedOperationException(request.method)
            "eth_submitHashrate" -> throw UnsupportedOperationException(request.method)
            "eth_submitWork" -> throw UnsupportedOperationException(request.method)
            "eth_syncing" -> ethSyncing()
            "eth_uninstallFilter" -> throw UnsupportedOperationException(request.method)
            "net_listening" -> throw UnsupportedOperationException(request.method)
            "net_peerCount" -> throw UnsupportedOperationException(request.method)
            "net_version" -> netVersion()
            "web3_clientVersion" -> web3ClientVersion()
            "web3_sha3" -> throw UnsupportedOperationException(request.method)
            else -> throw UnsupportedOperationException(request.method)
        }
    }

    private fun web3ClientVersion(): Response<String> {
        return object : Web3ClientVersion() {
            override fun getResult(): String {
                return Numeric.encodeQuantity(BigInteger.ONE)
            }
        }
    }

    private fun netVersion(): Response<String> {
        return object : NetVersion() {
            override fun getResult(): String {
                return Numeric.encodeQuantity(BigInteger.ONE)
            }
        }
    }

    private fun ethGasPrice(): Response<String> {
        return object : EthGasPrice() {
            override fun getResult(): String = Numeric.encodeQuantity(BigInteger.ONE)
        }
    }

    private fun ethGetTransactionCount(params: List<Any>): Response<String> {
        val address = Address(params[0].toString())
        val defaultBlockParameter = params[1].toString()
        val result =
            Numeric.encodeQuantity(embeddedEthereum.getTransactionCount(address, defaultBlockParameter))

        return object : EthGetTransactionCount() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethSendTransaction(params: List<Any>): Response<String> {
        val transaction = params[0] as Transaction
        val result = embeddedEthereum.processTransaction(transaction)

        return object : EthSendTransaction() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethSendRawTransaction(params: List<Any>): Response<String> {
        val signedTransactionData = params[0] as String
        val result = embeddedEthereum.processTransaction(signedTransactionData)

        return object : EthSendTransaction() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethGetTransactionReceipt(params: List<*>): Response<TransactionReceipt> {
        val transactionHash = params[0] as String
        val result = embeddedEthereum.getTransactionReceipt(transactionHash)

        return object : EthGetTransactionReceipt() {
            override fun getResult(): TransactionReceipt? {
                return result
            }
        }
    }

    private fun ethCall(params: List<*>): Response<String> {
        val transaction = params[0] as Transaction
        val defaultBlockParameter = params[1].toString()
        val result = embeddedEthereum.ethCall(transaction, defaultBlockParameter)

        return object : EthCall() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun estimateGas(params: List<*>): Response<String> {
        val transaction = params[0] as Transaction
        val result = embeddedEthereum.estimateGas(transaction)

        return object : EthEstimateGas() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethBlockNumber(): Response<String> {
        val result = embeddedEthereum.ethBlockNumber()

        return object : EthBlockNumber() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethGetBalance(params: List<*>): Response<String> {
        val address = params[0] as String
        val defaultBlockParameter = params[1] as String
        val result = embeddedEthereum.ethGetBalance(Address(address), defaultBlockParameter)

        return object : EthGetBalance() {
            override fun getResult(): String? {
                return result
            }
        }
    }

    private fun ethBlockByHash(params: List<*>): Response<EthBlock.Block> {
        val hash = params[0] as String
        val fullTransactionObjects = params[1] as Boolean
        val result = embeddedEthereum.ethBlockByHash(hash, fullTransactionObjects)

        return object : EthBlock() {
            override fun getResult(): Block? {
                return result
            }
        }
    }

    private fun ethBlockByNumber(params: List<*>): Response<EthBlock.Block> {
        val blockNumber = params[0] as String
        val fullTransactionObjects = params[1] as Boolean
        val result = embeddedEthereum.ethBlockByNumber(blockNumber, fullTransactionObjects)

        return object : EthBlock() {
            override fun getResult(): Block? {
                return result
            }
        }
    }

    private fun ethGetBlockTransactionCountByHash(params: List<*>): Response<String> {
        val blockHash = params[0] as String
        val result = embeddedEthereum.ethGetBlockTransactionCountByHash(Hash.fromHexString(blockHash))
        return object : EthGetBlockTransactionCountByHash() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethGetCode(params: List<*>): Response<String> {
        val address = params[0] as String
        val defaultBlockParameter = params[1] as String
        val result = embeddedEthereum.ethGetCode(Address(address), defaultBlockParameter)

        return object : EthGetCode() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethGetBlockTransactionCountByNumber(params: List<*>): Response<String> {
        val blockNumber = Numeric.decodeQuantity(params[0] as String)
        val result = embeddedEthereum.ethGetBlockTransactionCountByNumber(blockNumber.toLong())
        return object : EthGetBlockTransactionCountByNumber() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethSyncing(): Response<EthSyncing.Result> {
        return object : EthSyncing() {
            override fun getResult(): Result {
                return Result().apply { isSyncing = true }
            }
        }
    }

    override fun sendBatch(p0: BatchRequest?): BatchResponse {
        throw UnsupportedOperationException("Batch send is not supported")
    }

    override fun sendBatchAsync(p0: BatchRequest?): CompletableFuture<BatchResponse> {
        throw UnsupportedOperationException("Batch send is not supported")
    }

    override fun <T : Response<*>> sendAsync(request: Request<*, *>, responseType: Class<T>): CompletableFuture<T> {
        return Async.run { send(request, responseType) }
    }

    override fun <T : Notification<*>> subscribe(
        request: Request<*, *>,
        unsubscribeMethod: String,
        responseType: Class<T>
    ): Flowable<T> {
        throw UnsupportedOperationException(
            String.format(
                "Service %s does not support subscriptions",
                this.javaClass.simpleName
            )
        )
    }

    @Throws(IOException::class)
    override fun close() {
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EmbeddedWeb3jService::class.java)
    }
}
