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

import java.math.BigInteger
import java.util.Optional
import java.util.OptionalInt
import org.hyperledger.besu.config.GenesisConfigFile
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
import org.hyperledger.besu.ethereum.chain.DefaultBlockchain
import org.hyperledger.besu.ethereum.chain.GenesisState
import org.hyperledger.besu.ethereum.chain.MutableBlockchain
import org.hyperledger.besu.ethereum.core.Address
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.BlockBody
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions
import org.hyperledger.besu.ethereum.core.Hash
import org.hyperledger.besu.ethereum.core.PrivacyParameters
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.core.Wei
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockProcessor
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSpecs
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionValidator
import org.hyperledger.besu.ethereum.mainnet.TransactionProcessor
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider
import org.hyperledger.besu.ethereum.vm.BlockHashLookup
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.worldstate.DefaultMutableWorldState
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage
import org.hyperledger.besu.util.bytes.BytesValue
import org.hyperledger.besu.util.uint.UInt256
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.TransactionReceipt

class LocalEthereum(w3jSelfAddress: org.web3j.abi.datatypes.Address, private val operationTracer: OperationTracer) {
    private val genesisState: GenesisState
    private val transactionProcessor: TransactionProcessor
    private val transactionReceiptFactory: MainnetBlockProcessor.TransactionReceiptFactory
    private val blockchain: MutableBlockchain
    private val worldState: DefaultMutableWorldState
    private val blockchainQueries: BlockchainQueries
    private val blockHashLookup: BlockHashLookup
    private val blockHeaderFunctions: BlockHeaderFunctions
    private val miningBeneficiary: Address
    private val isPersistingState: Boolean
    private val blockResultFactory: BlockResultFactory

    init {
        val chainId = Optional.empty<BigInteger>()

        val genesisConfig = GenesisConfigFile.mainnet()
        val protocolSchedule = MainnetProtocolSchedule.fromConfig(genesisConfig.configOptions)

        val protocolSpec = MainnetProtocolSpecs
            .istanbulDefinition(chainId, OptionalInt.empty(), OptionalInt.empty(), false)
            .privacyParameters(PrivacyParameters.DEFAULT)
            .transactionValidatorBuilder { gasCalculator ->
                MainnetTransactionValidator(
                    gasCalculator,
                    false,
                    Optional.empty()
                )
            }
            .build(protocolSchedule)

        genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule)

        transactionProcessor = protocolSpec.transactionProcessor

        transactionReceiptFactory = protocolSpec.transactionReceiptFactory

        val storageProvider = KeyValueStorageProvider(
            InMemoryKeyValueStorage(),
            InMemoryKeyValueStorage(),
            InMemoryKeyValueStorage(),
            InMemoryKeyValueStorage(),
            InMemoryKeyValueStorage(),
            InMemoryKeyValueStorage(),
            false
        )
        val metricsSystem = NoOpMetricsSystem()

        val blockchainStorage = storageProvider.createBlockchainStorage(protocolSchedule)
        blockchain = DefaultBlockchain.createMutable(genesisState.block, blockchainStorage, metricsSystem)

        val worldStateStorage = storageProvider.createWorldStateStorage()
        val worldStatePreimageStorage = storageProvider.createWorldStatePreimageStorage()
        worldState = DefaultMutableWorldState(worldStateStorage, worldStatePreimageStorage)

        genesisState.writeStateTo(worldState)

        val updater = worldState.updater()
        updater.createAccount(Address.fromHexString(w3jSelfAddress.value), 0, Wei.fromEth(10))
        updater.commit()
        worldState.persist()

        blockchainQueries =
            BlockchainQueries(blockchain, WorldStateArchive(worldStateStorage, worldStatePreimageStorage))

        blockHashLookup = BlockHashLookup(genesisState.block.header, blockchain)

        blockHeaderFunctions = protocolSpec.blockHeaderFunctions

        miningBeneficiary = Address.ZERO

        isPersistingState = true

