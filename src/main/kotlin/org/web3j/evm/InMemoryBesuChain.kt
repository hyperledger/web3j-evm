/*
 * Copyright 2021 Web3 Labs Ltd.
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
import org.hyperledger.besu.cli.config.EthNetworkConfig
import org.hyperledger.besu.cli.config.NetworkName
import org.hyperledger.besu.config.GenesisConfigFile
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResult
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries
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
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * Full in-memory Besu blockchain for easy testing.
 */
class InMemoryBesuChain(
    configuration: Configuration,
    private val operationTracer: OperationTracer,
    networkName: NetworkName = NetworkName.DEV,
    genesisConfigOverrides: Map<String, String> = DEFAULT_GENESIS_OVERRIDES
) {
    val chainId: BigInteger
    val blockchainQueries: BlockchainQueries

    private val genesisState: GenesisState
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
                    StandardCharsets.UTF_8
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

        // Create accounts from configuration
        LOG.debug("Allocating test account ${configuration.testAddress}")

        // Note that this resets the account if already exists
        worldStateUpdater.createAccount(
            Address.fromHexString(configuration.testAddress),
            0,
            Wei.fromEth(configuration.ethFund)
        )

        worldStateUpdater.commit()

        blockchainQueries = BlockchainQueries(blockChain, worldStateArchive)
        chainId = protocolSchedule.chainId.orElse(BigInteger.ONE)

        simulator = TransactionSimulator(
            blockChain,
            worldStateArchive,
            protocolSchedule,
            PrivacyParameters.DEFAULT
        )
    }

    /**
     * Process a Besu transaction object creating a new block for it.
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
    fun processTransaction(transaction: Transaction): TransactionReceipt {
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

    fun call(callParameter: CallParameter):
            Optional<TransactionSimulatorResult> {
        return simulator.processAtHead(callParameter)
    }

    fun ethBlockByHash(hash: Hash, completeTransaction: Boolean): Optional<BlockResult> {
        if (completeTransaction) {
            return blockchainQueries.blockByHashWithTxHashes(hash).map { tx ->
                blockResultFactory.transactionHash(tx)
            }
        }

        return blockchainQueries.blockByHash(hash).map { tx ->
            blockResultFactory.transactionComplete(tx)
        }
    }

    fun ethBlockByNumber(number: Long, completeTransaction: Boolean): Optional<BlockResult> {
        if (completeTransaction) {
            blockchainQueries.blockByNumberWithTxHashes(number).map { tx ->
                blockResultFactory.transactionHash(tx)
            }
        }

        return blockchainQueries.blockByNumber(number).map { tx ->
            blockResultFactory.transactionComplete(tx)
        }
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

    companion object {
        private val LOG = LoggerFactory.getLogger(InMemoryBesuChain::class.java)
        private val DEFAULT_GENESIS_OVERRIDES = mapOf(
            "londonblock" to "1",
            "petersburgblock" to "0"
        )
    }
}
