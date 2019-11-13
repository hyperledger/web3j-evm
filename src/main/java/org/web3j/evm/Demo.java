package org.web3j.evm;

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

import java.math.BigDecimal;

public class Demo {
    public static void main(String... args) throws Exception {
        Credentials credentials = WalletUtils.loadCredentials("Password123", "resources/demo-wallet.json");

        // If you don't want console debugging, use PassthroughTracer instead..
        OperationTracer operationTracer = new ConsoleDebugTracer();
        //OperationTracer operationTracer = new PassthroughTracer();

        // We use LocalWeb3jService rather than the usual service implementation..
        Web3j web3j = Web3j.build(new LocalWeb3jService(new Address(credentials.getAddress()), operationTracer));

        // Transaction etherTransaction = Transaction.createEtherTransaction(credentials.getAddress(), BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(1_000_000), "0x2dfBf35bb7c3c0A466A6C48BEBf3eF7576d3C420", Convert.toWei("1", Convert.Unit.ETHER).toBigInteger());
        // BigInteger gasUsed = web3j.ethEstimateGas(etherTransaction).send().getAmountUsed();

        // First run a simple ETH transfer transaction..
        System.out.println("Starting simple ETH transfer transaction");
        TransactionReceipt transactionReceipt =
                Transfer.sendFunds(
                        web3j,
                        credentials, "0x2dfBf35bb7c3c0A466A6C48BEBf3eF7576d3C420",
                        new BigDecimal("1"), Convert.Unit.ETHER).send();

        System.out.println("Transfer transaction receipt: " + transactionReceipt.getTransactionHash());
        if (!transactionReceipt.isStatusOK())
            throw new RuntimeException("Failed transaction");

        System.out.println();

        // Deploy Greeter contract..
        System.out.println("Starting Greeter deploy..");
        Greeter greeter = Greeter.deploy(web3j, credentials, new DefaultGasProvider(), "Hello!").send();

        System.out.println();

        // Fetch greeter value..
        System.out.println("Greeter was deployed, about to get greeting..");

        String greet = greeter.greet().send();
        System.out.println("Greeter string value is: " + greet);
    }
}
