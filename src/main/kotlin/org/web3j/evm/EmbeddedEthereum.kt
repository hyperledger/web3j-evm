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

import com.google.common.io.Resources
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt256
import org.hyperledger.besu.cli.config.EthNetworkConfig
import org.hyperledger.besu.cli.config.NetworkName
import org.hyperledger.besu.config.GenesisConfigFile
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
import org.hyperledger.besu.ethereum.chain.DefaultBlockchain
import org.hyperledger.besu.ethereum.chain.GenesisState
import org.hyperledger.besu.ethereum.chain.MutableBlockchain
import org.hyperledger.besu.ethereum.core.Address
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.BlockBody
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder
import org.hyperledger.besu.ethereum.core.Hash
import org.hyperledger.besu.ethereum.core.MutableWorldState
import org.hyperledger.besu.ethereum.core.PrivacyParameters
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.core.TransactionReceipt
import org.hyperledger.besu.ethereum.core.Wei
import org.hyperledger.besu.ethereum.core.WorldUpdater
import org.hyperledger.besu.ethereum.mainnet.BodyValidation
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule
import org.hyperledger.besu.ethereum.mainnet.ScheduleBasedBlockHeaderFunctions
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStoragePrefixedKeyBlockchainStorage
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStateKeyValueStorage
import org.hyperledger.besu.ethereum.storage.keyvalue.WorldStatePreimageKeyValueStorage
import org.hyperledger.besu.ethereum.transaction.CallParameter
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult
import org.hyperledger.besu.ethereum.vm.BlockHashLookup
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.worldstate.DefaultWorldStateArchive
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage
import org.slf4j.LoggerFactory
import org.web3j.evm.Configuration.Companion.TEST_ACCOUNTS
import org.web3j.protocol.core.methods.response.AccessListObject
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional
import org.web3j.abi.datatypes.Address as wAddress
import org.web3j.protocol.core.methods.request.Transaction as wTransaction
import org.web3j.protocol.core.methods.response.TransactionReceipt as wTransactionReceipt

/**
 *
 */
