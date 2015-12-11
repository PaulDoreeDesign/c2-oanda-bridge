package com.quas.c2obridge;

import com.sun.mail.iap.ConnectionException;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.protocol.IMAPProtocol;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Store;

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
				String minute = Main.getCurrentMinute();
				int minuteInt = Integer.parseInt(minute);
				if (minuteInt % 10 == 0) {
					System.out.print("NOOP'd at " + Main.getCurrentTime() + ". ");
					if (minuteInt % 60 == 0) System.out.println();
				}
				folder.doCommand(new IMAPFolder.ProtocolCommand() {
					public Object doCommand(IMAPProtocol p) {
						try {
							p.simpleCommand("NOOP", null);
						} catch (ConnectionException ce) {
							System.err.println("Encountered ConnectionException: " + ce);
							System.err.println("Trying to reconnect...");
							System.out.println("Encountered ConnectionException, trying to reconnect...");

							// stop old idle manager
							MailSync.getIdleManager().stop();
							// shutdown old executor service
							MailSync.shutdownExecutorService();
							// create new connection
							boolean connected = false;
							Store store = MailSync.getStore();
							while (!connected) {
								try {
									System.err.println("Trying to close store before opening connection again:");
									// disconnect old store
									store.close();
									System.err.println("Closed old store, trying to open connection:");
									// connect store again
									store.connect("smtp.gmail.com", Main.EMAIL + "@gmail.com", Main.PASSWORD);
									connected = true;
								} catch (MessagingException me) {
									// sleep and try again
									try {
										Thread.sleep(1000);
									} catch (InterruptedException ie) {
										// do nothing
									}
								}
							}

							System.err.println("Managed to connect to store... continuing reconnect process:");

							try {
								// open inbox
								IMAPFolder folder = (IMAPFolder) store.getFolder("inbox");

								// set new inbox
								MailSync.setInbox(folder);

								// set listener on the new inbox
								C2OBridge.listen(folder);

								System.err.println("Reconnected successfully!");
								System.out.println("Reconnected successfully!");

							} catch (MessagingException me) {
								System.err.println("INBOX DOESN'T EXIST, WHAT? " + me);
								me.printStackTrace(System.err);
								System.exit(1);
							}
						} catch (ProtocolException pe) {
							System.err.println("Encountered unknown error while keeping alive and sending NOOP: " + pe);
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