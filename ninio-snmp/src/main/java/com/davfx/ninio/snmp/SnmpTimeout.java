package com.davfx.ninio.snmp;

import java.io.IOException;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.Timeout;

public final class SnmpTimeout {
	private SnmpTimeout() {
	}
	
	public static SnmpConnecter.Connecting wrap(final Timeout t, final double timeout, final SnmpConnecter.Connecting wrappee) {
		return new SnmpConnecter.Connecting() {
			@Override
			public void close() {
				wrappee.close(); // Does not close the timeout managers because it would be to complicated (specific executor or synchronization issues)
			}
			
			@Override
			public Cancelable get(Address address, String community, AuthRemoteSpecification authRemoteSpecification, Oid oid, final Callback receiver) {
				final Timeout.Manager m = t.set(timeout);
				m.run(new Runnable() {
					@Override
					public void run() {
						receiver.failed(new IOException("Timeout"));
					}
				});

				final Cancelable cancelable = wrappee.get(address, community, authRemoteSpecification, oid, new SnmpConnecter.Connecting.Callback() {
					@Override
					public void received(SnmpResult result) {
						m.reset();
						receiver.received(result);
					}
					
					@Override
					public void finished() {
						m.cancel();
						receiver.finished();
					}
					
					@Override
					public void failed(IOException ioe) {
						m.cancel();
						receiver.failed(ioe);
						
					}
				});
				
				return new Cancelable() {
					@Override
					public void cancel() {
						m.cancel();
						cancelable.cancel();
					}
				};
			}
		};
	}
}
