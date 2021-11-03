/*
 * Copyright 2020 Web3 Labs Ltd.
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
package org.web3j.evm;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import org.hyperledger.besu.ethereum.vm.OperationTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.generated.Fibonacci;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

public class EmbeddedEthereumTest {

    private EmbeddedEthereum embeddedEthereum;

    @BeforeEach
    public void setUp() {
        embeddedEthereum =
                new EmbeddedEthereum(
                        new Configuration(Address.DEFAULT, 1000000), OperationTracer.NO_TRACING);
    }

    @Test
    public void canProcessTransactionWhichEmitsEvent() {
        final String deployHash =
                embeddedEthereum.processTransaction(
                        Transaction.createContractTransaction(
                                Address.DEFAULT.toString(),
                                BigInteger.ZERO,
                                BigInteger.ZERO,
                                Fibonacci.BINARY));

        final TransactionReceipt deployReceipt = embeddedEthereum.getTransactionReceipt(deployHash);

        final Function function =
                new Function(
                        Fibonacci.FUNC_FIBONACCINOTIFY,
                        Arrays.<Type>asList(
                                new org.web3j.abi.datatypes.generated.Uint256(BigInteger.TEN)),
                        Collections.<TypeReference<?>>emptyList());

        final String notifyFib =
                embeddedEthereum.processTransaction(
                        Transaction.createFunctionCallTransaction(
                                Address.DEFAULT.toString(),
                                BigInteger.ONE,
                                BigInteger.ZERO,
                                BigInteger.valueOf(50000),
                                deployReceipt.getContractAddress(),
                                FunctionEncoder.encode(function)));

        final TransactionReceipt notifyFibReceipt =
                embeddedEthereum.getTransactionReceipt(notifyFib);
    }
}
