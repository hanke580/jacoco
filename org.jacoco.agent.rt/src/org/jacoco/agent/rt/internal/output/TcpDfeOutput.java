/*******************************************************************************
 * Copyright (c) 2009, 2022 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.output;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.jacoco.agent.rt.internal.IExceptionLogger;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.RuntimeData;

/**
 * Output that connects to a TCP port. This controller uses the following agent
 * options:
 * <ul>
 * <li>address</li>
 * <li>port</li>
 * </ul>
 */
public class TcpDfeOutput implements IAgentOutput {

	private final IExceptionLogger logger;

	private TcpConnection connection;

	private Thread worker;

	/**
	 * New controller instance.
	 *
	 * @param logger
	 *            logger to use in case of exceptions is spawned threads
	 */
	public TcpDfeOutput(final IExceptionLogger logger) {
		this.logger = logger;
	}

	@Override
	public void startup(final AgentOptions options, final RuntimeData data)
			throws IOException {
		final Socket socket = createSocket(options.getAddress(),
				options.getPort());
		connection = new TcpConnection(socket, data);
		connection.init();
		connection.writeSessionInfo();
		worker = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					connection.run();
				} catch (final IOException e) {
					logger.logExeption(e);
				}
			}
		});
		worker.setName(getClass().getName());
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	public void shutdown() throws Exception {
		connection.close();
		worker.join();
	}

	@Override
	public void writeExecutionData(final boolean reset) throws IOException {
		connection.writeExecutionData(reset);
	}

	/**
	 * Open a socket based on the given configuration.
	 *
	 * @param options
	 *            address and port configuration
	 * @return opened socket
	 * @throws IOException
	 */
	protected Socket createSocket(String address, int port) throws IOException {
		return new Socket(address, port);
	}

}
