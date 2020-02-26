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
package org.web3j.protocol.core;

import java.math.BigInteger;
import java.util.Arrays;

import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Uint;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;

/** Mordon Testnet Configuration. */
public class TestnetConfig implements IntegrationTestConfig {

    @Override
    public String validBlockHash() {
        // genesis block hash
        return "0xff96cc2ceaddefcfd1933ac5eb652a8c8668708beb3e5c0eff54aea202140012";
    }

    @Override
    public BigInteger validBlock() {
        // https://testnet.etherscan.io/block/71032
        return BigInteger.valueOf(0);
    }

    @Override
    public BigInteger validBlockTransactionCount() {
        return BigInteger.valueOf(0);
    }

    @Override
    public BigInteger validBlockUncleCount() {
        return BigInteger.ZERO;
    }

    @Override
    public String validAccount() {
        // From demo-wallet.json
        return "0xcb0365cd172e1308ad995d5445234b1693b4e9c4";
    }

    @Override
    public String validContractAddress() {
        // Deployed fibonacci example
        return "0x94d2553e6848fbbbbd7e6c17ec830b4e1282d92a";
    }

    @Override
    public BigInteger validContractBlock() {
        return BigInteger.valueOf(6383474);
    }

    @Override
    public String validContractAddressPositionZero() {
        return "0x0000000000000000000000000000000000000000000000000000000000000000";
    }

    @Override
    public String validContractCode() {
        return "0x60806040526004361061004b5763ffffffff7c01000000000000000000000000000000000000000000000000000000006000350416633c7fdc70811461005057806361047ff41461007a575b600080fd5b34801561005c57600080fd5b50610068600435610092565b60408051918252519081900360200190f35b34801561008657600080fd5b506100686004356100e0565b600061009d826100e0565b604080518481526020810183905281519293507f71e71a8458267085d5ab16980fd5f114d2d37f232479c245d523ce8d23ca40ed929081900390910190a1919050565b60008115156100f15750600061011e565b81600114156101025750600161011e565b61010e600283036100e0565b61011a600184036100e0565b0190505b9190505600a165627a7a723058201b9d0941154b95636fb5e4225fefd5c2c460060efa5f5e40c9826dce08814af80029";
    }

    @Override
    public Transaction buildTransaction(Web3j web3j) throws Exception {
        return Transaction.createContractTransaction(
                validAccount(),
                web3j.ethGetTransactionCount(
                                validAccount(), DefaultBlockParameter.valueOf("latest"))
                        .send()
                        .getTransactionCount(), // nonce
                Transaction.DEFAULT_GAS,
                "0x");
    }

    @Override
    public String validTransactionHash() {
        return "0x7f577ad310e847f93b8c0010c2c8fcc4df7238f36300e996dd6ba85f171be591";
    }

    @Override
    public String validUncleBlockHash() {
        return "0xaad80bc1288287a7d870286b4a89a7d26698a2e80320481510989bca9eb104fc";
    }

    @Override
    public BigInteger validUncleBlock() {
        return BigInteger.valueOf(6401616);
    }

    @Override
    public String encodedEvent() {
        Event event =
                new Event(
                        "Notify",
                        Arrays.asList(
                                new TypeReference<Uint>(true) {}, new TypeReference<Uint>() {}));

        return EventEncoder.encode(event);
    }
}
