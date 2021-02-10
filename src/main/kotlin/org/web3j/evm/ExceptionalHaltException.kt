package org.web3j.evm;



import org.hyperledger.besu.ethereum.vm.ExceptionalHaltReason;

public class ExceptionalHaltException extends Exception {
    private final ExceptionalHaltReason reason;

    public ExceptionalHaltException(final ExceptionalHaltReason reason) {
        this.reason = reason;
    }

    @Override
    public String getMessage() {
        return "Exceptional halt condition(s) triggered: " + this.reason;
    }

    public ExceptionalHaltReason getReasons() {
        return reason;
    }
}
