# web3j-evm [![Build Status](https://travis-ci.org/web3j/evm.svg?branch=master)](https://travis-ci.org/web3j/evm)

**⚠️ This is a work in progress! ⚠**

Web3j-evm is a local freestanding Ethereum EVM and ledger running within a Java process, which can be used for unit and integration testing your Web3j projects.

As everything is local and in-process, there is no need to start up external Ethereum nodes, which helps with easy of use and performance.

Everything runs within the JVM process, including EVM bytecode, which allows for easy debugging of for example Solidity smart contracts.

## How to use

This project is currently under heavy development, and we'll update this section shortly.

Below is a simple demo that shows that we can do ETH transactions, contract deployment and simple contract interactions.
Using the ConsoleDebugTracer, we're able to step through the EVM bytecode and inspect the stack.

![](https://raw.githubusercontent.com/web3j/evm/master/resources/web3j-evm-demo.gif)
