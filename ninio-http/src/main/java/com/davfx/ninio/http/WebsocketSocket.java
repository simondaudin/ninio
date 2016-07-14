package com.davfx.ninio.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.Buffering;
import com.davfx.ninio.core.ByteBufferAllocator;
import com.davfx.ninio.core.Closing;
import com.davfx.ninio.core.Connecting;
import com.davfx.ninio.core.Connector;
import com.davfx.ninio.core.Disconnectable;
import com.davfx.ninio.core.Failing;
import com.davfx.ninio.core.Queue;
import com.davfx.ninio.core.Receiver;
import com.davfx.ninio.core.TcpSocket;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.io.BaseEncoding;

public final class WebsocketSocket implements Connector {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketSocket.class);

	public static interface Builder extends TcpSocket.Builder {
		Builder to(Address connectAddress);
		Builder with(HttpClient httpClient);
		Builder route(String path);
	}

	public static Builder builder() {
		return new Builder() {
			private HttpClient httpClient = null;
			private String path = String.valueOf(HttpSpecification.PATH_SEPARATOR);
			
			private Address connectAddress = null;
			
			private Connecting connecting = null;
			private Closing closing = null;
			private Failing failing = null;
			private Receiver receiver = null;
			private Buffering buffering = null;
			
			@Override
			public TcpSocket.Builder with(ByteBufferAllocator byteBufferAllocator) {
				return this;
			}
			
			@Override
			public TcpSocket.Builder bind(Address bindAddress) {
				return this;
			}
			
			@Override
			public Builder closing(Closing closing) {
				this.closing = closing;
				return this;
			}
		
			@Override
			public Builder connecting(Connecting connecting) {
				this.connecting = connecting;
				return this;
			}
			
			@Override
			public Builder failing(Failing failing) {
				this.failing = failing;
				return this;
			}
			
			@Override
			public Builder receiving(Receiver receiver) {
				this.receiver = receiver;
				return this;
			}
			
			@Override
			public Builder buffering(Buffering buffering) {
				this.buffering = buffering;
				return this;
			}
			
			@Override
			public Builder to(Address connectAddress) {
				this.connectAddress = connectAddress;
				return this;
			}
			
			@Override
			public Builder with(HttpClient httpClient) {
				this.httpClient = httpClient;
				return this;
			}
			
			@Override
			public Builder route(String path) {
				this.path = path;
				return this;
			}
			
			@Override
			public Connector create(Queue queue) {
				if (httpClient == null) {
					throw new NullPointerException("httpClient");
				}
				return new WebsocketSocket(httpClient, path, connectAddress, connecting, closing, failing, receiver, buffering);
			}
		};
	}

	private static final SecureRandom RANDOM = new SecureRandom();
	
	private final HttpContentSender sender;
	
	private WebsocketSocket(HttpClient httpClient, String path, final Address connectAddress, final Connecting connecting, final Closing closing, final Failing failing, final Receiver receiver, final Buffering buffering) {
		HttpRequest request = new HttpRequest(connectAddress, false, HttpMethod.GET, path, ImmutableMultimap.<String, String>builder()
			.put("Sec-WebSocket-Key", BaseEncoding.base64().encode(String.valueOf(RANDOM.nextLong()).getBytes(Charsets.UTF_8)))
			.put("Sec-WebSocket-Version", "13")
			.put("Connection", "Upgrade")
			.put("Upgrade", "websocket")
			.build()
		);

		if (connecting != null) {
			connecting.connected(this, connectAddress);
		}
		
		sender = httpClient.request()
			.failing(failing)
			.buffering(buffering)
			.receiving(new HttpReceiver() {
				private boolean opcodeRead = false;
				private int currentOpcode;
				private boolean lenRead = false;
				private boolean mustReadExtendedLen16;
				private boolean mustReadExtendedLen64;
				private long currentLen;
				private long currentRead;
				private boolean mustReadMask;
				private ByteBuffer currentExtendedLenBuffer;
				private byte[] currentMask;
				private ByteBuffer currentMaskBuffer;
				private int currentPosInMask;
				
				private long toPing = 0L;

				@Override
				public HttpContentReceiver received(Disconnectable disconnectable, HttpResponse response) {
					// We should check everything here (status code, header Sec-WebSocket-Accept, ...)
					if (response.status != 101) {
						if (failing != null) {
							failing.failed(new IOException("Could not connect to " + connectAddress + " [" + response.status + " " + response.reason + "]"));
						}
						return null;
					}
					
					return new HttpContentReceiver() {
						@Override
						public void received(ByteBuffer buffer) {
							while (buffer.hasRemaining()) {
								if (!opcodeRead && buffer.hasRemaining()) {
									int v = buffer.get() & 0xFF;
									if ((v & 0x80) != 0x80) {
										LOGGER.error("Current implementation handles only FIN packets");
										sender.cancel();
										if (closing != null) {
											closing.closed();
										}
										return;
									}
									currentOpcode = v & 0x0F;
									opcodeRead = true;
								}
						
								if (!lenRead && buffer.hasRemaining()) {
									int v = buffer.get() & 0xFF;
									int len = v & 0x7F;
									if (len <= 125) {
										currentLen = len;
										mustReadExtendedLen16 = false;
										mustReadExtendedLen64 = false;
									} else if (len == 126) {
										mustReadExtendedLen16 = true;
										mustReadExtendedLen64 = false;
										currentExtendedLenBuffer = ByteBuffer.allocate(2);
									} else {
										mustReadExtendedLen64 = true;
										mustReadExtendedLen16 = false;
										currentExtendedLenBuffer = ByteBuffer.allocate(8);
									}
									mustReadMask = ((v & 0x80) == 0x80);
									if (mustReadMask) {
										currentMask = new byte[4];
										currentMaskBuffer = ByteBuffer.wrap(currentMask);
										currentPosInMask = 0;
									}
									lenRead = true;
								}
								
								while (mustReadExtendedLen16 && buffer.hasRemaining()) {
									int v = buffer.get();
									currentExtendedLenBuffer.put((byte) v);
									if (currentExtendedLenBuffer.position() == 2) {
										currentExtendedLenBuffer.flip();
										currentLen = currentExtendedLenBuffer.getShort() & 0xFFFF;
										mustReadExtendedLen16 = false;
										currentExtendedLenBuffer = null;
									}
								}
								while (mustReadExtendedLen64 && buffer.hasRemaining()) {
									int v = buffer.get();
									currentExtendedLenBuffer.put((byte) v);
									if (currentExtendedLenBuffer.position() == 8) {
										currentExtendedLenBuffer.flip();
										currentLen = currentExtendedLenBuffer.getLong();
										mustReadExtendedLen64 = false;
										currentExtendedLenBuffer = null;
									}
								}
								while (mustReadMask && buffer.hasRemaining()) {
									int v = buffer.get();
									currentMaskBuffer.put((byte) v);
									if (currentMaskBuffer.position() == 4) {
										currentMaskBuffer = null;
										mustReadMask = false;
									}
								}
								
								if (opcodeRead && lenRead && !mustReadExtendedLen16 && !mustReadExtendedLen64 && !mustReadMask && buffer.hasRemaining() && (currentRead < currentLen)) {
									ByteBuffer partialBuffer;
									int len = (int) Math.min(buffer.remaining(), currentLen - currentRead);
									if (currentMask == null) {
										partialBuffer = buffer.duplicate();
										partialBuffer.limit(partialBuffer.position() + len);
										buffer.position(buffer.position() + len);
										currentRead += len;
									} else {
										partialBuffer = ByteBuffer.allocate(len);
										while (buffer.hasRemaining() && (currentRead < currentLen)) {
											int v = buffer.get() & 0xFF;
											v ^= currentMask[currentPosInMask];
											partialBuffer.put((byte) v);
											currentRead++;
											currentPosInMask = (currentPosInMask + 1) % 4;
										}
										partialBuffer.flip();
									}
									int opcode = currentOpcode;
									long frameLength = currentLen;

									if (currentRead == currentLen) {
										opcodeRead = false;
										lenRead = false;
										mustReadExtendedLen16 = false;
										mustReadExtendedLen64 = false;
										currentExtendedLenBuffer = null;
										mustReadMask = false;
										currentMaskBuffer = null;
										currentMask = null;
										currentRead = 0L;
									}
									
									if (opcode == 0x09) {
										if (toPing == 0L) {
											toPing = frameLength;
											sender.send(WebsocketUtils.headerOf(0x0A, frameLength));
										}

										toPing -= partialBuffer.remaining();
										sender.send(partialBuffer);
									} else if ((opcode == 0x01) || (opcode == 0x02)) {
										if (receiver != null) {
											receiver.received(WebsocketSocket.this, null, partialBuffer);
										}
									} else if (opcode == 0x08) {
										LOGGER.debug("Connection closed by peer");
										sender.cancel();
										if (closing != null) {
											closing.closed();
										}
										break;
									}
								}
							}
						}
						
						@Override
						public void ended() {
							LOGGER.debug("Connection abruptly closed by peer");
							sender.cancel();
							if (closing != null) {
								closing.closed();
							}
						}
					};
				}
			})
			.build(request);
	}

	@Override
	public void close() {
		LOGGER.trace("Close requested");
		sender.send(WebsocketUtils.headerOf(0x08, 0L));
	}
	
	@Override
	public Connector send(Address address, ByteBuffer buffer) {
		sender.send(WebsocketUtils.headerOf(0x02, buffer.remaining()));
		sender.send(buffer);
		return this;
	}
}