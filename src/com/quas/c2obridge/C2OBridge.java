package com.quas.c2obridge;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IdleManager;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Quasar on 2/12/2015.
 */
public class C2OBridge {

	/** The inbox folder */
	private Folder inbox;

	/** MessageHandler implementation */
	private StrategyHandler strategyHandler;

	/**
	 * Creates an instance of the C2OBridge with the 'inbox' folder from the email account.
	 *
	 * @param inbox
	 */
	public C2OBridge(Folder inbox, StrategyHandler messageHandler) {
		this.inbox = inbox;
		this.strategyHandler = messageHandler;
	}

	public void readMessages() {
		System.out.println("Sweeping inbox...");
		try {
			inbox.open(Folder.READ_WRITE);

			// check that there aren't any C2 position update emails in inbox
			// quit to force manual checking of inbox, if so
			Message[] messages = inbox.getMessages();
			for (Message message : messages) {
				/*
				try {
					messageHandler.handleMessage(message);
				} catch (Exception e) {
					System.err.println("ERROR: " + e);
					e.printStackTrace(System.err);
				}
				*/
				String subject = message.getSubject();
				if (subject.equals(StrategyHandler.SUBJECT_FIND)) {
					System.out.println("WARNING: new unhandled emails in inbox. force-quitting, check inbox manually...");
					System.exit(0);
				}
			}
			System.out.println("Inbox checked successfully with no issues.");
		} catch (MessagingException me) {
			System.err.println("Error reading inbox messages: " + me);
			me.printStackTrace(System.err);
		}
	}

	/**
	 * Begins a new thread which listens for new emails and handles them accordingly.
	 */
	public void listen(Session session, IMAPFolder folder) {
		try {
			ExecutorService es = Executors.newCachedThreadPool();
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
							strategyHandler.handleMessage(message);
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
			idleManager.watch(folder);
		} catch (IOException ioe) {
			System.err.println("Error in C2OBridge.listen(): " + ioe);
			ioe.printStackTrace(System.err);
		} catch (MessagingException me) {
			System.err.println("Error in C2OBridge.listen(): " + me);
			me.printStackTrace(System.err);
		}
	}
}
