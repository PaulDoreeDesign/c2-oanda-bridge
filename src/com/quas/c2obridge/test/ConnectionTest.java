package com.quas.c2obridge.test;

import com.quas.c2obridge.*;
import com.sun.mail.iap.ConnectionException;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IdleManager;
import com.sun.mail.imap.protocol.IMAPProtocol;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test class to try out different methods of keeping a permanent connection.
 *
 * Created by Quasar on 17/12/2015.
 */
public class ConnectionTest {

	public static void main(String[] args) {
		try {
			for (int i = 0; i < 100; i++) {
				System.out.println("[ATTEMPT #" + (i + 1) + "] Connecting to mail server:");
				// load gmail mailserver properties and connect to it
				Properties props = new Properties();
				FileInputStream fis = new FileInputStream(new File("smtp.properties"));
				props.load(fis);
				// close file input stream
				fis.close();
				props.setProperty("mail.imaps.usesocketchannels", "true");
				Session session = Session.getInstance(props, null);
				Store store = null;
				try {
					store = session.getStore("imaps");
				} catch (NoSuchProviderException ne) {
					System.err.println("No such error exception wtf? " + ne);
					ne.printStackTrace(System.err);
					return;
				}
				store.connect("smtp.gmail.com", Main.EMAIL + "@gmail.com", Main.PASSWORD);

				// open up inbox
				IMAPFolder inbox = (IMAPFolder) store.getFolder("inbox");
				inbox.open(Folder.READ_WRITE);

				// go through and check all emails
				System.out.println("Checking current messages in inbox:");
				Message[] messages = inbox.getMessages();
				for (Message message : messages) {
					System.out.println("Title: " + message.getSubject());
				}

				// setup keep-alive thread
				Thread keepAliveThread = new Thread(new KeepAliveTest(inbox));
				keepAliveThread.start();
				System.out.println("Started running nested-class keep-alive thread.");

				try {
					ExecutorService es = Executors.newCachedThreadPool();
					final IdleManager idleManager = new IdleManager(session, es);

					// watch inbox for new emails
					// folder.open(Folder.READ_WRITE);
					inbox.addMessageCountListener(new MessageCountAdapter() {

						public void messagesAdded(MessageCountEvent ev) {

							Folder folder = (Folder) ev.getSource();
							Message[] messages = ev.getMessages();

							// process each message
							try {
								for (Message message : messages) {
									System.out.println("\n[" + Main.getCurrentTime() + "] Received a message with title = " + message.getSubject());
								}
							} catch (MessagingException me) {
								System.err.println("Error in ConnectionTest->main->messagesAdded(): " + me);
								me.printStackTrace(System.err);
								System.exit(1);
							}

							try {
								idleManager.watch(folder); // keep watching for new messages
							} catch (MessagingException mex) {
								System.err.println("MessagingException caught when watching for new messages:");
								mex.printStackTrace(System.err);
								System.exit(1);
							}
						}
					});
					idleManager.watch(inbox);

					// handle input here
					System.out.println("Setup complete, listening for new emails...");

					try {
						keepAliveThread.join();
						System.out.println("KeepAliveThread joined!!");
					} catch (InterruptedException ie) {
						System.err.println("Interrupted in main but will loop anyway: " + ie);
					}

					System.out.println("Closing inbox, stopping idle manager and shutting down executor service:");
					inbox.close(false);
					idleManager.stop();
					es.shutdownNow();
					System.out.println("---------------------------\nRestarting everything now---------------------------\n");

				} catch (IOException ioe) {
					System.err.println("Error in ConnectionTest->main() (1): " + ioe);
					ioe.printStackTrace(System.err);
					System.exit(1);
				} catch (MessagingException me) {
					System.err.println("Error in ConnectionTest->main() (2): " + me);
					me.printStackTrace(System.err);
					System.exit(1);
				}
			}

		} catch (IOException ioe) {
			System.err.println("IOException occurred: " + ioe);
			ioe.printStackTrace(System.err);
		} catch (MessagingException me) {
			System.err.println("MessagingException caught: " + me);
			me.printStackTrace(System.err);
		}
	}

	private static class KeepAliveTest implements Runnable {

		private IMAPFolder folder;

		private volatile boolean running;

		private KeepAliveTest(IMAPFolder folder) {
			this.folder = folder;

			this.running = true;
		}

		@Override
		public void run() {
			while (running) {
				try {
					Thread.sleep(60000); // once a minute

					// Perform a NOOP just to keep alive the connection
					String minute = Main.getCurrentMinute();
					int minuteInt = Integer.parseInt(minute);
					if (minuteInt % 60 == 0) {
						System.out.println("---------------------------\nNEW HOUR (" + Main.getCurrentTime() + "---------------------------\n");
					} else {
						System.out.print(minute + " ");
					}
					folder.doCommand(new IMAPFolder.ProtocolCommand() {
						public Object doCommand(IMAPProtocol p) {
							try {
								p.simpleCommand("NOOP", null);
							} catch (ConnectionException ce) {
								System.err.println("Encountered ConnectionException: " + ce);
								System.err.println("Shutting down thread.");
								running = false;
							} catch (ProtocolException pe) {
								System.err.println("Encountered ProtocolException: " + pe);
								System.err.println("Shutting down application completely...");
								System.exit(1);
							}
							return null;
						}
					});
				} catch (InterruptedException e) {
					// Ignore, just aborting the thread...
					System.out.println("Keep alive thread was interrupted, shouldn't be a biggie");
				} catch (MessagingException e) {
					// Shouldn't really happen...
					System.err.println("Unexpected exception while keeping alive the IDLE connection: " + e);
					e.printStackTrace(System.err);
					System.err.println("Shutting down application completely...");
					System.exit(1);
				}
			}
			System.out.println("Join should occur right now:");
		}
	}
}
