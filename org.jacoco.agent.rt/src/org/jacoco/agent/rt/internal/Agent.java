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
package org.jacoco.agent.rt.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.util.List;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.internal.output.FileOutput;
import org.jacoco.agent.rt.internal.output.IAgentOutput;
import org.jacoco.agent.rt.internal.output.NoneOutput;
import org.jacoco.agent.rt.internal.output.TcpClientOutput;
import org.jacoco.agent.rt.internal.output.TcpDfeOutput;
import org.jacoco.agent.rt.internal.output.TcpServerOutput;
import org.jacoco.core.JaCoCo;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.AbstractRuntime;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.AgentOptions.OutputMode;
import org.jacoco.core.runtime.RuntimeData;

/**
 * The agent manages the life cycle of JaCoCo runtime.
 */
public class Agent implements IAgent {

	private static Agent singleton;

	/**
	 * Returns a global instance which is already started. If the method is
	 * called the first time the instance is created with the given options.
	 *
	 * @param options
	 *            options to configure the instance
	 * @return global instance
	 * @throws Exception
	 *             in case something cannot be initialized
	 */
	public static synchronized Agent getInstance(final AgentOptions options)
			throws Exception {
		if (singleton == null) {
			final Agent agent = new Agent(options, IExceptionLogger.SYSTEM_ERR);
			agent.startup();
			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					agent.shutdown();
				}
			});
			singleton = agent;
		}
		return singleton;
	}

	/**
	 * Returns a global instance which is already started. If a agent has not
	 * been initialized before this method will fail.
	 *
	 * @return global instance
	 * @throws IllegalStateException
	 *             if no Agent has been started yet
	 */
	public static synchronized Agent getInstance()
			throws IllegalStateException {
		if (singleton == null) {
			throw new IllegalStateException("JaCoCo agent not started.");
		}
		return singleton;
	}

	private final AgentOptions options;

	private final IExceptionLogger logger;

	private final RuntimeData data;

	private IAgentOutput output;

	private Callable<Void> jmxRegistration;

	/**
	 * Creates a new agent with the given agent options.
	 *
	 * @param options
	 *            agent options
	 * @param logger
	 *            logger used by this agent
	 */
	Agent(final AgentOptions options, final IExceptionLogger logger) {
		this.options = options;
		this.logger = logger;
		this.data = new RuntimeData();
	}

	/**
	 * Returns the runtime data object created by this agent
	 *
	 * @return runtime data for this agent instance
	 */
	public RuntimeData getData() {
		return data;
	}

	/**
	 * get the main class name of running jvm
	 *
	 * @return Main Class in string
	 */
	public static String getMainClassName() {
		String sunJavaCommand = System.getProperty("sun.java.command");
		if (sunJavaCommand != null) {
			return sunJavaCommand.split(" ")[0];
		}
		return null;

		// FIXME we cannot use thread to get Main Class since in Premain method
		// the main class is not initilized yet
		// for (Entry<Thread, StackTraceElement[]> entry : Thread
		// .getAllStackTraces().entrySet()) {
		// Thread thread = entry.getKey();
		// System.out.println("\n\n\n" + thread.getThreadGroup().getName());
		// if (thread.getThreadGroup() != null
		// && thread.getThreadGroup().getName().equals("main")) {
		// for (StackTraceElement stackTraceElement : entry.getValue()) {
		// System.out.println(stackTraceElement.getClassName() + " "
		// + stackTraceElement.getMethodName() + " "
		// + stackTraceElement.getFileName() + "\n\n");
		// if (stackTraceElement.getMethodName().equals("main")) {

		// try {
		// Class<?> c = Class
		// .forName(stackTraceElement.getClassName());
		// Class[] argTypes = new Class[] { String[].class };
		// // This will throw NoSuchMethodException in case of
		// // fake main methods
		// c.getDeclaredMethod("main", argTypes);
		// System.out.println("FIND MAIN CLASS: "
		// + stackTraceElement.getClassName());
		// return stackTraceElement.getClassName();
		// } catch (Exception e) {
		// e.printStackTrace();
		// return null;
		// }
		// }
		// }
		// }
		// }
		// return null;
	}

	/**
	 * Initializes this agent.
	 *
	 * @throws Exception
	 *             in case something cannot be initialized
	 */
	public void startup() throws Exception {
		try {
			String sessionId = options.getSessionId();
			if (sessionId == null) {
				sessionId = createSessionId();
			} else {
				sessionId = sessionId + "-" + getMainClassName();
			}
			data.setSessionId(sessionId);
			output = createAgentOutput();
			output.startup(options, data);
			if (options.getJmx()) {
				jmxRegistration = new JmxRegistration(this);
			}
		} catch (final Exception e) {
			logger.logExeption(e);
			throw e;
		}
	}

	/**
	 * Shutdown the agent again.
	 */
	public void shutdown() {
		try {
			if (options.getDumpOnExit()) {
				output.writeExecutionData(false);
			}
			output.shutdown();
			if (jmxRegistration != null) {
				jmxRegistration.call();
			}
		} catch (final Exception e) {
			logger.logExeption(e);
		}
	}

	/**
	 * Create output implementation as given by the agent options.
	 *
	 * @return configured controller implementation
	 */
	IAgentOutput createAgentOutput() {
		final OutputMode controllerType = options.getOutput();
		switch (controllerType) {
		case file:
			return new FileOutput();
		case tcpserver:
			return new TcpServerOutput(logger);
		case tcpclient:
			return new TcpClientOutput(logger);
		case dfe:
			return new TcpDfeOutput(logger);
		case none:
			return new NoneOutput();
		default:
			throw new AssertionError(controllerType);
		}
	}

	private String createSessionId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (final Exception e) {
			// Also catch platform specific exceptions (like on Android) to
			// avoid bailing out here
			host = "unknownhost";
		}
		return host + "-" + AbstractRuntime.createRandomId();
	}

	// === IAgent Implementation ===

	public String getVersion() {
		return JaCoCo.VERSION;
	}

	public String getSessionId() {
		return data.getSessionId();
	}

	public void setSessionId(final String id) {
		data.setSessionId(id);
	}

	public void reset() {
		data.reset();
	}

	public byte[] getExecutionData(final boolean reset) {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			final ExecutionDataWriter writer = new ExecutionDataWriter(buffer);
			data.collect(writer, writer, reset);
		} catch (final IOException e) {
			// Must not happen with ByteArrayOutputStream
			throw new AssertionError(e);
		}
		return buffer.toByteArray();
	}

	public void dump(final boolean reset) throws IOException {
		output.writeExecutionData(reset);
	}

}
