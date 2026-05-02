package cn.nexus.infrastructure.mq.reliable.exception;

public class ReliableMqPermanentFailureException extends RuntimeException {

    public ReliableMqPermanentFailureException(String message) {
        super(message);
    }

    public ReliableMqPermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
