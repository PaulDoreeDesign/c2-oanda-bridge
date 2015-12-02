package com.quas.c2obridge;

import com.sun.mail.iap.ProtocolException;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.protocol.IMAPProtocol;

import javax.mail.Folder;
import javax.mail.MessagingException;

/**
 * Runnable used to keep alive the connection to the IMAP server
 *
 * @author Juan Martín Sotuyo Dodero <jmsotuyo@monits.com>
 */
public class MailKeepAlive implements Runnable {

	private static final long KEEP_ALIVE_FREQ = 60000; // every minute

	private final IMAPFolder folder;

	public MailKeepAlive(IMAPFolder folder) {
		this.folder = folder;
	}

	@Override
	public void run() {
		while (!Thread.interrupted()) {
			try {
				Thread.sleep(KEEP_ALIVE_FREQ);

				// Perform a NOOP just to keep alive the connection
				System.out.print(Main.getCurrentMinute() + " ");
				folder.doCommand(new IMAPFolder.ProtocolCommand() {
					public Object doCommand(IMAPProtocol p) {
						try {
							p.simpleCommand("NOOP", null);
						} catch (ProtocolException pe) {
							System.err.println("Encountered error while keeping alive and sending NOOP: " + pe);
							pe.printStackTrace(System.err);
						}
						return null;
					}
				});
			} catch (InterruptedException e) {
				// Ignore, just aborting the thread...
			} catch (MessagingException e) {
				// Shouldn't really happen...
				System.err.println("Unexpected exception while keeping alive the IDLE connection: " + e);
				e.printStackTrace(System.err);
			}
		}
	}
}