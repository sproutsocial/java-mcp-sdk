package io.modelcontextprotocol.server;

import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation for {@link McpTransportContext} which uses a Thread-safe map.
 * Objects of this kind are mutable.
 *
 * @author Dariusz Jędrzejczyk
 */
public class DefaultMcpTransportContext implements McpTransportContext {

	private final Map<String, Object> storage;

	/**
	 * Create an empty instance.
	 */
	public DefaultMcpTransportContext() {
		this.storage = new ConcurrentHashMap<>();
	}

	DefaultMcpTransportContext(Map<String, Object> storage) {
		this.storage = storage;
	}

	@Override
	public Object get(String key) {
		return this.storage.get(key);
	}

	@Override
	public void put(String key, Object value) {
		this.storage.put(key, value);
	}

	/**
	 * Allows copying the contents.
	 * @return new instance with the copy of the underlying map
	 */
	public McpTransportContext copy() {
		return new DefaultMcpTransportContext(new ConcurrentHashMap<>(this.storage));
	}

	// TODO for debugging

	@Override
	public String toString() {
		return new StringJoiner(", ", DefaultMcpTransportContext.class.getSimpleName() + "[", "]")
			.add("storage=" + storage)
			.toString();
	}

}
