package com.xksystem.logistics.upms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OfflineCommandTest {
    @Test
    void initializationAndRecoveryRefuseNonInteractiveProcessesBeforeStartingTheApplication() throws Exception {
        for (String command : new String[] {"init", "recover"}) {
            var process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "com.xksystem.logistics.LogisticsApplication", "--upms.command=" + command)
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            boolean ended = process.waitFor(20, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            assertThat(ended).as("Non-interactive account command exits promptly").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isNotZero();
            assertThat(output).contains("require a human interactive terminal").doesNotContain("Started LogisticsApplication");
        }
    }
}
