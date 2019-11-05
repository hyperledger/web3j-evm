package org.web3j.evm

import org.hyperledger.besu.ethereum.core.Gas
import org.hyperledger.besu.ethereum.vm.MessageFrame
import org.hyperledger.besu.ethereum.vm.OperationTracer
import org.hyperledger.besu.ethereum.vm.ehalt.ExceptionalHaltException

import java.util.Optional

class PassthroughTracer : OperationTracer {
    @Throws(ExceptionalHaltException::class)
    override fun traceExecution(
        messageFrame: MessageFrame,
        optional: Optional<Gas>,
        executeOperation: OperationTracer.ExecuteOperation
    ) = executeOperation.execute()
}
