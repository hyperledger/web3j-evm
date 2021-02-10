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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.io.Resources
import org.hyperledger.besu.config.GenesisConfigFile
import org.hyperledger.besu.config.JsonUtil
import org.hyperledger.besu.crypto.SECP256K1
import org.hyperledger.besu.ethereum.ProtocolContext
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameter
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionCompleteResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionHashResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptLogResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptRootResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptStatusResult
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries
import org.hyperledger.besu.ethereum.api.query.TransactionReceiptWithMetadata
import org.hyperledger.besu.ethereum.chain.GenesisState
import org.hyperledger.besu.ethereum.core.Address
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.BlockBody
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder
import org.hyperledger.besu.ethereum.core.Hash
import org.hyperledger.besu.ethereum.core.LogsBloomFilter
import org.hyperledger.besu.ethereum.core.MutableWorldState
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.core.Wei
import org.hyperledger.besu.ethereum.mainnet.BodyValidation
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStateKeyValueStorage
import org.hyperledger.besu.ethereum.vm.BlockHashLookup
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt256
import org.slf4j.LoggerFactory
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStatePreimageKeyValueStorage
import org.hyperledger.besu.ethereum.worldstate.DefaultWorldStateArchive
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult
import java.util.Optional
import kotlin.collections.HashMap

class EmbeddedEthereum(configuration: Configuration, private val operationTracer: OperationTracer) {

    // Dummy signature for transactions to not fail being processed.
    private val FAKE_SIGNATURE =
        SECP256K1.Signature.create(SECP256K1.HALF_CURVE_ORDER, SECP256K1.HALF_CURVE_ORDER, 0.toByte())

    private val genesisState: GenesisState
    private val blockchainQueries: BlockchainQueries
    private val miningBeneficiary: Address
    private val blockResultFactory: BlockResultFactory
    private val protocolContext: ProtocolContext
    private val protocolSchedule: ProtocolSchedule

    init {
        val genesisConfig = if (configuration.genesisFileUrl == null) {
            val jsonString = Resources.toString(GenesisConfigFile::class.java.getResource("/dev.json"), UTF_8)
            val configRoot = JsonUtil.normalizeKeys(JsonUtil.objectNodeFromString(jsonString, false))
            val objectNode = ObjectNode(JsonNodeFactory(false))
            objectNode.put("balance", "${Wei.fromEth(configuration.ethFund)}")
            JsonUtil.getObjectNode(configRoot, "alloc").get().set<JsonNode>(configuration.selfAddress.value, objectNode as JsonNode)
            GenesisConfigFile.fromConfig(configRoot)
        } else
            GenesisConfigFile.fromConfig(
                Resources.toString(
                    configuration.genesisFileUrl,
                    UTF_8
                )
            )

        protocolSchedule = MainnetProtocolSchedule.fromConfig(genesisConfig.configOptions)
        genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule)

        val storageProvider = KeyValueStorageProvider({ InMemoryKeyValueStorage() },
            InMemoryKeyValueStorage(),
            false
        )
        val metricsSystem = NoOpMetricsSystem()

        val hashValueStore: Map<Bytes, ByteArray> = HashMap()
        val stateStorage: InMemoryKeyValueStorage = TestInMemoryStorage(hashValueStore)
        val worldStateStorage: WorldStateStorage = WorldStateKeyValueStorage(stateStorage)
        val worldStateArchive: WorldStateArchive = DefaultWorldStateArchive(
            worldStateStorage, WorldStatePreimageKeyValueStorage(InMemoryKeyValueStorage())
        )
        protocolContext = ProtocolContext.init(storageProvider, worldStateArchive, genesisState, protocolSchedule, metricsSystem, { _, _ -> }, 6L)

        blockchainQueries =
            BlockchainQueries(protocolContext.blockchain, protocolContext.worldStateArchive)

        miningBeneficiary = Address.ZERO

