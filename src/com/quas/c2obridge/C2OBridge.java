package com.quas.c2obridge;

import com.quas.c2obridge.strategy.*;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IdleManager;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test class to try out different methods of keeping a permanent connection.
 *
 * Created by Quasar on 17/12/2015.
 */
public class C2OBridge {

	/**
	 * Debug mode = read all emails currently in the inbox and treat as new emails
	 * Non-debug mode = if there are any emails in inbox upon program start, terminate with warning message
	 */
	private static final boolean DEBUG_MODE;

	/** Oanda API details */
	public static final String OANDA_API_KEY;
	public static final String OANDA_API_URL;
	/** Account IDs for respective strategies */
	private static final int COPY_ACC_ID;
	private static final int REVERSE_ACC_ID;
	private static final int SMART_COPY_ACC_ID;

	/** Email address without the @gmail.com domain */
	public static final String EMAIL;
	/** Email password */
	public static final String PASSWORD;

	// load from props file
	static {
		// application settings
		Properties settings = new Properties();
		try {
			settings.load(new FileInputStream(new File("settings.properties")));
		} catch (IOException ioe) {
			System.err.println("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(System.err);
			System.exit(0);
		}
		DEBUG_MODE = Boolean.parseBoolean(settings.getProperty("DEBUG_MODE"));

		// gmail credentials
		Properties gmailProps = new Properties();
		try {
			gmailProps.load(new FileInputStream(new File("gmail.properties")));
		} catch (IOException ioe) {
			System.err.println("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(System.err);
			System.exit(0);
		}
		EMAIL = gmailProps.getProperty("GMAIL_ACC");
		PASSWORD = gmailProps.getProperty("GMAIL_PASS");

		// oanda credentials
		Properties oandaProps = new Properties();
		try {
			oandaProps.load(new FileInputStream(new File("oanda.properties")));
		} catch (IOException ioe) {
			System.err.println("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(System.err);
			System.exit(0);
		}
		OANDA_API_KEY = oandaProps.getProperty("API_KEY");
		OANDA_API_URL = oandaProps.getProperty("API_URL");
		COPY_ACC_ID = Integer.parseInt(oandaProps.getProperty("COPY_ACC_ID"));
		REVERSE_ACC_ID = Integer.parseInt(oandaProps.getProperty("REVERSE_ACC_ID"));
		SMART_COPY_ACC_ID = Integer.parseInt(oandaProps.getProperty("SMART_COPY_ACC_ID"));
	}

	/** MessageHandler implementation */
	private List<StrategyHandler> strategyHandlers;

	/**
	 * Constructs a new C2OBridge main application instance and initialises all the strategy handlers.
	 */
	public C2OBridge() {
		// initialise the applicable strategies and their account ids
		this.strategyHandlers = new ArrayList<StrategyHandler>();
		// exact copy strategy
		strategyHandlers.add(new CopyStrategyHandler(COPY_ACC_ID));
		// smart copy strategy
		strategyHandlers.add(new SmartCopyStrategyHandler(SMART_COPY_ACC_ID));
		// reverse strategy - disabled
		// strategyHandlers.add(new ReverseStrategyHandler(REVERSE_ACC_ID));
	}

	/**
	 * Alerts all strategies of shutdown.
	 */
	public void shutdown() {
		for (StrategyHandler strategy : strategyHandlers) {
			strategy.shutdown();
		}
	}

	public static void main(String[] args) {
		// create instance of app
		final C2OBridge app = new C2OBridge();
		// initialise command line handler thread
		new Thread(new CommandLineHandler(app)).start();

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
				Store store;
				try {
					store = session.getStore("imaps");
				} catch (NoSuchProviderException ne) {
					System.err.println("No such error exception wtf? " + ne);
					ne.printStackTrace(System.err);
					return;
				}
				store.connect("smtp.gmail.com", EMAIL + "@gmail.com", PASSWORD);

				// open up inbox
				IMAPFolder inbox = (IMAPFolder) store.getFolder("inbox");
				inbox.open(Folder.READ_WRITE);

				// go through and check all emails
				System.out.println("Checking current messages in inbox:");
				Message[] messages = inbox.getMessages();
				if (messages.length > 0) {
					if (!DEBUG_MODE) {
						System.out.println("There are undeleted messages in the inbox. Terminating...");
						System.exit(0);
						return;
					} else {
						// go through all the messages
						for (Message m : messages) {
							System.out.println("Title: " + m.getSubject());
						}
					}
				}
				if (DEBUG_MODE) {
					// this is where it ends for debug mode
					System.out.println("Was run in debug mode, terminating...");
					System.exit(0);
					return;
				}

				// setup keep-alive thread
				Thread keepAliveThread = new Thread(new KeepAlive(inbox));
				keepAliveThread.start();
				System.out.println("Started running nested-class keep-alive thread.");

				try {
					ExecutorService es = Executors.newCachedThreadPool();
					final IdleManager idleManager = new IdleManager(session, es);

					// watch inbox for new emails
					inbox.addMessageCountListener(new MessageCountAdapter() {

						public void messagesAdded(MessageCountEvent ev) {

							Folder folder = (Folder) ev.getSource();
							Message[] messages = ev.getMessages();

							// process each message
							try {
								for (Message message : messages) {
									System.out.println("\n[" + getCurrentTime() + "] Received a message with title = " + message.getSubject());
									for (StrategyHandler strategy : app.strategyHandlers) {
										strategy.handleMessage(message);
										sleep(500); // sleep for half sec between strategies to not exceed limit for Oanda API calls
									}
								}
							} catch (MessagingException me) {
								System.err.println("Error in ConnectionTest->main->messagesAdded(): " + me);
								me.printStackTrace(System.err);
								System.exit(1);
							} catch (IOException ioe) {
								System.err.println("There was an issue in I/O when trying to do transaction: " + ioe);
								ioe.printStackTrace(System.err);
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
						System.out.println("KeepAliveThread joined.");
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

	public static String getCurrentTime() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
		return sdf.format(cal.getTime());
	}

	public static String getCurrentMinute() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat("mm");
		return sdf.format(cal.getTime());
	}

	public static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ie) {
			// ignore
		}
	}
}
