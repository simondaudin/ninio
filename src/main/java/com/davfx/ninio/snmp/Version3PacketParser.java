package com.davfx.ninio.snmp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Version3PacketParser {

	private static final Logger LOGGER = LoggerFactory.getLogger(Version3PacketParser.class);
	
	private static final Oid AUTH_ERROR_OID_PREFIX = new Oid("1.3.6.1.6.3.15.1.1");
	private static final Oid AUTH_ERROR_UNKNOWN_ENGINE_ID_OID = new Oid("1.3.6.1.6.3.15.1.1.4.0");
	private static final Oid AUTH_ERROR_NOT_IN_TIME_WINDOW_OID = new Oid("1.3.6.1.6.3.15.1.1.2.0");
	
	private final int requestId;
	private final int errorStatus;
	private final int errorIndex;
	private final List<Result> results = new LinkedList<Result>();

	public Version3PacketParser(AuthRemoteEngine authEngine, ByteBuffer buffer) throws IOException {
		BerReader ber = new BerReader(buffer);
		ber.beginReadSequence();
		{
			int version = ber.readInteger();
			if (version != BerConstants.VERSION_3) {
				throw new IOException("Invalid version: " + version + " should be " + BerConstants.VERSION_3);
			}
			
			byte securityFlags;

			ber.beginReadSequence();
			{
				ber.readInteger(); // Packet number
				ber.readInteger(); // Max packet size
				securityFlags = ber.readBytes().get();
				int securityModel = ber.readInteger();
				if (securityModel != BerConstants.VERSION_3_USM_SECURITY_MODEL) {
					throw new IOException("Invalid security model: " + securityModel + " should be " + BerConstants.VERSION_3_USM_SECURITY_MODEL);
				}
			}
			ber.endReadSequence();

			int previousEngineTime = authEngine.getTime();
			int previousEngineBootCount = authEngine.getBootCount();
			
			BerReader secBer = new BerReader(ber.readBytes());
			secBer.beginReadSequence();
			{
				ByteBuffer engine = secBer.readBytes();
				byte[] id = new byte[engine.remaining()];
				engine.get(id);
				authEngine.setId(id);
				authEngine.setBootCount(secBer.readInteger());
				authEngine.resetTime(secBer.readInteger());
				String login = BerPacketUtils.string(secBer.readBytes());
				if (!login.equals(authEngine.getAuthLogin())) {
					throw new IOException("Bad login: " + login + " should be: " + authEngine.getAuthLogin());
				}
				secBer.readBytes();
				ByteBuffer decryptParams = secBer.readBytes();
				if (decryptParams.hasRemaining()) {
					byte[] dp = new byte[decryptParams.remaining()];
					decryptParams.get(dp);
					authEngine.setEncryptionParameters(dp);
				}
			}
			secBer.endReadSequence();

			BerReader pdu;
			if ((securityFlags & BerConstants.VERSION_3_PRIV_FLAG) != 0) {
				pdu = new BerReader(authEngine.decrypt(ber.readBytes()));
			} else {
				pdu = ber;
			}

			pdu.beginReadSequence();
			pdu.readBytes();
			pdu.readBytes();
			
			int requestId;
			int errorStatus;
			int errorIndex;

			int s = pdu.beginReadSequence();
			if (s == BerConstants.REPORT) {
				requestId = pdu.readInteger();
				errorStatus = pdu.readInteger();
				errorIndex = pdu.readInteger();

				pdu.beginReadSequence();
				{
					while (pdu.hasRemainingInSequence()) {
						pdu.beginReadSequence();
						{
							Oid oid = pdu.readOid();
							String value = pdu.readValue();
							LOGGER.trace("<- {} = {}", oid, value);
							if (!authEngine.isReady() && AUTH_ERROR_UNKNOWN_ENGINE_ID_OID.isPrefix(oid)) {
								LOGGER.trace("Engine not known ({}), requestId = {}", oid, requestId);
								errorStatus = BerConstants.ERROR_STATUS_RETRY;
								errorIndex = 0;
								requestId = Integer.MAX_VALUE;
							} else if (authEngine.isReady() && (previousEngineTime == 0) && (previousEngineBootCount == 0) && (authEngine.getTime() > 0) && (authEngine.getBootCount() > 0) && AUTH_ERROR_NOT_IN_TIME_WINDOW_OID.isPrefix(oid)) {
								LOGGER.trace("Engine not synced ({}), requestId = {}", oid, requestId);
								errorStatus = BerConstants.ERROR_STATUS_RETRY;
								errorIndex = 0;
								requestId = Integer.MAX_VALUE;
							} else if (AUTH_ERROR_OID_PREFIX.isPrefix(oid)) {
								LOGGER.error("Authentication failed ({}), requestId = {}", oid, requestId);
								errorStatus = BerConstants.ERROR_STATUS_AUTHENTICATION_FAILED;
								errorIndex = 0;
								requestId = Integer.MAX_VALUE;
							}
							// OidValue value = pdu.readOidValue();
							results.add(new Result(oid, value));
						}
						pdu.endReadSequence();
					}
				}
				pdu.endReadSequence();
			} else {
				if (s != BerConstants.RESPONSE) {
					throw new IOException("Not a response packet");
				}
				requestId = pdu.readInteger();
				errorStatus = pdu.readInteger();
				errorIndex = pdu.readInteger();

				pdu.beginReadSequence();
				{
					while (pdu.hasRemainingInSequence()) {
						pdu.beginReadSequence();
						{
							Oid oid = pdu.readOid();
							String value = pdu.readValue();
							// OidValue value = pdu.readOidValue();
							results.add(new Result(oid, value));
						}
						pdu.endReadSequence();
					}
				}
				pdu.endReadSequence();
			}
			pdu.endReadSequence();

			this.requestId = requestId;
			this.errorStatus = errorStatus;
			this.errorIndex = errorIndex;
		}
		ber.endReadSequence();
		
		authEngine.setReady();
	}

	public Iterable<Result> getResults() {
		return results;
	}

	public int getRequestId() {
		return requestId;
	}

	public int getErrorStatus() {
		return errorStatus;
	}

	public int getErrorIndex() {
		return errorIndex;
	}
}