        blockResultFactory = BlockResultFactory()
    }

    fun processTransaction(web3jTransaction: org.web3j.protocol.core.methods.request.Transaction): String {
        val transaction = Transaction.builder()
            .gasLimit(transactionGasLimitOrDefault(web3jTransaction))
            .gasPrice(Wei.of(hexToULong(web3jTransaction.gasPrice)))
            .nonce(hexToULong(web3jTransaction.nonce))
            .sender(Address.fromHexString(web3jTransaction.from))
            .to(Address.fromHexString(web3jTransaction.to))
            .payload(Bytes.fromHexString(web3jTransaction.data))
            .value(Wei.of(UInt256.fromHexString(web3jTransaction.value ?: "0x0")))
            .signature(FAKE_SIGNATURE)
            .build()

        return processTransaction(transaction)
    }

    fun processTransaction(signedTransactionData: String): String {
        val transaction = Transaction.readFrom(RLP.input(Bytes.fromHexString(signedTransactionData)))
        return processTransaction(transaction)
    }

    private fun processTransaction(transaction: Transaction): String {
        val worldState =
            protocolContext.worldStateArchive.getMutable(protocolContext.blockchain.chainHeadHeader.stateRoot).get()
        val worldStateUpdater = worldState.updater()
        LOG.info("Starting with root: {}", worldState.rootHash())

        val blockheaderBuilder = BlockHeaderBuilder.create()
            .coinbase(miningBeneficiary)
            .difficulty(protocolContext.blockchain.chainHeadHeader.difficulty.plus(100000))
            .nonce(protocolContext.blockchain.chainHeadHeader.nonce + 1)
            .gasLimit(protocolContext.blockchain.chainHeadHeader.gasLimit)
            .ommersHash(Hash.EMPTY_LIST_HASH)
            .logsBloom(LogsBloomFilter.empty())
            .extraData(Bytes.EMPTY)
            .mixHash(Hash.EMPTY)
            .parentHash(protocolContext.blockchain.chainHeadHash)
            .number(protocolContext.blockchain.chainHeadBlockNumber + 1)
            .timestamp(System.currentTimeMillis())
            .blockHeaderFunctions(protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber + 1).blockHeaderFunctions)
            .transactionsRoot(BodyValidation.transactionsRoot(listOf(transaction)))

        val processableBlockHeader = blockheaderBuilder
            .buildProcessableBlockHeader()

        val result = protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber + 1).transactionProcessor.processTransaction(
            protocolContext.blockchain,
            worldStateUpdater,
            processableBlockHeader,
            transaction,
            miningBeneficiary,
            operationTracer,
            BlockHashLookup(processableBlockHeader, protocolContext.blockchain),
            true
        )

        worldStateUpdater.commit()

        rewardMiner(worldState, miningBeneficiary)

        LOG.info("Ending with root: {}", worldState.rootHash())

        val gasUsed = transaction.gasLimit - result.gasRemaining

        val transactionReceipt = protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber + 1).transactionReceiptFactory.create(transaction.type, result, worldState, gasUsed)

        val blockHeader = blockheaderBuilder
            .gasUsed(gasUsed)
            .receiptsRoot(BodyValidation.receiptsRoot(listOf(transactionReceipt)))
            .logsBloom(BodyValidation.logsBloom(listOf(transactionReceipt)))
            .stateRoot(worldState.rootHash())
            .buildBlockHeader()

        val block = Block(blockHeader, BlockBody(listOf(transaction), emptyList()))

        worldState.persist()

        protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber + 1).blockImporter.importBlock(protocolContext, block, HeaderValidationMode.NONE)

        return transaction.hash.toHexString()
    }

    fun rewardMiner(
        worldState: MutableWorldState,
        coinbaseAddress: Address
    ) {
        val coinbaseReward: Wei = protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber).blockReward
        val updater = worldState.updater()
        val coinbase = updater.getOrCreate(coinbaseAddress).mutable
        coinbase.incrementBalance(coinbaseReward)
        updater.commit()
    }

    fun getTransactionCount(
        w3jAddress: org.web3j.abi.datatypes.Address,
        defaultBlockParameter: String
    ): BigInteger {
        val blockParameter = BlockParameter(defaultBlockParameter)
        val blockNumber: Optional<Long> = blockParameter.number
        val worldState = when {
            blockNumber.isPresent -> {
                blockchainQueries.getWorldState(blockNumber.get()).get() }
            blockParameter.isLatest -> {
                blockchainQueries.getWorldState(blockchainQueries.headBlockNumber()).get() }
            blockParameter.isEarliest -> {
                blockchainQueries.getWorldState(0).get() }
            else -> {
                // If block parameter is pending but there is no pending state so take latest
                blockchainQueries.getWorldState(blockchainQueries.headBlockNumber()).get()
            }
        }
        val nonce = worldState.get(Address.fromHexString(w3jAddress.value)).nonce
        return BigInteger.valueOf(nonce)
    }

    fun getTransactionReceipt(transactionHashParam: String): TransactionReceipt? {
        val hash = Hash.fromHexStringLenient(transactionHashParam)

        val result = blockchainQueries
            .transactionReceiptByTransactionHash(hash)
            .map { receipt -> getResult(receipt) }
            .orElse(null)
            ?: return null

        val transactionHash = result.transactionHash
        val transactionIndex = result.transactionIndex
        val blockHash = result.blockHash
        val blockNumber = result.blockNumber
        val cumulativeGasUsed = result.cumulativeGasUsed
        val gasUsed = result.gasUsed
        val contractAddress = result.contractAddress
        val root = if (result is TransactionReceiptRootResult) result.root else null
        val status = if (result is TransactionReceiptStatusResult) result.status else null
        val from = result.from
        val to = result.to
        val logs = result.logs.map(this::mapTransactionReceiptLog)
        val logsBloom = result.logsBloom

        return TransactionReceipt(
            transactionHash,
            transactionIndex,
            blockHash,
            blockNumber,
            cumulativeGasUsed,
            gasUsed,
            contractAddress,
            root,
            status,
            from,
            to,
            logs,
            logsBloom,
            ""
        )
    }

    private fun mapTransactionReceiptLog(result: TransactionReceiptLogResult): org.web3j.protocol.core.methods.response.Log {
        val removed = result.isRemoved
        val logIndex = result.logIndex
        val transactionIndex = result.transactionIndex
        val transactionHash = result.transactionHash
        val blockHash = result.blockHash
        val blockNumber = result.blockNumber
        val address = result.address
        val data = result.data
        val type: String? = null // TODO?
        val topics = result.topics

        return org.web3j.protocol.core.methods.response.Log(
            removed,
            logIndex,
            transactionIndex,
            transactionHash,
            blockHash,
            blockNumber,
            address,
            data,
            type,
            topics
        )
    }

    private fun getResult(receipt: TransactionReceiptWithMetadata): TransactionReceiptResult {
        return if (receipt.receipt.transactionReceiptType == TransactionReceiptType.ROOT) {
            TransactionReceiptRootResult(receipt)
        } else {
            TransactionReceiptStatusResult(receipt)
        }
    }

    fun makeEthCall(
        web3jTransaction: org.web3j.protocol.core.methods.request.Transaction,
        defaultBlockParameter: String
    ): TransactionProcessingResult? {
        val nonce = if (web3jTransaction.nonce == null)
            getTransactionCount(
                org.web3j.abi.datatypes.Address(web3jTransaction.from),
                defaultBlockParameter
            ).toLong()
        else
            hexToULong(web3jTransaction.nonce)

        val transaction = Transaction.builder()
            .gasLimit(transactionGasLimitOrDefault(web3jTransaction))
            .gasPrice(Wei.of(Numeric.decodeQuantity(web3jTransaction.gasPrice ?: "0x01")))
            .nonce(nonce)
            .sender(Address.fromHexString(web3jTransaction.from))
            .to(Address.fromHexString(web3jTransaction.to))
            .payload(Bytes.fromHexString(web3jTransaction.data ?: Bytes.EMPTY.toString()))
            .value(Wei.of(UInt256.fromHexString(web3jTransaction.value ?: "0x00")))
            .signature(FAKE_SIGNATURE)
            .build()

        val worldState =
            protocolContext.worldStateArchive.getMutable(protocolContext.blockchain.chainHeadHeader.stateRoot).get()
        val worldStateUpdater = worldState.updater()

        return protocolSchedule.getByBlockNumber(protocolContext.blockchain.chainHeadBlockNumber).transactionProcessor.processTransaction(
            protocolContext.blockchain,
            worldStateUpdater,
            genesisState.block.header,
            transaction,
            miningBeneficiary,
            operationTracer,
            BlockHashLookup(protocolContext.blockchain.chainHeadHeader, protocolContext.blockchain),
            false
        )
    }

    fun ethCall(
        web3jTransaction: org.web3j.protocol.core.methods.request.Transaction,
        defaultBlockParameter: String
    ): String {
        // TODO error case..? See EthCall in Besu..
        return makeEthCall(web3jTransaction, defaultBlockParameter)!!.output.toHexString()
    }

    fun estimateGas(
        web3jTransaction: org.web3j.protocol.core.methods.request.Transaction
    ): String {
        val gasLimit = transactionGasLimitOrDefault(web3jTransaction)
        val result = makeEthCall(web3jTransaction, "latest")
        val gasUse = gasLimit - result!!.gasRemaining

        return longToHex(gasUse)
    }

    fun ethBlockNumber(): String {
        return longToHex(blockchainQueries.headBlockNumber())
    }

    fun ethGetBalance(
        w3jAddress: org.web3j.abi.datatypes.Address,
        @Suppress("UNUSED_PARAMETER") defaultBlockParameter: String
    ): String? {
        return blockchainQueries
            .accountBalance(Address.fromHexString(w3jAddress.value), blockchainQueries.headBlockNumber())
            .map(Wei::toHexString)
            .orElse(null)
    }

    fun ethBlockByHash(hash: String, fullTransactionObjects: Boolean): EthBlock.Block? {
        val maybeBlockResult = if (fullTransactionObjects)
            blockchainQueries.blockByHashWithTxHashes(Hash.fromHexString(hash))
                .map { tx -> blockResultFactory.transactionHash(tx) }
        else
            blockchainQueries.blockByHash(Hash.fromHexString(hash))
                .map { tx -> blockResultFactory.transactionComplete(tx) }

        return maybeBlockResult.map { br -> ethBlockByBlockResult(br, fullTransactionObjects) }.orElse(null)
    }

    fun ethBlockByNumber(blockNumber: String, fullTransactionObjects: Boolean): EthBlock.Block? {
        val maybeBlockResult = if (fullTransactionObjects)
            blockchainQueries.blockByNumberWithTxHashes(hexToULong(blockNumber))
                .map { tx -> blockResultFactory.transactionHash(tx) }
        else
            blockchainQueries.blockByNumber(hexToULong(blockNumber))
                .map { tx -> blockResultFactory.transactionComplete(tx) }

        return maybeBlockResult.map { br -> ethBlockByBlockResult(br, fullTransactionObjects) }.orElse(null)
    }

    private fun ethBlockFullTransactionObject(blockResult: BlockResult): List<EthBlock.TransactionObject> {
        return blockResult.transactions.map {
            val tcr = it as TransactionCompleteResult
            EthBlock.TransactionObject(
                tcr.hash,
                tcr.nonce,
                tcr.blockHash,
                tcr.blockNumber,
                tcr.transactionIndex,
                tcr.from,
                tcr.to,
                tcr.value,
                tcr.gasPrice,
                tcr.gas,
                tcr.input,
                null, // TODO?
                null, // TODO?
                null, // TODO?
                tcr.r,
                tcr.s,
                hexToULong(tcr.v).toInt()
            )
        }
    }

    private fun ethBlockTransactionHash(blockResult: BlockResult): List<EthBlock.TransactionHash> {
        return blockResult.transactions.map {
            val thr = it as TransactionHashResult
            EthBlock.TransactionHash(
                thr.hash
            )
        }
    }

    private fun ethBlockByBlockResult(blockResult: BlockResult, fullTransactionObjects: Boolean): EthBlock.Block? {
        val transactionResults = if (fullTransactionObjects)
            ethBlockFullTransactionObject(blockResult)
        else
            ethBlockTransactionHash(blockResult)

        return EthBlock.Block(
            blockResult.number,
            blockResult.hash,
            blockResult.parentHash,
            blockResult.nonce,
            blockResult.sha3Uncles,
            blockResult.logsBloom,
            blockResult.transactionsRoot,
            blockResult.stateRoot,
            blockResult.receiptsRoot,
            null, // TODO?
            blockResult.miner,
            null, // TODO?
            blockResult.difficulty,
            blockResult.totalDifficulty,
            blockResult.extraData,
            blockResult.size,
            blockResult.gasLimit,
            blockResult.gasUsed,
            blockResult.timestamp,
            transactionResults,
            null, // TODO?
            null // TODO?
        )
    }

    fun ethGetCode(w3jAddress: org.web3j.abi.datatypes.Address, defaultBlockParameter: String): String {
        val blockParameter = BlockParameter(defaultBlockParameter)
        val blockNumber: Optional<Long> = blockParameter.number
        val besuAddress = Address.fromHexString(w3jAddress.toString())
        return when {
            blockNumber.isPresent -> {
                blockchainQueries.getCode(besuAddress, blockNumber.get())?.get()?.toString() ?: "0x"
            }
            blockParameter.isLatest -> {
                blockchainQueries.getCode(besuAddress, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
            blockParameter.isEarliest -> {
                blockchainQueries.getCode(besuAddress, 0)?.get()?.toString() ?: "0x"
            }
            else -> {
                // If block parameter is pending but there is no pending state so take latest
                blockchainQueries.getCode(besuAddress, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
        }
    }

    fun ethGetBlockTransactionCountByHash(hash: Hash): String {
        return Numeric.encodeQuantity(BigInteger.valueOf(blockchainQueries.getTransactionCount(hash).toLong()))
    }

    fun ethGetBlockTransactionCountByNumber(blockNumber: Long): String {
        return Numeric.encodeQuantity(BigInteger.valueOf(blockchainQueries.getTransactionCount(blockNumber).get().toLong()))
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EmbeddedEthereum::class.java)

        private fun hexToULong(hex: String): Long {
            return UInt256.fromHexString(hex).toLong()
        }

        private fun longToHex(value: Long): String {
            return UInt256.valueOf(value).toHexString()
        }

        private fun transactionGasLimitOrDefault(web3jTransaction: org.web3j.protocol.core.methods.request.Transaction): Long {
            // TODO: Verify happy with default value..
            return if (web3jTransaction.gas == null) 10_000_000L else hexToULong(web3jTransaction.gas)
        }
    }

    private class TestInMemoryStorage(hashValueStore: Map<Bytes, ByteArray>) :
        InMemoryKeyValueStorage(hashValueStore)
}
