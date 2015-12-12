package com.quas.c2obridge;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IdleManager;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application root
 *
 * Created by Quasar on 2/12/2015.
 */
public class C2OBridge {

	/**
	 * Debug mode = read all emails currently in the inbox and treat as new emails
	 * Non-debug mode = if there are any emails in inbox upon program start, terminate with warning message
	 */
	private static final boolean DEBUG_MODE = false;

	/** MessageHandler implementation */
	private static List<StrategyHandler> strategyHandlers;

	/** Oanda API details */
	public static final String OANDA_API_KEY;
	public static final String OANDA_API_URL;
	/** Account IDs for respective strategies */
	private static final int COPY_ACC_ID;
	private static final int REVERSE_ACC_ID;
	private static final int SMART_COPY_ACC_ID;

	// load from props file
	static {
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

	/**
	 * Creates an instance of the C2OBridge.
	 */
	public C2OBridge() {
		// initialise the applicable strategies and their account ids
		strategyHandlers = new ArrayList<StrategyHandler>();
		// smart copy strategy
		strategyHandlers.add(new SmartCopyStrategyHandler(SMART_COPY_ACC_ID));
		// exact copy strategy
		strategyHandlers.add(new CopyStrategyHandler(COPY_ACC_ID));
		// reverse strategy
		strategyHandlers.add(new ReverseStrategyHandler(REVERSE_ACC_ID));
	}

	/**
	 * Alerts all strategies of shutdown.
	 */
	public void shutdown() {
		for (StrategyHandler strategy : strategyHandlers) {
			strategy.shutdown();
		}
	}

	public void readMessages() {
		if (DEBUG_MODE) System.out.println("Sweeping inbox...");
		else System.out.println("[ALERT] Running in production mode!");

		try {
			IMAPFolder inbox = MailSync.getInbox();
			inbox.open(Folder.READ_WRITE);

			// check that there aren't any C2 position update emails in inbox
			// quit to force manual checking of inbox, if so
			Message[] messages = inbox.getMessages();
			for (Message message : messages) {
				if (DEBUG_MODE) {
					try {
						for (StrategyHandler strategy : strategyHandlers) {
							strategy.handleMessage(message);
							sleep(1000);
						}
					} catch (Exception e) {
						System.err.println("ERROR: " + e);
						e.printStackTrace(System.err);
					}
				} else {
					String subject = message.getSubject();
					if (subject.equals(IStrategyHandler.SUBJECT_FIND)) {
						System.out.println("WARNING: new unhandled emails in inbox. force-quitting, check inbox manually...");
						System.exit(0);
					}
					System.out.println("Inbox checked successfully with no issues.");
				}
			}
		} catch (MessagingException me) {
			System.err.println("Error reading inbox messages: " + me);
			me.printStackTrace(System.err);
		}
	}

	/**
	 * Begins a new thread which listens for new emails and handles them accordingly.
	 */
	public static void listen(final IMAPFolder folder) {
		try {
			ExecutorService es = Executors.newCachedThreadPool();
			MailSync.setExecutorService(es);
			Session session = MailSync.getSession();
			final IdleManager idleManager = new IdleManager(session, es);

			// watch inbox for new emails
			// folder.open(Folder.READ_WRITE);
			folder.addMessageCountListener(new MessageCountAdapter() {

				public void messagesAdded(MessageCountEvent ev) {

					Folder folder = (Folder) ev.getSource();
					Message[] messages = ev.getMessages();

					// process each message
					try {
						for (Message message : messages) {
							System.out.println("\n[" + Main.getCurrentTime() + "] Received a message with title = " + message.getSubject());
							// run message through all the strategies
							for (StrategyHandler strategy : strategyHandlers) {
								strategy.handleMessage(message);
								sleep(1000); // sleep for 1 sec between strategies to not exceed limit for Oanda API calls
							}
						}
					} catch (IOException ioe) {
						System.err.println("Error in C2OBridge.listen->messagesAdded(): " + ioe);
						ioe.printStackTrace(System.err);
					} catch (MessagingException me) {
						System.err.println("Error in C2OBridge.listen->messagesAdded(): " + me);
						me.printStackTrace(System.err);
					}

					try {
						// process new messages
						idleManager.watch(folder); // keep watching for new messages
					} catch (MessagingException mex) {
						System.err.println("MessagingException caught when trying to process new messages:");
						mex.printStackTrace(System.err);
					}
				}
			});
			// set idle manager
			MailSync.setIdleManager(idleManager);
			idleManager.watch(folder);
		} catch (IOException ioe) {
			System.err.println("Error in C2OBridge.listen(): " + ioe);
			ioe.printStackTrace(System.err);
		} catch (MessagingException me) {
			System.err.println("Error in C2OBridge.listen(): " + me);
			me.printStackTrace(System.err);
		}
	}

	public static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ie) {
			// ignore
		}
	}
}
