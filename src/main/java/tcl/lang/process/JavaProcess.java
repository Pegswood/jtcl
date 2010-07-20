package tcl.lang.process;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

import tcl.lang.ManagedSystemInStream;
import tcl.lang.TclByteArray;
import tcl.lang.TclException;
import tcl.lang.TclIO;
import tcl.lang.TclObject;

/**
 * Implements a pure Java process. Combines java.lang.ProcessBuilder and
 * java.lang.Process. Has the disadvantage of possibly sucking up too much
 * stdin, and cannot outlive the JVM if it has any PIPE or CHANNEL or INHERIT
 * redirects. When Java 1.7 is available, this class should be re-written to do
 * a proper INHERIT redirect, instead of using the pipe model of Java 1.5, to
 * avoid sucking up too much stdin.
 * 
 * @author danb
 * 
 */
public class JavaProcess extends TclProcess {
	/**
	 * The Java Process object
	 */
	protected Process process = null;
	/**
	 * The Java ProcessBuilder object
	 */
	protected ProcessBuilder processBuilder = new ProcessBuilder();
	/**
	 * The source of bytes for this process's stdin
	 */
	protected InputStream stdinStream = null;
	/**
	 * The sink for this process's stdout
	 */
	protected OutputStream stdoutStream = null;
	/**
	 * The sink for this process's stderr
	 */
	protected OutputStream stderrStream = null;

	/**
	 * Coupler that reads this process's stdout
	 */
	protected Coupler stdoutCoupler = null;
	/**
	 * Coupler that reads this process's stderr
	 */
	protected Coupler stderrCoupler = null;

	/**
	 * @return this process's stream to write stdin data to
	 */
	public OutputStream getOutputStream() {
		return process.getOutputStream();
	}

	/**
	 * @return this process's stream to read stdout from
	 */
	public InputStream getInputStream() {
		return process.getInputStream();
	}

	/**
	 * @return this process's stream to read stderr from
	 */
	public InputStream getErrorStream() {
		return process.getErrorStream();
	}

	@Override
	public int exitValue() throws IllegalThreadStateException {
		if (process == null)
			throw new IllegalThreadStateException("Process not yet started");
		return process.exitValue();
	}

	@Override
	public void start() throws IOException {
		processBuilder.command(command);
		if (stderrRedirect != null && stderrRedirect.type == Redirect.Type.MERGE_ERROR) {
			processBuilder.redirectErrorStream(true);
		}
		process = processBuilder.start();

		/*
		 * Connect the process's stdin
		 */
		if (stdinRedirect != null) {
			switch (stdinRedirect.type) {
			case FILE:
				stdinStream = new BufferedInputStream(new FileInputStream(stdinRedirect.file));
				break;
			case PIPE:
				JavaProcess upstream = (JavaProcess) stdinRedirect.pipePartner;
				stdinStream = upstream.getInputStream();
				break;
			case INHERIT:
				stdinStream = new ManagedSystemInStream();
				break;
			case STREAM:
				stdinStream = stdinRedirect.istream;
				break;
			case TCL_CHANNEL:
				// wrap channel in an inputStream
				stdinStream = new InputStream() {
					TclObject tclbuf = TclByteArray.newInstance();

					@Override
					public int read() throws IOException {
						try {
							if (stdinRedirect.channel.eof())
								return -1;
							int cnt = stdinRedirect.channel.read(interp, tclbuf, TclIO.READ_N_BYTES, 1);
							if (cnt > 0)
								return TclByteArray.getBytes(interp, tclbuf)[0];
							else
								return -1;
						} catch (TclException e) {
							throw new IOException(e.getMessage());
						}

					}

				};
				break;
			}
		}
		if (stdinStream != null) {
			Thread coupler = new Coupler(stdinStream, process.getOutputStream(), true, true);
			coupler.start();
		} else {
			// just close the output stream of the process, since it won't get any output anyway
			process.getOutputStream().close();
		}

		/*
		 * Connect process's stdout
		 */
		if (stdoutRedirect != null) {
			switch (stdoutRedirect.type) {
			case FILE:
				stdoutStream = new BufferedOutputStream(new FileOutputStream(stdoutRedirect.file,
						stdoutRedirect.appendToFile));
				break;
			case INHERIT:
				stdoutStream = new FileOutputStream(FileDescriptor.out);
				break;
			case STREAM:
				stdoutStream = stdoutRedirect.ostream;
				break;
			case TCL_CHANNEL:
				stdoutStream = new OutputStream() {
					byte[] buf = new byte[1];
					TclObject tclbuf;

					@Override
					public void write(int b) throws IOException {
						buf[0] = (byte) (b & 0xFF);
						tclbuf = TclByteArray.newInstance(buf);
						try {
							stdoutRedirect.channel.write(interp, tclbuf);
						} catch (TclException e) {
							throw new IOException(e.getMessage());
						}
					}

					/*
					 * (non-Javadoc)
					 * 
					 * @see java.io.OutputStream#flush()
					 */
					@Override
					public void flush() throws IOException {
						super.flush();
						try {
							stdoutRedirect.channel.flush(interp);
						} catch (TclException e) {
							throw new IOException(e.getMessage());
						}
					}

				};
				break;
			}

		}
		if (stdoutStream != null) {
			// don't close inherited STDOUT; that will close the descriptor and
			// TCL won't be
			// able to write
			stdoutCoupler = new Coupler(process.getInputStream(), stdoutStream,
					stdoutRedirect.type != Redirect.Type.INHERIT, stdoutRedirect.type == Redirect.Type.INHERIT);
			stdoutCoupler.start();
		}
		/*
		 * Connect process's stderr
		 */
		if (stderrRedirect != null && stderrRedirect.type != Redirect.Type.MERGE_ERROR) {
			switch (stderrRedirect.type) {
			case FILE:
				stderrStream = new BufferedOutputStream(new FileOutputStream(stderrRedirect.file,
						stderrRedirect.appendToFile));
				break;
			case INHERIT:
				stderrStream = new FileOutputStream(FileDescriptor.err);
				break;
			case STREAM:
				stdoutStream = stdoutRedirect.ostream;
				break;
			case TCL_CHANNEL:
				stderrStream = new OutputStream() {
					byte[] buf = new byte[1];
					TclObject tclbuf;

					@Override
					public void write(int b) throws IOException {
						buf[0] = (byte) (b & 0xFF);
						tclbuf = TclByteArray.newInstance(buf);
						try {
							stderrRedirect.channel.write(interp, tclbuf);
						} catch (TclException e) {
							throw new IOException(e.getMessage());
						}
					}

					/*
					 * (non-Javadoc)
					 * 
					 * @see java.io.OutputStream#flush()
					 */
					@Override
					public void flush() throws IOException {
						super.flush();
						try {
							stderrRedirect.channel.flush(interp);
						} catch (TclException e) {
							throw new IOException(e.getMessage());
						}
					}

				};
				break;
			}
		}
		if (stderrStream != null) {
			stderrCoupler = new Coupler(process.getErrorStream(), stderrStream,
					stderrRedirect.type != Redirect.Type.INHERIT, true);
			stderrCoupler.start();
		}

	}