        blockResultFactory = BlockResultFactory()
    }

    fun processTransaction(web3jTransaction: org.web3j.protocol.core.methods.request.Transaction): String {
        val transaction = Transaction.builder()
            .gasLimit(transactionGasLimitOrDefault(web3jTransaction))
            .gasPrice(Wei.of(hexToULong(web3jTransaction.gasPrice)))
            .nonce(hexToULong(web3jTransaction.nonce))
            .sender(Address.fromHexString(web3jTransaction.from))
            .to(Address.fromHexString(web3jTransaction.to))
            .payload(BytesValue.fromHexString(web3jTransaction.data))
            .value(Wei.of(UInt256.fromHexString(web3jTransaction.value ?: "0x0")))
            .build()

        return processTransaction(transaction)
    }

    fun processTransaction(signedTransactionData: String): String {
        val transaction = Transaction.readFrom(RLP.input(BytesValue.fromHexString(signedTransactionData)))
        return processTransaction(transaction)
    }

    private fun processTransaction(transaction: Transaction): String {
        val worldStateUpdater = worldState.updater()
        val result = transactionProcessor.processTransaction(
            blockchain,
            worldStateUpdater,
            genesisState.block.header,
            transaction,
            miningBeneficiary,
            operationTracer,
            blockHashLookup,
            isPersistingState
        )

        worldStateUpdater.commit()
        worldState.persist()

        val blockHeader = BlockHeaderBuilder.fromHeader(blockchain.chainHeadHeader)
            .parentHash(blockchain.chainHeadHash)
            .number(blockchain.chainHeadBlockNumber + 1)
            .gasUsed(transaction.gasLimit - result.gasRemaining)
            .timestamp(System.currentTimeMillis())
            .blockHeaderFunctions(blockHeaderFunctions)
            .buildBlockHeader()

        val transactionReceipt = transactionReceiptFactory.create(result, worldState, blockHeader.gasUsed)

        val block = Block(blockHeader, BlockBody(listOf(transaction), emptyList()))

        blockchain.appendBlock(block, listOf(transactionReceipt))

        return transaction.hash.hexString
    }

    fun getTransactionCount(
        w3jAddress: org.web3j.abi.datatypes.Address,
        @Suppress("UNUSED_PARAMETER") defaultBlockParameterName: DefaultBlockParameterName
    ): BigInteger {
        val address = Address.fromHexString(w3jAddress.value)
        val account = worldState.get(address)
        val nonce = account?.nonce ?: 0L

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
            logsBloom
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
        @Suppress("UNUSED_PARAMETER") defaultBlockParameter: String
    ): TransactionProcessor.Result? {
        val nonce = if (web3jTransaction.nonce == null)
            getTransactionCount(
                org.web3j.abi.datatypes.Address(web3jTransaction.from),
                DefaultBlockParameterName.PENDING
            ).toLong()
        else
            hexToULong(web3jTransaction.nonce)

        val transaction = Transaction.builder()
            .gasLimit(transactionGasLimitOrDefault(web3jTransaction))
            // TODO: Better management of defaults..
            .gasPrice(if (web3jTransaction.gas == null) Wei.of(1) else Wei.of(hexToULong(web3jTransaction.gasPrice)))
            .nonce(nonce)
            .sender(Address.fromHexString(web3jTransaction.from))
            .to(Address.fromHexString(web3jTransaction.to))
            .payload(if (web3jTransaction.data == null) BytesValue.EMPTY else BytesValue.fromHexString(web3jTransaction.data))
            .value(if (web3jTransaction.value == null) Wei.ZERO else Wei.of(UInt256.fromHexString(web3jTransaction.value)))
            .build()

        val worldState = this.worldState.copy()
        val worldStateUpdater = worldState.updater()
        val result = transactionProcessor.processTransaction(
            blockchain,
            worldStateUpdater,
            genesisState.block.header,
            transaction,
            miningBeneficiary,
            operationTracer,
            blockHashLookup,
            isPersistingState
        )

        return result
    }

    fun ethCall(
        web3jTransaction: org.web3j.protocol.core.methods.request.Transaction,
        defaultBlockParameter: String
    ): String {
        // TODO error case..? See EthCall in Besu..
        return makeEthCall(web3jTransaction, defaultBlockParameter)!!.output.hexString
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

    fun ethGetCode(w3jAddress: org.web3j.abi.datatypes.Address, @Suppress("UNUSED_PARAMETER") defaultBlockParameter: String): String {
        return blockchainQueries
            .getCode(Address.fromHexString(w3jAddress.value), blockchainQueries.headBlockNumber())
            .map(BytesValue::toString)
            .orElse(null)
    }

    companion object {
        private fun hexToULong(hex: String): Long {
            return UInt256.fromHexString(hex).toLong()
        }

        private fun longToHex(value: Long): String {
            return UInt256.of(value).toHexString()
        }

        private fun transactionGasLimitOrDefault(web3jTransaction: org.web3j.protocol.core.methods.request.Transaction): Long {
            // TODO: Verify happy with default value..
            return if (web3jTransaction.gas == null) 10_000_000L else hexToULong(web3jTransaction.gas)
        }
    }
}
