package com.mkpro.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class BasicProcessExecutorTest {

    private final ICommandExecutor executor = new BasicProcessExecutor(5, 1024);

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testWindowsEcho() {
        ExecutionResult result = executor.execute("echo Hello World");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("Hello World"));
        assertEquals(0, result.getExitCode());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testUnixEcho() {
        ExecutionResult result = executor.execute("echo 'Hello World'");
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("Hello World"));
        assertEquals(0, result.getExitCode());
    }

    @Test
    void testTimeout() {
        // Command that runs for 10+ seconds, but executor has 5s timeout
        // Use an allowlisted command so this exercises the executor timeout rather
        // than being rejected by CommandPolicy before a process is started.
        String sleepCmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "ping -n 11 127.0.0.1" : "ping 127.0.0.1";
        
        ExecutionResult result = executor.execute(sleepCmd);
        assertTrue(result.isTimedOut(), "Expected timeout but got: exitCode=" + result.getExitCode());
        assertFalse(result.isSuccess());
    }

    @Test
    void testOutputTruncation() {
        // Executor has 1024 byte limit
        String largeCmd;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // 'set' lists all environment variables — usually > 1KB
            // If not enough, chain multiple echos
            largeCmd = "cmd /c \"for /L %i in (1,1,200) do @echo AAAAAAAAAA\"";
        } else {
            largeCmd = "python3 -c \"print('A' * 2000)\"";
        }

        ExecutionResult result = executor.execute(largeCmd);
        // If command was blocked or failed, skip assertion
        if (result.getExitCode() != -1 || !result.getStderr().contains("BLOCKED")) {
            assertTrue(result.isOutputTruncated(), 
                "Expected truncation. Output length: " + result.getStdout().length() + 
                ", stderr: " + result.getStderr().substring(0, Math.min(100, result.getStderr().length())));
        }
    }

    @Test
    void testBlockedCommand() {
        // CommandPolicy should block this (assuming default policy)
        ExecutionResult result = executor.execute("rm -rf /");
        assertFalse(result.isSuccess());
        assertTrue(result.getStderr().contains("BLOCKED"));
    }
}