	@Override
	protected int _waitFor() throws InterruptedException, IOException {
		if (process == null)
			throw new IllegalThreadStateException("Process not yet started");
		int rv = process.waitFor();
		if (stdinRedirect != null && stdinRedirect.type == Redirect.Type.INHERIT && stdinStream != null)
			stdinStream.close(); // so we don't keep sucking up stdin
		// wait for couplers to finish
		if (stdoutCoupler != null) {
			stdoutCoupler.join();
		}
		// wait for couplers to finish

		if (stderrCoupler != null) {
			stderrCoupler.join();
		}
		return rv;
	}

	@Override
	public int getPid() throws IllegalThreadStateException {
		/*
		 * Get the PID through reflection to get private fields in at least
		 * Sun's implementation of the java.lang.Process subclass
		 */
		if (process == null)
			throw new IllegalThreadStateException("Process not yet started");
		final String[] pidFields = { "pid", "handle" };

		for (String pidField : pidFields) {
			try {
				Field f = process.getClass().getDeclaredField(pidField);
				f.setAccessible(true);
				return f.getInt(process);
			} catch (Exception ee) {
				// do nothing
			}
		}
		return -1; // couldn't get pid through reflection
	}

	@Override
	public boolean isStarted() {
		return (process != null);
	}

	@Override
	public void setWorkingDir(File workingDir) {
		processBuilder.directory(workingDir);
	}

	@Override
	public void destroy() {
		if (stdinRedirect!= null && stdinRedirect.type == Redirect.Type.INHERIT) {
			try {
				stdinStream.close();
			} catch (IOException e) {
				// do nothing
			}
		}
		process.destroy();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see tcl.lang.process.TclProcess#canInheritFileDescriptors()
	 */
	@Override
	public boolean canInheritFileDescriptors() {
		return false;
	}

	/**
	 * Stuff data into process's stdin, or get data out of stdout/stderr
	 * 
	 * @author danb
	 * 
	 */
	private class Coupler extends Thread {
		InputStream in = null;
		OutputStream out = null;
		boolean closeOut;
		boolean flushOut;

		public Coupler(InputStream in, OutputStream out, boolean closeOut, boolean flushOut) {
			this.in = in;
			this.out = out;
			this.closeOut = closeOut;
			this.flushOut = flushOut;
		}

		@Override
		public void run() {
			while (true) {
				int b = -1;
				try {
					b = in.read();
					if (b == -1) {
						if (closeOut)
							out.close();
						break;
					}
					out.write(b);
					if (flushOut)
						out.flush();

				} catch (IOException e) {
					saveIOException(e);
					try {
						if (closeOut)
							out.close();
					} catch (IOException e1) {
						// do nothing
					}
					try {
						in.close();
					} catch (IOException e1) {
						// do nothing
					}
					break;
				}
			}
		}

	}

}