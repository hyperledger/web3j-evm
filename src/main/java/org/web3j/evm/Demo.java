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
package org.web3j.evm;

import java.math.BigDecimal;

import org.hyperledger.besu.ethereum.vm.OperationTracer;

import org.web3j.abi.datatypes.Address;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.greeter.generated.contracts.Greeter;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Transfer;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Convert;

public class Demo {
    public static void main(String... args) throws Exception {
        Credentials credentials =
                WalletUtils.loadCredentials("Password123", "resources/demo-wallet.json");

        // Define our own address and how much ether to prefund this address with
        Configuration configuration = new Configuration(new Address(credentials.getAddress()), 10);

        // If you don't want console debugging, use PassthroughTracer instead..
        OperationTracer operationTracer = new ConsoleDebugTracer();
        // OperationTracer operationTracer = new PassthroughTracer();

        // We use EmbeddedWeb3jService rather than the usual service implementation..
        Web3j web3j = Web3j.build(new EmbeddedWeb3jService(configuration, operationTracer));

        // Transaction etherTransaction =
        // Transaction.createEtherTransaction(credentials.getAddress(), BigInteger.ZERO,
        // BigInteger.ONE, BigInteger.valueOf(1_000_000),
        // "0x2dfBf35bb7c3c0A466A6C48BEBf3eF7576d3C420", Convert.toWei("1",
        // Convert.Unit.ETHER).toBigInteger());
        // BigInteger gasUsed = web3j.ethEstimateGas(etherTransaction).send().getAmountUsed();

        // First run a simple ETH transfer transaction..
        System.out.println("Starting simple ETH transfer transaction");
        TransactionReceipt transactionReceipt =
                Transfer.sendFunds(
                                web3j,
                                credentials,
                                "0x2dfBf35bb7c3c0A466A6C48BEBf3eF7576d3C420",
                                new BigDecimal("1"),
                                Convert.Unit.ETHER)
                        .send();

        System.out.println(
                "Transfer transaction receipt: " + transactionReceipt.getTransactionHash());
        if (!transactionReceipt.isStatusOK()) throw new RuntimeException("Failed transaction");

        System.out.println();

        // Deploy Greeter contract..
        System.out.println("Starting Greeter deploy..");
        Greeter greeter =
                Greeter.deploy(web3j, credentials, new DefaultGasProvider(), "Hello!").send();

        System.out.println();

        // Fetch greeter value..
        System.out.println("Greeter was deployed, about to get greeting..");

        String greet = greeter.greet().send();
        System.out.println("Greeter string value is: " + greet);
    }
}
