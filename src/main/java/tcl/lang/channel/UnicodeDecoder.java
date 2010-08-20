package tcl.lang.channel;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * This class implements Unicode decoding for Tcl input Channels. It is similar
 * to an InputStreamReader, but can report on its internal buffering, and
 * handles Tcl's "binary" encoding.
 * 
 * @author Dan Bodoh
 * 
 */
class UnicodeDecoder extends Reader {
	/**
	 * The underlying InputStream that is decoded
	 */
	MarkableInputStream in = null;
	/**
	 * The unicode decoder, or null if using binary encoding
	 */
	CharsetDecoder csd = null;
	/**
	 * The Java string name for the charset, or null for binary encoding
	 */
	String encoding = null;

	/**
	 * Set to true when the end of file is seen
	 */
	boolean eofSeen = false;

	/**
	 * Create a new UnicodeDecoder with the specified encoding
	 * 
	 * @param in
	 *            the underlying InputStream (as an InputBuffer) that will be
	 *            decoded
	 * @param encoding
	 *            a Java encoding string name, or null for no encoding
	 * @throws UnsupportedEncodingException
	 */
	UnicodeDecoder(MarkableInputStream in, String encoding) {
		super();
		this.in = in;
		setEncoding(encoding, true);
	}

	/**
	 * Set a new encoding for this channel. The current encoding state will not
	 * be destroyed if the value of 'encoding' is the same as its previous value
	 * 
	 * @param encoding
	 *            a Java encoding string name, or null for no encoding
	 */
	void setEncoding(String encoding)   {
		setEncoding(encoding, false);
	}

	/**
	 * Set a new encoding for this channel
	 * 
	 * @param encoding
	 *            a Java encoding string name, or null for no encoding
	 * @param force
	 *            if true,, an existing encoder will be destroyed even if it has
	 *            the same 'encoding' value. If false, an existing encoder will
	 *            not be destroyed if the encoding is not changed.
	 */
	private void setEncoding(String encoding, boolean force)   {
		if (!force) {
			if (encoding == null && this.encoding == null)
				return;
			if (encoding != null && this.encoding != null && this.encoding.equals(encoding))
				return;
		}
		if (encoding != null) {
			Charset cs = Charset.forName(encoding);
			csd = cs.newDecoder();
			csd.onMalformedInput(CodingErrorAction.REPLACE);
			csd.onUnmappableCharacter(CodingErrorAction.REPLACE);
		} else {
			csd = null;
		}
	}

	/**
	 * Reset any internal state after a seek was performed
	 */
	void seekReset() {
		if (csd != null) {
			csd.reset();
		}
		this.eofSeen = false;
	}

	/**
	 * Sets the current eof state to false
	 * 
	 * @param eof
	 *            End of file indicator is set to this state
	 */
	void cancelEof() {
		this.eofSeen = false;
		if (csd != null)
			csd.reset();
	}

	/**
	 * Take a look at the next byte in the stream, bypassing the unicode decoder.
	 * Used to look for the \n or a \r\n sequence.
	 * 
	 * @param consume
	 *            if true, consume the next byte in the input stream
	 * 
	 * @return next byte (not the Unicode character!) from underlying input
	 *         stream, possibly consuming it. Returns -1 on eof
	 * 
	 * @throws IOException
	 */
	int peek(boolean consume) throws IOException {
		if (eofSeen)
			return -1;
		int c = in.read();
		if (!consume)
			in.unread(c);
		/*
		 * Don't set eofSeen in peek, because we have to call csd.decode() one
		 * last time
		 */
		return c;
	}

	/**
	 * @return number of bytes (not chars!) that can be consumed without
	 *         blocking.
	 */
	public int available() throws IOException {
		return in.available();
	}

	@Override
	public void close() throws IOException {
		in.close();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.io.Reader#mark(int)
	 */
	@Override
	public void mark(int readAheadLimit) throws IOException {
		in.mark(readAheadLimit);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.io.Reader#markSupported()
	 */
	@Override
	public boolean markSupported() {
		return in.markSupported();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.io.Reader#reset()
	 */
	@Override
	public void reset() throws IOException {
		in.reset();
		if (csd != null)
			csd.reset();
	}

	/**
	 * Flush the decoder, and return number of new characters or -1 if none
	 * 
	 * @param cb
	 *            character buffer to receive characters
	 * @param origPos
	 *            position in character buffer at start of decoding operation
	 * @return number of characters in cb, or -1 if none
	 */
	private int flushDecoder(CharBuffer cb, int origPos) {
		CoderResult result = csd.flush(cb);
		int cnt = cb.position() - origPos;
		if (result == CoderResult.OVERFLOW) {
			return cnt;
		} else {
			if (cnt == 0)
				return -1;
			else
				return cnt;
		}
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		if (csd != null) {
			/*
			 * Use a raw Charset decoder, instead of something easier like an
			 * InputStreamReader, to prevent reading ahead from the underlying
			 * stream. InputStreamReader will read ahead and not report the
			 * number of unconsumed bytes, which breaks Channel.tell() and
			 * Channel.seek()
			 */

			CharBuffer cb = CharBuffer.wrap(cbuf, off, len);

			/* If there was an eof on the last call, we might just have to flush */
			if (eofSeen) {
				return flushDecoder(cb, off);
			}

			/*
			 * Use all the leftover bytes from the last read call. Possibly add
			 * some bytes if len is too small, to fit at least one char worth of
			 * bytes
			 */
			int byteArraySize = len < 16 ? 16 : len;
			byte[] byteArray = new byte[byteArraySize];
			ByteBuffer bb = ByteBuffer.wrap(byteArray);

			boolean zeroLengthRead = false;

			/* We want at least one character added to char buffer */
			while (cb.position() - off == 0 && len > 0 && !zeroLengthRead) {
				/* And read bytes from the underlying stream */
				if (bb.remaining() > 0 && !eofSeen) {
					/*
					 * try to read the number of bytes equal to number of
					 * remaining chars we want to decode
					 */
					int cnt = in.read(byteArray, bb.position(), cb.remaining() > bb.remaining() ? bb.remaining() : cb
							.remaining());
					if (cnt == -1)
						eofSeen = true;
					else {
						bb.position(bb.position() + cnt);
					}
					zeroLengthRead = (cnt == 0);
				}
				bb.flip(); // start reading from bb

				/* decode the bytes into characters */
				CoderResult result = csd.decode(bb, cb, eofSeen);

				if (result == CoderResult.UNDERFLOW && eofSeen) {
					return flushDecoder(cb, off);
				}
				bb.compact();
			}
			// return any unconsumed bytes in bb back to the input stream
			bb.flip();
			while (bb.remaining() > 0) {
				eofSeen = false;
				in.unread(bb.get());
			}
			return cb.position() - off;
		} else {

			/*
			 * otherwise, do a binary encoding, which means just translate bytes
			 * to chars
			 */

			byte[] bbuf = new byte[len];
			int cnt = in.read(bbuf, 0, len);
			if (cnt == -1) {
				eofSeen = true;
				return -1;
			}
			for (int i = 0; i < cnt; i++) {
				cbuf[i + off] = (char) (bbuf[i] & 0xff);
			}
			return cnt;
		}
	}
}