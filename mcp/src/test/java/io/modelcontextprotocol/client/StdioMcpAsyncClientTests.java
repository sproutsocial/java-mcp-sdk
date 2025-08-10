/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the {@link McpAsyncClient} with {@link StdioClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		ServerParameters stdioParams;
		String currentPath = System.getenv("PATH");
		String nodePath = System.getProperty("user.dir") + "/node";
		String newPath = nodePath + (currentPath != null ? System.getProperty("path.separator") + currentPath : "");
		System.out.println("✅Using PATH: " + newPath);

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

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(20);
	}

}