class EmbeddedEthereum @JvmOverloads constructor(
    configuration: Configuration,
    private val operationTracer: OperationTracer,
    networkName: NetworkName = NetworkName.DEV,
    genesisConfigOverrides: Map<String, String> = DEFAULT_GENESIS_OVERRIDES
) {
    private val genesisState: GenesisState
    private val blockchainQueries: BlockchainQueries
    private val miningBeneficiary = Address.ZERO
    private val blockResultFactory = BlockResultFactory()

    private val worldState: MutableWorldState
    private val worldStateArchive: WorldStateArchive
    private val worldStateUpdater: WorldUpdater
    private val protocolSchedule: ProtocolSchedule
    private val blockChain: MutableBlockchain

    private val simulator: TransactionSimulator

    init {
        val genesisConfig = if (configuration.genesisFileUrl === null) {
            val networkConfig = EthNetworkConfig.getNetworkConfig(networkName)
            GenesisConfigFile.fromConfig(networkConfig.genesisConfig)
        } else {
            GenesisConfigFile.fromConfig(
                @Suppress("UnstableApiUsage")
                Resources.toString(
                    configuration.genesisFileUrl,
                    UTF_8
                )
            )
        }
        val configOptions = genesisConfig.getConfigOptions(genesisConfigOverrides)

        protocolSchedule = MainnetProtocolSchedule.fromConfig(
            configOptions,
            PrivacyParameters.DEFAULT,
            true
        )

        val keyValueStorage = InMemoryKeyValueStorage()
        val blockchainStorage = KeyValueStoragePrefixedKeyBlockchainStorage(
            keyValueStorage, MainnetBlockHeaderFunctions()
        )
        val worldStateStorage = WorldStateKeyValueStorage(InMemoryKeyValueStorage())
        val worldStatePreimageStorage = WorldStatePreimageKeyValueStorage(InMemoryKeyValueStorage())

        worldStateArchive = DefaultWorldStateArchive(
            worldStateStorage,
            worldStatePreimageStorage
        )
        worldState = worldStateArchive.mutable
        worldStateUpdater = worldState.updater()

        genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule)
        genesisState.writeStateTo(worldState)

        LOG.debug("Genesis Block Hash: ${genesisState.block.hash}")

        blockChain = DefaultBlockchain.createMutable(
            genesisState.block,
            blockchainStorage,
            NoOpMetricsSystem(),
            6L
        )

        // Create accounts from Web3j configuration
        LOG.debug("Allocating Web3j account ${configuration.selfAddress}")

        // Note that this resets the account if already exists
        worldStateUpdater.createAccount(
            configuration.selfAddress.asBesu(),
            0,
            Wei.fromEth(configuration.ethFund)
        )

        worldStateUpdater.commit()

        blockchainQueries = BlockchainQueries(blockChain, worldStateArchive)
        simulator = TransactionSimulator(
            blockChain,
            worldStateArchive,
            protocolSchedule,
            PrivacyParameters.DEFAULT
        )
    }

    /**
     * Process an unsigned Web3j transaction object.
     *
     * The transaction will be signed by the matching key pair
     * for the provided "from" address in the transaction if it is
     * defined in the test accounts, otherwise will be signed with
     * the first test account key pair.
     *
     * @param web3jTransaction The web3j transaction
     * @return the transaction hash
     * @see TEST_ACCOUNTS
     */
    fun processTransaction(web3jTransaction: wTransaction): String {
        val transaction = toBesuTx(web3jTransaction)
        processTransaction(transaction)
        return transaction.hash.toHexString()
    }

    /**
     * Process a signed RLP encoded transaction.
     *
     * @param signedTransactionData The singed RLP encoded transaction data
     * @return the transaction hash
     */
    fun processTransaction(signedTransactionData: String): String {
        val transaction = Transaction.readFrom(RLP.input(Bytes.fromHexString(signedTransactionData)))
        processTransaction(transaction)
        return transaction.hash.toHexString()
    }

    /**
     * Process a Besu transaction object.
     *
     * The logic is as follows:
     * 1) A new block header is created following the current chain head.
     * 2) The transaction is processed using this new block header.
     * 3) If the processing result is valid the world state is updated.
     * 4) Transaction receipts are created for the processed transaction.
     * 5) A new block is created and append to the blockchain with the transaction and receipt.
     *
     * @param transaction The Besu transaction
     * @return the transaction receipt
     */
    private fun processTransaction(transaction: Transaction): TransactionReceipt {
        LOG.debug("Starting with root: {}", worldState.rootHash())

        val nextBlockNumber = blockChain.chainHeadBlockNumber + 1
        val spec = protocolSchedule.getByBlockNumber(nextBlockNumber)
        val transactionProcessor = spec.transactionProcessor
        val transactionReceiptFactory = spec.transactionReceiptFactory
        val transactions = listOf(transaction)

        val processableBlockHeader = createPendingBlockHeader()
        val blockHashLookup = BlockHashLookup(processableBlockHeader, blockChain)
        val result = transactionProcessor.processTransaction(
            blockChain,
            worldStateUpdater,
            processableBlockHeader,
            transaction,
            miningBeneficiary,
            operationTracer,
            blockHashLookup,
            true
        )

        if (result.isInvalid) {
            throw Exception(result.validationResult.errorMessage)
        }

        worldStateUpdater.commit()

        // TODO review this
        rewardMiner(worldState, miningBeneficiary)

        LOG.debug("Ending with root: {}", worldState.rootHash())

        val gasUsed = result.estimateGasUsedByTransaction
        val transactionReceipt = transactionReceiptFactory.create(
            transaction.type,
            result,
            worldState,
            gasUsed
        )
        val receipts = listOf(transactionReceipt)

        val sealableBlockHeader = BlockHeaderBuilder.create()
            .populateFrom(processableBlockHeader)
            .ommersHash(Hash.EMPTY_LIST_HASH)
            .stateRoot(worldState.rootHash())
            .transactionsRoot(BodyValidation.transactionsRoot(transactions))
            .receiptsRoot(BodyValidation.receiptsRoot(receipts))
            .logsBloom(BodyValidation.logsBloom(receipts))
            .gasUsed(gasUsed)
            .extraData(Bytes.EMPTY)
            .buildSealableBlockHeader()

        val blockHeaderFunctions = ScheduleBasedBlockHeaderFunctions.create(protocolSchedule)
        val blockHeader = BlockHeaderBuilder.create()
            .populateFrom(sealableBlockHeader)
            .nonce(blockChain.chainHeadHeader.nonce + 1)
            .mixHash(Hash.ZERO)
            .blockHeaderFunctions(blockHeaderFunctions)
            .buildBlockHeader()
        val block = Block(blockHeader, BlockBody(transactions, emptyList()))

        worldState.persist(blockHeader)
        blockChain.appendBlock(block, receipts)

        return transactionReceipt
    }

    private fun toBesuTx(web3jTransaction: wTransaction): Transaction {
        val chainId = web3jTransaction.chainId?.toBigInteger()
            ?: protocolSchedule.chainId.orElse(BigInteger.ONE)
        val nonce = if (web3jTransaction.nonce == null) {
            getTransactionCount(wAddress(web3jTransaction.from)).toLong()
        } else {
            hexToULong(web3jTransaction.nonce)
        }
        val from = (web3jTransaction.from ?: "0x0").toLowerCase()

        // TODO should we add support for account 0x0 fake signature?
        return Transaction.builder()
            .gasLimit(transactionGasLimitOrDefault(web3jTransaction))
            .gasPrice(Wei.of(hexToULong(web3jTransaction.gasPrice)))
            .nonce(nonce)
            .sender(Address.fromHexString(from))
            .to(Address.fromHexString(web3jTransaction.to))
            .payload(Bytes.fromHexString(web3jTransaction.data))
            .value(Wei.of(UInt256.fromHexString(web3jTransaction.value ?: "0x0")))
            .chainId(chainId)
            .guessType()
            .signAndBuild(
                TEST_ACCOUNTS[from]
                    ?: TEST_ACCOUNTS.values.first()
            )
    }

    private fun createPendingBlockHeader(): ProcessableBlockHeader? {
        val parentHeader = blockChain.chainHeadHeader
        val newBlockNumber = parentHeader.number + 1

        return BlockHeaderBuilder.create()
            .parentHash(parentHeader.hash)
            .coinbase(miningBeneficiary)
            .difficulty(parentHeader.difficulty.plus(100000))
            .number(newBlockNumber)
            .gasLimit(parentHeader.gasLimit)
            .timestamp(System.currentTimeMillis())
            .baseFee(parentHeader.baseFee.orElse(0))
            .buildProcessableBlockHeader()
    }

    private fun rewardMiner(
        worldState: MutableWorldState,
        coinbaseAddress: Address
    ) {
        val spec = protocolSchedule.getByBlockNumber(blockChain.chainHeadBlockNumber)
        val coinbaseReward: Wei = spec.blockReward
        val updater = worldState.updater()
        val coinbase = updater.getOrCreate(coinbaseAddress).mutable
        coinbase.incrementBalance(coinbaseReward)
        updater.commit()
    }

    fun getTransactionCount(w3jAddress: wAddress): BigInteger {
        val count = blockchainQueries.getTransactionCount(w3jAddress.asBesu())
        return BigInteger.valueOf(count)
    }

    fun getTransactionReceipt(transactionHashParam: String): wTransactionReceipt? {
        val hash = Hash.fromHexStringLenient(transactionHashParam)

        val result = blockchainQueries
            .transactionReceiptByTransactionHash(hash)
            .map { receipt -> getResult(receipt) }
            .orElse(null)
            ?: return null

        val blockHash = result.blockHash
        val blockNumber = result.blockNumber
        val contractAddress = result.contractAddress
        val cumulativeGasUsed = result.cumulativeGasUsed
        val from = result.from
        val gasUsed = result.gasUsed
        val effectiveGasPrice = result.effectiveGasPrice
        val logs = result.logs.map(this::mapTransactionReceiptLog)
        val logsBloom = result.logsBloom
        val to = result.to
        val transactionHash = result.transactionHash
        val transactionIndex = result.transactionIndex
        val root = if (result is TransactionReceiptRootResult) result.root else null
        val status = if (result is TransactionReceiptStatusResult) result.status else null

        return wTransactionReceipt(
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
            "",
            "",
            effectiveGasPrice

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

    fun ethCall(
        web3jTransaction: wTransaction,
        defaultBlockParameter: String
    ): String {
        val result = processCall(web3jTransaction, defaultBlockParameter)
        // TODO handle errors
        return result.get().result.output.toHexString()
    }

    private fun processCall(
        web3jTransaction: wTransaction,
        defaultBlockParameter: String
    ): Optional<TransactionSimulatorResult> {
        return simulator.processAtHead(
            CallParameter(
                Address.fromHexString(web3jTransaction.from),
                Address.fromHexString(web3jTransaction.to),
                Long.MAX_VALUE,
                Wei.ZERO,
                Wei.ZERO,
                Bytes.fromHexString(web3jTransaction.data)
            )
        )
    }

    fun estimateGas(
        web3jTransaction: wTransaction
    ): String {
        val result = processCall(web3jTransaction, "latest")
        return longToHex(result.get().result.estimateGasUsedByTransaction)
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
        return blockResult.transactions.map { it ->
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
                hexToULong(tcr.v).toInt(),
                tcr.type,
                tcr.maxFeePerGas,
                tcr.maxPriorityFeePerGas,
                tcr.accessList.map {
                    AccessListObject(
                        it.address.toHexString(),
                        mutableListOf(it.storageKeys.toString())
                    )
                }
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
            null, // TODO?
            blockResult.baseFeePerGas
        )
    }

    fun ethGetCode(w3jAddress: wAddress, defaultBlockParameter: String): String {
        val blockParameter = BlockParameter(defaultBlockParameter)
        val blockNumber: Optional<Long> = blockParameter.number
        val address = w3jAddress.asBesu()

        return when {
            blockNumber.isPresent -> {
                blockchainQueries.getCode(address, blockNumber.get())?.get()?.toString() ?: "0x"
            }
            blockParameter.isLatest -> {
                blockchainQueries.getCode(address, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
            blockParameter.isEarliest -> {
                blockchainQueries.getCode(address, 0)?.get()?.toString() ?: "0x"
            }
            else -> {
                // If block parameter is pending but there is no pending state so take latest
                blockchainQueries.getCode(address, blockchainQueries.headBlockNumber())?.get()?.toString() ?: "0x"
            }
        }
    }

    fun ethGetBlockTransactionCountByHash(hash: Hash): String {
        return Numeric.encodeQuantity(BigInteger.valueOf(blockchainQueries.getTransactionCount(hash).toLong()))
    }

    fun ethGetBlockTransactionCountByNumber(blockNumber: Long): String {
        return Numeric.encodeQuantity(
            BigInteger.valueOf(
                blockchainQueries.getTransactionCount(blockNumber).get().toLong()
            )
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(EmbeddedEthereum::class.java)
        private val DEFAULT_GENESIS_OVERRIDES = mapOf(
            "londonblock" to "1",
            "petersburgblock" to "0"
        )

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

// TODO extract
private fun wAddress.asBesu(): Address {
    return Address.fromHexString(this.value)
}
