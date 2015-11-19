package com.davfx.ninio.http.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.davfx.ninio.http.HttpQueryPath;
import com.davfx.ninio.http.HttpRequest;
import com.davfx.ninio.http.util.HttpController.Http;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class FileHttpServiceHandler implements HttpServiceHandler {
	
	private static final Config CONFIG = ConfigFactory.load(FileHttpServiceHandler.class.getClassLoader());
	private static final int BUFFER_SIZE = CONFIG.getBytes("ninio.http.file.buffer").intValue();
	
	private static final ImmutableMap<String, String> DEFAULT_CONTENT_TYPES;
	static {
		ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
		for (Config c : CONFIG.getConfigList("ninio.http.file.contentTypes")) {
			b.put(c.getString("extension").toLowerCase(), c.getString("contentType"));
		}
		DEFAULT_CONTENT_TYPES = b.build();
	}
	
	private final File dir;
	private final String index;
	private final ImmutableList<String> path;
	
	public FileHttpServiceHandler(File dir, String index, HttpQueryPath path) {
		this.dir = dir;
		this.index = index;
		this.path = path.path;
	}
	
	@Override
	public Http handle(HttpRequest request, HttpPost post) throws Exception {
		List<String> l = new LinkedList<>();
		Iterator<String> i = path.iterator();
		for (String s : request.path.path.path) {
			if (!i.hasNext()) {
				i = null;
			}
			if (i == null) {
				l.add(s);
			} else {
				String n = i.next();
				if (!s.equals(n)) {
					return Http.notFound();
				}
			}
		}
		
		final File file = new File(dir, l.isEmpty() ? index : (File.separatorChar + Joiner.on(File.separatorChar).join(l)));

		if (file.isFile()) {
			String name = file.getName();
			String contentType = null;
			for (Map.Entry<String, String> e : DEFAULT_CONTENT_TYPES.entrySet()) {
				if (name.toLowerCase().endsWith(e.getKey())) {
					contentType = e.getValue();
					break;
				}
			}

			// "Cache-Control", "private, max-age=0, no-cache"

			return Http.ok().contentType(contentType).contentLength(file.length()).stream(new HttpController.HttpStream() {
				@Override
				public void produce(OutputStream out) throws Exception {
					try (InputStream in = new FileInputStream(file)) {
						while (true) {
							byte[] b = new byte[BUFFER_SIZE];
							int l = in.read(b);
							if (l < 0) {
								break;
							}
							out.write(b, 0, l);
						}
					}
				}
			});
		} else {
			return Http.notFound();
		}
	}
}