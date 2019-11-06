package org.web3j.evm

import io.reactivex.Flowable
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.web3j.abi.datatypes.Address
import org.web3j.protocol.Web3jService
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.*
import org.web3j.protocol.websocket.events.Notification
import org.web3j.utils.Async
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class LocalWeb3jService(selfAddress: Address, operationTracer: OperationTracer) : Web3jService {
    private val localEthereum: LocalEthereum = LocalEthereum(selfAddress, operationTracer)

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
            "eth_blockNumber" -> throw UnsupportedOperationException(request.method)
            "eth_call" -> ethCall(request.params)
            "eth_coinbase" -> throw UnsupportedOperationException(request.method)
            "eth_compileLLL" -> throw UnsupportedOperationException(request.method)
            "eth_compileSerpent" -> throw UnsupportedOperationException(request.method)
            "eth_compileSolidity" -> throw UnsupportedOperationException(request.method)
            "eth_estimateGas" -> estimateGas(request.params)
            "eth_gasPrice" -> ethGasPrice()
            "eth_getBalance" -> throw UnsupportedOperationException(request.method)
            "eth_getBlockByHash" -> throw UnsupportedOperationException(request.method)
            "eth_getBlockByNumber" -> throw UnsupportedOperationException(request.method)
            "eth_getBlockTransactionCountByHash" -> throw UnsupportedOperationException(request.method)
            "eth_getBlockTransactionCountByNumber" -> throw UnsupportedOperationException(request.method)
            "eth_getCode" -> throw UnsupportedOperationException(request.method)
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
            "eth_syncing" -> throw UnsupportedOperationException(request.method)
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
            override fun getResult(): String {
                return Numeric.encodeQuantity(BigInteger.ONE)
            }
        }
    }

    private fun ethGetTransactionCount(params: List<Any>): Response<String> {
        val address = Address(params[0].toString())
        val defaultBlockParameterName = DefaultBlockParameterName.fromString(params[1].toString())
        val result = Numeric.encodeQuantity(localEthereum.getTransactionCount(address, defaultBlockParameterName))

        return object : EthGetTransactionCount() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethSendTransaction(params: List<Any>): Response<String> {
        val transaction = params[0] as Transaction
        val result = localEthereum.processTransaction(transaction)

        return object : EthSendTransaction() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethSendRawTransaction(params: List<Any>): Response<String> {
        val signedTransactionData = params[0] as String
        val result = localEthereum.processTransaction(signedTransactionData)

        return object : EthSendTransaction() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun ethGetTransactionReceipt(params: List<*>): Response<TransactionReceipt> {
        val transactionHash = params[0] as String
        val result = localEthereum.getTransactionReceipt(transactionHash)

        return object : EthGetTransactionReceipt() {
            override fun getResult(): TransactionReceipt? {
                return result
            }
        }
    }

    private fun ethCall(params: List<*>): Response<String> {
        val transaction = params[0] as Transaction
        val defaultBlockParameter = DefaultBlockParameter.valueOf(params[1].toString())
        val result = localEthereum.ethCall(transaction, defaultBlockParameter)

        return object : EthCall() {
            override fun getResult(): String {
                return result
            }
        }
    }

    private fun estimateGas(params: List<*>): Response<String> {
        val transaction = params[0] as Transaction
        val result = localEthereum.estimateGas(transaction)

        return object : EthEstimateGas() {
            override fun getResult(): String {
                return result
            }
        }
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
        private val LOG = LogManager.getLogger()
    }
}
