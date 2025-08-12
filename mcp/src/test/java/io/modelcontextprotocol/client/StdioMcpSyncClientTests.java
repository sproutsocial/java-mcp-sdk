/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link McpSyncClient} with {@link StdioClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncClientTests extends AbstractMcpSyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		ServerParameters stdioParams;
        String currentPath = System.getenv("PATH");
        String nodePath = System.getProperty("user.dir") + "/node";
        String newPath = nodePath + (currentPath != null ? System.getProperty("path.separator") + currentPath : "");

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            stdioParams = ServerParameters.builder("./node/npx.cmd")
                .args("-y", "@modelcontextprotocol/server-everything", "stdio")
                .addEnvVar("PATH", newPath)
				.build();
		}
		else {
            stdioParams = ServerParameters.builder("./node/npx")
                .args("-y", "@modelcontextprotocol/server-everything", "stdio")
                .addEnvVar("PATH", newPath)
                .build();
		}
		return new StdioClientTransport(stdioParams);
	}

	@Test
	void customErrorHandlerShouldReceiveErrors() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> receivedError = new AtomicReference<>();

		McpClientTransport transport = createMcpTransport();
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		((StdioClientTransport) transport).setStdErrorHandler(error -> {
			receivedError.set(error);
			latch.countDown();
		});

		String errorMessage = "Test error";
		((StdioClientTransport) transport).getErrorSink().emitNext(errorMessage, Sinks.EmitFailureHandler.FAIL_FAST);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(receivedError.get()).isNotNull().isEqualTo(errorMessage);

		StepVerifier.create(transport.closeGracefully()).expectComplete().verify(Duration.ofSeconds(5));
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(10);
	}

}
