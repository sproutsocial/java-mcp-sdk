/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerResponse.SseBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Server-side implementation of the Model Context Protocol (MCP) streamable transport
 * layer using HTTP with Server-Sent Events (SSE) through Spring WebMVC. This
 * implementation provides a bridge between synchronous WebMVC operations and reactive
 * programming patterns to maintain compatibility with the reactive transport interface.
 *
 * <p>
 * This is the non-reactive version of
 * {@link io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider}
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpStreamableServerTransportProvider
 * @see RouterFunction
 */
public class WebMvcStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebMvcStreamableServerTransportProvider.class);

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Default base URL for the message endpoint.
	 */
	public static final String DEFAULT_BASE_URL = "";

	/**
	 * The endpoint URI where clients should send their JSON-RPC messages. Defaults to
	 * "/mcp".
	 */
	private final String mcpEndpoint;

	/**
	 * Flag indicating whether DELETE requests are disallowed on the endpoint.
	 */
	private final boolean disallowDelete;

	private final ObjectMapper objectMapper;

	private final RouterFunction<ServerResponse> routerFunction;

	private McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	private McpTransportContextExtractor<ServerRequest> contextExtractor;

	// private Function<ServerRequest, McpTransportContext> contextExtractor = req -> new
	// DefaultMcpTransportContext();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Constructs a new WebMvcStreamableServerTransportProvider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param baseUrl The base URL for the message endpoint, used to construct the full
	 * endpoint URL for clients.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP. This endpoint will handle GET, POST, and DELETE requests.
	 * @param disallowDelete Whether to disallow DELETE requests on the endpoint.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private WebMvcStreamableServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint,
			boolean disallowDelete, McpTransportContextExtractor<ServerRequest> contextExtractor,
			Duration keepAliveInterval) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "McpTransportContextExtractor must not be null");

		this.objectMapper = objectMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.contextExtractor = contextExtractor;
		this.routerFunction = RouterFunctions.route()
			.GET(this.mcpEndpoint, this::handleGet)
			.POST(this.mcpEndpoint, this::handlePost)
			.DELETE(this.mcpEndpoint, this::handleDelete)
			.build();

		if (keepAliveInterval != null) {
			this.keepAliveScheduler = KeepAliveScheduler
				.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(this.sessions.values()))
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.build();

			this.keepAliveScheduler.start();
		}
		else {
			logger.warn("Keep-alive interval is not set or invalid. No keep-alive will be scheduled.");
		}
	}

	@Override
	public String protocolVersion() {
		return "2025-03-26";
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients through their SSE connections.
	 * If any errors occur during sending to a particular client, they are logged but
	 * don't prevent sending to other clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (this.sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", this.sessions.size());

		return Mono.fromRunnable(() -> {
			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.sendNotification(method, params).block();
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
				}
			});
		});
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", this.sessions.size());

			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.closeGracefully().block();
				}
				catch (Exception e) {
					logger.error("Failed to close session {}: {}", session.getId(), e.getMessage());
				}
			});

			this.sessions.clear();
			logger.debug("Graceful shutdown completed");
		}).then().doOnSuccess(v -> {
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Returns the RouterFunction that defines the HTTP endpoints for this transport. The
	 * router function handles three endpoints:
	 * <ul>
	 * <li>GET [mcpEndpoint] - For establishing SSE connections and message replay</li>
	 * <li>POST [mcpEndpoint] - For receiving JSON-RPC messages from clients</li>
	 * <li>DELETE [mcpEndpoint] - For session deletion (if enabled)</li>
	 * </ul>
	 * @return The configured RouterFunction for handling HTTP requests
	 */
	public RouterFunction<ServerResponse> getRouterFunction() {
		return this.routerFunction;
	}

	/**
	 * Setup the listening SSE connections and message replay.
	 * @param request The incoming server request
	 * @return A ServerResponse configured for SSE communication, or an error response
	 */
	private ServerResponse handleGet(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		List<MediaType> acceptHeaders = request.headers().asHttpHeaders().getAccept();
		if (!acceptHeaders.contains(MediaType.TEXT_EVENT_STREAM)) {
			return ServerResponse.badRequest().body("Invalid Accept header. Expected TEXT_EVENT_STREAM");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		if (!request.headers().asHttpHeaders().containsKey(HttpHeaders.MCP_SESSION_ID)) {
			return ServerResponse.badRequest().body("Session ID required in mcp-session-id header");
		}

		String sessionId = request.headers().asHttpHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			return ServerResponse.notFound().build();
		}

		logger.debug("Handling GET request for session: {}", sessionId);

		try {
			return ServerResponse.sse(sseBuilder -> {
				sseBuilder.onTimeout(() -> {
					logger.debug("SSE connection timed out for session: {}", sessionId);
				});

				WebMvcStreamableMcpSessionTransport sessionTransport = new WebMvcStreamableMcpSessionTransport(
						sessionId, sseBuilder);

				// Check if this is a replay request
				if (request.headers().asHttpHeaders().containsKey(HttpHeaders.LAST_EVENT_ID)) {
					String lastId = request.headers().asHttpHeaders().getFirst(HttpHeaders.LAST_EVENT_ID);

					try {
						session.replay(lastId)
							.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
							.toIterable()
							.forEach(message -> {
								try {
									sessionTransport.sendMessage(message)
										.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
										.block();
								}
								catch (Exception e) {
									logger.error("Failed to replay message: {}", e.getMessage());
									sseBuilder.error(e);
								}
							});
					}
					catch (Exception e) {
						logger.error("Failed to replay messages: {}", e.getMessage());
						sseBuilder.error(e);
					}
				}
				else {
					// Establish new listening stream
					McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
						.listeningStream(sessionTransport);

					sseBuilder.onComplete(() -> {
						logger.debug("SSE connection completed for session: {}", sessionId);
						listeningStream.close();
					});
				}
			}, Duration.ZERO);
		}
		catch (Exception e) {
			logger.error("Failed to handle GET request for session {}: {}", sessionId, e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * Handles POST requests for incoming JSON-RPC messages from clients.
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A ServerResponse indicating success or appropriate error status
	 */
	private ServerResponse handlePost(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		List<MediaType> acceptHeaders = request.headers().asHttpHeaders().getAccept();
		if (!acceptHeaders.contains(MediaType.TEXT_EVENT_STREAM)
				|| !acceptHeaders.contains(MediaType.APPLICATION_JSON)) {
			return ServerResponse.badRequest()
				.body(new McpError("Invalid Accept headers. Expected TEXT_EVENT_STREAM and APPLICATION_JSON"));
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		try {
			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Handle initialization request
			if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
					&& jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
				McpSchema.InitializeRequest initializeRequest = objectMapper.convertValue(jsonrpcRequest.params(),
						new TypeReference<McpSchema.InitializeRequest>() {
						});
				McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
					.startSession(initializeRequest);
				this.sessions.put(init.session().getId(), init.session());

				try {
					McpSchema.InitializeResult initResult = init.initResult().block();

					return ServerResponse.ok()
						.contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.MCP_SESSION_ID, init.session().getId())
						.body(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), initResult,
								null));
				}
				catch (Exception e) {
					logger.error("Failed to initialize session: {}", e.getMessage());
					return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
				}
			}

			// Handle other messages that require a session
			if (!request.headers().asHttpHeaders().containsKey(HttpHeaders.MCP_SESSION_ID)) {
				return ServerResponse.badRequest().body(new McpError("Session ID missing"));
			}

			String sessionId = request.headers().asHttpHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return ServerResponse.status(HttpStatus.NOT_FOUND)
					.body(new McpError("Session not found: " + sessionId));
			}

			if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
				session.accept(jsonrpcResponse)
					.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
					.block();
				return ServerResponse.accepted().build();
			}
			else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
				session.accept(jsonrpcNotification)
					.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
					.block();
				return ServerResponse.accepted().build();
			}
			else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
				// For streaming responses, we need to return SSE
				return ServerResponse.sse(sseBuilder -> {
					sseBuilder.onComplete(() -> {
						logger.debug("Request response stream completed for session: {}", sessionId);
					});
					sseBuilder.onTimeout(() -> {
						logger.debug("Request response stream timed out for session: {}", sessionId);
					});

					WebMvcStreamableMcpSessionTransport sessionTransport = new WebMvcStreamableMcpSessionTransport(
							sessionId, sseBuilder);

					try {
						session.responseStream(jsonrpcRequest, sessionTransport)
							.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
							.block();
					}
					catch (Exception e) {
						logger.error("Failed to handle request stream: {}", e.getMessage());
						sseBuilder.error(e);
					}
				}, Duration.ZERO);
			}
			else {
				return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new McpError("Unknown message type"));
			}
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Handles DELETE requests for session deletion.
	 * @param request The incoming server request
	 * @return A ServerResponse indicating success or appropriate error status
	 */
	private ServerResponse handleDelete(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		if (this.disallowDelete) {
			return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		if (!request.headers().asHttpHeaders().containsKey(HttpHeaders.MCP_SESSION_ID)) {
			return ServerResponse.badRequest().body("Session ID required in mcp-session-id header");
		}

		String sessionId = request.headers().asHttpHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			return ServerResponse.notFound().build();
		}

		try {
			session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
			this.sessions.remove(sessionId);
			return ServerResponse.ok().build();
		}
		catch (Exception e) {
			logger.error("Failed to delete session {}: {}", sessionId, e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Implementation of McpStreamableServerTransport for WebMVC SSE sessions. This class
	 * handles the transport-level communication for a specific client session.
	 *
	 * <p>
	 * This class is thread-safe and uses a ReentrantLock to synchronize access to the
	 * underlying SSE builder to prevent race conditions when multiple threads attempt to
	 * send messages concurrently.
	 */
	private class WebMvcStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final String sessionId;

		private final SseBuilder sseBuilder;

		private final ReentrantLock lock = new ReentrantLock();

		private volatile boolean closed = false;

		/**
		 * Creates a new session transport with the specified ID and SSE builder.
		 * @param sessionId The unique identifier for this session
		 * @param sseBuilder The SSE builder for sending server events to the client
		 */
		WebMvcStreamableMcpSessionTransport(String sessionId, SseBuilder sseBuilder) {
			this.sessionId = sessionId;
			this.sseBuilder = sseBuilder;
			logger.debug("Streamable session transport {} initialized with SSE builder", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection with a
		 * specific message ID.
		 * @param message The JSON-RPC message to send
		 * @param messageId The message ID for SSE event identification
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromRunnable(() -> {
				if (this.closed) {
					logger.debug("Attempted to send message to closed session: {}", this.sessionId);
					return;
				}

				this.lock.lock();
				try {
					if (this.closed) {
						logger.debug("Session {} was closed during message send attempt", this.sessionId);
						return;
					}

					String jsonText = objectMapper.writeValueAsString(message);
					this.sseBuilder.id(messageId != null ? messageId : this.sessionId)
						.event(MESSAGE_EVENT_TYPE)
						.data(jsonText);
					logger.debug("Message sent to session {} with ID {}", this.sessionId, messageId);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", this.sessionId, e.getMessage());
					try {
						this.sseBuilder.error(e);
					}
					catch (Exception errorException) {
						logger.error("Failed to send error to SSE builder for session {}: {}", this.sessionId,
								errorException.getMessage());
					}
				}
				finally {
					this.lock.unlock();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured ObjectMapper.
		 * @param data The source data object to convert
		 * @param typeRef The target type reference
		 * @return The converted object of type T
		 * @param <T> The target type
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				WebMvcStreamableMcpSessionTransport.this.close();
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			this.lock.lock();
			try {
				if (this.closed) {
					logger.debug("Session transport {} already closed", this.sessionId);
					return;
				}

				this.closed = true;

				this.sseBuilder.complete();
				logger.debug("Successfully completed SSE builder for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
			}
			finally {
				this.lock.unlock();
			}
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebMvcStreamableServerTransportProvider}.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private String mcpEndpoint = "/mcp";

		private boolean disallowDelete = false;

		private McpTransportContextExtractor<ServerRequest> contextExtractor = (serverRequest, context) -> context;

		private Duration keepAliveInterval;

		/**
		 * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param objectMapper The ObjectMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if objectMapper is null
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param mcpEndpoint The MCP endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if mcpEndpoint is null
		 */
		public Builder mcpEndpoint(String mcpEndpoint) {
			Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
			this.mcpEndpoint = mcpEndpoint;
			return this;
		}

		/**
		 * Sets whether to disallow DELETE requests on the endpoint.
		 * @param disallowDelete true to disallow DELETE requests, false otherwise
		 * @return this builder instance
		 */
		public Builder disallowDelete(boolean disallowDelete) {
			this.disallowDelete = disallowDelete;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<ServerRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "contextExtractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets the keep-alive interval for the transport. If set, a keep-alive scheduler
		 * will be created to periodically check and send keep-alive messages to clients.
		 * @param keepAliveInterval The interval duration for keep-alive messages, or null
		 * to disable keep-alive
		 * @return this builder instance
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebMvcStreamableServerTransportProvider} with
		 * the configured settings.
		 * @return A new WebMvcStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebMvcStreamableServerTransportProvider build() {
			Assert.notNull(this.objectMapper, "ObjectMapper must be set");
			Assert.notNull(this.mcpEndpoint, "MCP endpoint must be set");

			return new WebMvcStreamableServerTransportProvider(this.objectMapper, this.mcpEndpoint, this.disallowDelete,
					this.contextExtractor, this.keepAliveInterval);
		}

	}

}
