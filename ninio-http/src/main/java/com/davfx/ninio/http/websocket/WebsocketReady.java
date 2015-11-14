package com.davfx.ninio.http.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.CloseableByteBufferHandler;
import com.davfx.ninio.core.FailableCloseableByteBufferHandler;
import com.davfx.ninio.core.Ready;
import com.davfx.ninio.core.ReadyConnection;
import com.davfx.ninio.http.HttpClient;
import com.davfx.ninio.http.HttpClientHandler;
import com.davfx.ninio.http.HttpMethod;
import com.davfx.ninio.http.HttpPath;
import com.davfx.ninio.http.HttpRequest;
import com.davfx.ninio.http.HttpResponse;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.io.BaseEncoding;

public final class WebsocketReady implements Ready {
	private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketReady.class);

	private final HttpClient httpClient;
	private final SecureRandom random = new SecureRandom();

	public WebsocketReady(HttpClient httpClient) {
		this.httpClient = httpClient;
	}
	
	@Override
	public void connect(Address address, final ReadyConnection connection) {
		HttpRequest request = new HttpRequest(address, false, HttpMethod.GET, new HttpPath("/"), ImmutableMultimap.<String, String>builder()
			.put("Sec-WebSocket-Key", BaseEncoding.base64().encode(String.valueOf(random.nextLong()).getBytes(Charsets.UTF_8)))
			.put("Sec-WebSocket-Version", "13")
			.put("Connection", "Upgrade")
			.put("Upgrade", "websocket")
			.build()
		);

		httpClient.send(request, new HttpClientHandler() {
			private WebsocketFrameReader websocketFrameReader;
			@Override
			public void failed(IOException e) {
				LOGGER.debug("Failed from peer", e);
				websocketFrameReader = null;
				connection.failed(e);
			}
			@Override
			public void close() {
				LOGGER.debug("Closed by peer");
				websocketFrameReader = null;
				connection.close();
			}
			
			@Override
			public void handle(Address address, ByteBuffer buffer) {
				if (websocketFrameReader == null) {
					return;
				}
				websocketFrameReader.handle(address, buffer);
			}
			
			@Override
			public void received(HttpResponse response) {
				LOGGER.debug("Response received: {} {}", response.status, response.reason);
				// We should check everything here (status code, header Sec-WebSocket-Accept, ...)
			}
			
			@Override
			public void ready(final CloseableByteBufferHandler write) {
				websocketFrameReader = new WebsocketFrameReader(new WebsocketFrameReader.Handler() {
					@Override
					public void failed(IOException e) {
						LOGGER.debug("Failed", e);
						connection.failed(e);
					}
					@Override
					public void handle(int opcode, long frameLength, ByteBuffer partialBuffer) {
						if (opcode == 0x02) {
							LOGGER.debug("Received with opcode {}", opcode);
							connection.handle(null, partialBuffer);
							return;
						}

						if (opcode == 0x08) {
							LOGGER.debug("Connection closed");
							connection.close();
							return;
						}
						connection.failed(new IOException("Current implementation does not handle this opcode: " + opcode));
					}
				});
				connection.connected(new FailableCloseableByteBufferHandler() {
					@Override
					public void close() {
						write.handle(null, WebsocketFrameReader.headerOf(true, 0x08, null));
					}
					@Override
					public void failed(IOException e) {
						close();
					}
					@Override
					public void handle(Address address, ByteBuffer buffer) {
						write.handle(null, WebsocketFrameReader.headerOf(true, 0x02, buffer));
						write.handle(null, buffer);
					}
				});
			}
		});
	}
}
