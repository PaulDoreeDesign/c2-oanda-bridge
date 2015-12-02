package com.quas.c2obridge;

import com.sun.mail.imap.IMAPFolder;

import javax.mail.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Properties;

/**
 * Created by Quasar on 27/11/2015.
 */
public class Main {

	/**
	 * STRATEGY ID:
	 * 1 - Exact copy of C2 (position sizing x4)
	 * 2 - Open positions of C2 (position sizing x4) with following rules:
	 * 		~ stop-loss at 2% of account (equivalent to 0.5% of C2)
	 * 		~ re-entry manual, after 5.0% loss of C2 only
	 * 3 - Make opposing trades to C2 - test different stop-losses ($500 or $1000 profit for C2) - trailing take-profits
	 *
	 * Other strategies to consider:
	 * - waiting until C2 is negative by a certain amount before entering identical trade
	 */
	private static final int STRATEGY_ID = 1;

	/** Email address without the @gmail.com domain */
	private static final String EMAIL;
	/** Email password */
	private static final String PASSWORD;
	// load from props file
	static {
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
	}

	public static void main(String[] args) throws Exception {
		// redirect System.err to log file
		File file = new File("err_log.txt");
		FileOutputStream fos = new FileOutputStream(file);
		PrintStream ps = new PrintStream(fos);
		System.setErr(ps);

		// determine strategy and create instance of appropriate handler implementation
		StrategyHandler messageHandler;
		if (STRATEGY_ID == 1) {
			messageHandler = new CopyStrategyHandler();
		}
		// else if (STRATEGY_ID == ...
		if (messageHandler == null) {
			throw new RuntimeException("Invalid STRATEGY_ID: " + STRATEGY_ID);
		}

		System.out.println("Connecting to mail server:");

		// load gmail mailserver properties and connect to it
		Properties props = new Properties();
		props.load(new FileInputStream(new File("smtp.properties")));
		props.setProperty("mail.imaps.usesocketchannels", "true");
		Session session = Session.getDefaultInstance(props, null);
		Store store = session.getStore("imaps");
		store.connect("smtp.gmail.com", EMAIL + "@gmail.com", PASSWORD);

		// open up inbox and pass to C2OBridge instance
		IMAPFolder inbox = (IMAPFolder) store.getFolder("inbox");

		C2OBridge app = new C2OBridge(inbox, messageHandler);
		app.readMessages();

		// setup keep-alive thread
		Thread keepAliveThread = new Thread(new MailKeepAlive(inbox));
		keepAliveThread.start();
		System.out.println("Started running keep-alive thread.");

		// listen for new emails
		app.listen(session, inbox);

		// handle input here
		System.out.println("Setup complete, listening for new emails...");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.equals("quit") || line.equals("exit")) {
				System.out.println("Shutdown successfully");
				System.exit(0);
			} else {
				System.out.println("Unrecognised command");
			}
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
}
