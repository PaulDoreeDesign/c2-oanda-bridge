package com.quas.c2obridge;


import com.sun.mail.iap.ConnectionException;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.protocol.IMAPProtocol;

import javax.mail.MessagingException;

public class KeepAlive implements Runnable {

	private IMAPFolder folder;

	private volatile boolean running;

	public KeepAlive(IMAPFolder folder) {
		this.folder = folder;

		this.running = true;
	}

	@Override
	public void run() {
		while (running) {
			try {
				Thread.sleep(60000); // once a minute

				// Perform a NOOP just to keep alive the connection
				String minute = C2OBridge.getCurrentMinute();
				int minuteInt = Integer.parseInt(minute);
				if (minuteInt % 60 == 0) {
					Logger.out.println("\n-----------------------\n| NEW HOUR (" + C2OBridge.getCurrentTime() + ") |\n-----------------------");
					System.out.println("\n-----------------------\n| NEW HOUR (" + C2OBridge.getCurrentTime() + ") |\n-----------------------"); // special case
				} else {
					Logger.out.print(minute + " ");
					System.out.print(minute + " "); // special case
				}
				folder.doCommand(new IMAPFolder.ProtocolCommand() {
					public Object doCommand(IMAPProtocol p) {
						try {
							p.simpleCommand("NOOP", null);
						} catch (ConnectionException ce) {
							Logger.error("Encountered ConnectionException: " + ce);
							Logger.error("Shutting down thread.");
							running = false;
						} catch (ProtocolException pe) {
							Logger.error("Encountered ProtocolException: " + pe);
							Logger.error("Shutting down application completely...");
							C2OBridge.crash();
						}
						return null;
					}
				});
			} catch (InterruptedException e) {
				// Ignore, just aborting the thread...
				Logger.info("Keep alive thread was interrupted, shouldn't be a biggie");
			} catch (MessagingException e) {
				// Shouldn't really happen...
				Logger.error("Unexpected exception while keeping alive the IDLE connection: " + e);
				e.printStackTrace(Logger.err);
				Logger.error("Shutting down application completely...");
				C2OBridge.crash();
			}
		}
		Logger.info("Join should occur right now:");
	}
}