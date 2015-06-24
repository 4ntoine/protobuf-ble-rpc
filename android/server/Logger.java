package com.googlecode.protobuf.socketrpc;

/**
 *
 */
public class Logger {

    protected Logger() {
    }

    private static Logger instance;

    public static Logger get() {
        if (instance == null)
            instance = new Logger();
        return instance;
    }

    public static void set(Logger logger) {
        instance = logger;
    }

    public void log(String message) {
        // nothing (to be implemented in inheritor)
    }
}
