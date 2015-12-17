package com.quas.c2obridge;

import com.sun.mail.imap.IMAPFolder;

import javax.mail.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Properties;


/**
 * STRATEGIES:
 * 1 - Exact copy of C2 (position sizing x4)
 * 2 - Open positions of C2 (position sizing x4) with following rules:
 * 		~ stop-loss at 2% of account (equivalent to 0.5% of C2)
 * 		~ re-entry manual, after 5.0% loss of C2 only
 * 3 - Make opposing trades to C2 - test different stop-losses ($500 or $1000 profit for C2) - trailing take-profits
 *
 * Other strategies to consider:
 * - delayed entry - wait until C2 is negative a certain amount before entry
 * - half-delayed entry - open half position, wait until C2 is negative a certain amount before entering 2nd half
 */
public class Main {

	/** Email address without the @gmail.com domain */
	public static final String EMAIL;
	/** Email password */
	public static final String PASSWORD;
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

	public static void temp(String[] args) throws Exception {
		// redirect System.err to log file
		File file = new File("err_log.txt");
		FileOutputStream fos = new FileOutputStream(file);
		PrintStream ps = new PrintStream(fos);
		System.setErr(ps);

		System.out.println("Connecting to mail server:");

		// load gmail mailserver properties and connect to it
		Properties props = new Properties();
		FileInputStream fis = new FileInputStream(new File("smtp.properties"));
		props.load(fis);
		props.setProperty("mail.imaps.usesocketchannels", "true");
		Session session = Session.getInstance(props, null);
		Store store = session.getStore("imaps");
		MailSync.setStore(store);
		MailSync.setSession(session);
		store.connect("smtp.gmail.com", EMAIL + "@gmail.com", PASSWORD);

		// open up inbox and pass to C2OBridge instance
		IMAPFolder inbox = (IMAPFolder) store.getFolder("inbox");
		MailSync.setInbox(inbox);

		C2OBridge app = new C2OBridge();
		app.readMessages();

		// setup keep-alive thread
		Thread keepAliveThread = new Thread(new MailKeepAlive(inbox));
		keepAliveThread.start();
		System.out.println("Started running keep-alive thread.");

		// close file input stream
		fis.close();

		// listen for new emails
		C2OBridge.listen(inbox);

		// handle input here
		System.out.println("Setup complete, listening for new emails...");
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.equals("quit") || line.equals("exit")) {
				System.out.println("---------------------");
				System.out.println("Shutting down:");
				app.shutdown();
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
