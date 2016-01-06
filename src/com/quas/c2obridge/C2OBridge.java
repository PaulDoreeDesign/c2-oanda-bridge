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

	/** Instance of app */
	private static C2OBridge app;

	// load from props file
	static {
		// application settings
		Properties settings = new Properties();
		try {
			settings.load(new FileInputStream(new File("settings.properties")));
		} catch (IOException ioe) {
			Logger.error("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(Logger.err);
			crash();
		}
		DEBUG_MODE = Boolean.parseBoolean(settings.getProperty("DEBUG_MODE"));

		// gmail credentials
		Properties gmailProps = new Properties();
		try {
			gmailProps.load(new FileInputStream(new File("gmail.properties")));
		} catch (IOException ioe) {
			Logger.error("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(Logger.err);
			crash();
		}
		EMAIL = gmailProps.getProperty("GMAIL_ACC");
		PASSWORD = gmailProps.getProperty("GMAIL_PASS");

		// oanda credentials
		Properties oandaProps = new Properties();
		try {
			oandaProps.load(new FileInputStream(new File("oanda.properties")));
		} catch (IOException ioe) {
			Logger.error("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(Logger.err);
			crash();
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
		// reverse strategy
		strategyHandlers.add(new ReverseStrategyHandler(REVERSE_ACC_ID));
		Logger.info("Number of strategies running: " + strategyHandlers.size());
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
		app = new C2OBridge();
		// initialise command line handler thread
		new Thread(new CommandLineHandler(app)).start();

		try {
			for (int i = 1; i <= 100; i++) {
				Logger.info("[ATTEMPT #" + i + "] Connecting to mail server:");
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
					Logger.error("No such error exception wtf? " + ne);
					ne.printStackTrace(Logger.err);
					crash();
					return;
				}
				store.connect("smtp.gmail.com", EMAIL + "@gmail.com", PASSWORD);

				// open up inbox
				IMAPFolder inbox = (IMAPFolder) store.getFolder("inbox");
				inbox.open(Folder.READ_WRITE);

				// go through and check all emails
				Logger.info("Checking current messages in inbox:");
				Message[] messages = inbox.getMessages();
				if (messages.length > 0 && i == 1) { // only require empty inbox on initial load, obviously
					if (!DEBUG_MODE) {
						Logger.info("There are undeleted messages in the inbox. Terminating...");
						crash();
						return;
					} else {
						// go through all the messages and try to process them
						for (Message m : messages) {
							Logger.info("Title: " + m.getSubject());
							for (StrategyHandler strategy : app.strategyHandlers) {
								strategy.handleMessage(m);
								sleep(1000);
							}
						}
					}
				}
				if (DEBUG_MODE) {
					// this is where it ends for debug mode
					Logger.info("Was run in debug mode, terminating...");
					crash();
					return;
				}

				// setup keep-alive thread
				Thread keepAliveThread = new Thread(new KeepAlive(inbox));
				keepAliveThread.start();
				Logger.info("Started running nested-class keep-alive thread.");

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
									Logger.out.println();
									Logger.info("Received a message with title = " + message.getSubject());
									for (StrategyHandler strategy : app.strategyHandlers) {
										strategy.handleMessage(message);
										sleep(1000); // sleep 1 sec between strategies to not exceed limit for Oanda API calls
									}
								}
							} catch (MessagingException me) {
								Logger.error("Error in ConnectionTest->main->messagesAdded(): " + me);
								me.printStackTrace(Logger.err);
								crash();
							} catch (IOException ioe) {
								Logger.error("There was an issue in I/O when trying to do transaction: " + ioe);
								ioe.printStackTrace(Logger.err);
							}

							try {
								idleManager.watch(folder); // keep watching for new messages
							} catch (MessagingException mex) {
								Logger.error("MessagingException caught when watching for new messages:");
								mex.printStackTrace(Logger.err);
								crash();
							}
						}
					});
					idleManager.watch(inbox);

					// handle input here
					Logger.info("Setup complete, listening for new emails...");

					try {
						keepAliveThread.join();
						Logger.info("KeepAliveThread joined.");
					} catch (InterruptedException ie) {
						Logger.error("Interrupted in main but will loop anyway: " + ie);
					}

					Logger.info("Closing inbox, stopping idle manager and shutting down executor service:");
					inbox.close(false);
					idleManager.stop();
					es.shutdownNow();
					Logger.info("Restarting everything now");

				} catch (IOException ioe) {
					Logger.error("Error in ConnectionTest->main() (1): " + ioe);
					ioe.printStackTrace(Logger.err);
					crash();
				} catch (MessagingException me) {
					Logger.error("Error in ConnectionTest->main() (2): " + me);
					me.printStackTrace(Logger.err);
					crash();
				}
			}

		} catch (IOException ioe) {
			Logger.error("IOException occurred: " + ioe);
			ioe.printStackTrace(Logger.err);
		} catch (MessagingException me) {
			Logger.error("MessagingException caught: " + me);
			me.printStackTrace(Logger.err);
		}
	}

	/**
	 * Forces the whole program to terminate.
	 */
	public static void crash() {
		// notify strategies of early termination
		app.shutdown();
		// notify logger to flush output files and close
		Logger.shutdown();
		sleep(3000); // sleep for 3 seconds anyway, just in case
		// finally, system.exit
		System.exit(1);
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
