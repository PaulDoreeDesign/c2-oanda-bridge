package com.quas.c2obridge;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Static logging class. This should be the only class in the application which allows direct calls to System.out and
 * System.err.
 *
 * Created by Quasar on 20/12/2015.
 */
public class Logger {

	/** The references for the info AND error combined log file */
	private static FileOutputStream ifos;
	public static PrintStream out;

	/** The references for the error log file */
	private static FileOutputStream efos;
	public static PrintStream err;

	// open logging files
	static {
		// get current date
		DateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm");
		Date date = new Date();
		String dateString = dateFormat.format(date);

		try {
			File ifile = new File(dateString + "_info.txt");
			ifos = new FileOutputStream(ifile);
			out = new PrintStream(ifos);

			File efile = new File(dateString + "_error.txt");
			efos = new FileOutputStream(efile);
			err = new PrintStream(efos);
		} catch (FileNotFoundException fnfe) {
			System.out.println("Exception encountered while attempting to create output log files: " + fnfe);
			System.exit(0);
		}
	}

	/**
	 * Gets the PrintStream for the info log.
	 *
	 * @return the PrintStream object for the info log
	 */
	public static PrintStream out() {
		return out;
	}

	/**
	 * Gets the PrintStream for the error log.
	 *
	 * @return the PrintStream object for the error log
	 */
	public static PrintStream err() {
		return err;
	}

	/**
	 * Logs the given message to console (System.out).
	 *
	 * @param msg the message to log
	 */
	public static void console(String msg) {
		System.out.println(time() + msg);
	}

	/**
	 * Logs the given message to the info log file. Also broadcasts in console.
	 *
	 * @param msg the message to log
	 */
	public static void info(String msg) {
		console(msg);
		out.println(time() + msg);
	}

	/**
	 * Logs the given message to the error log file. Also broadcasts to info and console.
	 *
	 * @param msg the message to log
	 */
	public static void error(String msg) {
		info(msg);
		err.println(time() + msg);
	}

	/**
	 * Notifies the logger that the application is shutting down. Flushes output and closes open files.
	 */
	public static void shutdown() {
		try {
			out.flush();
			err.flush();
			ifos.close();
			efos.close();
		} catch (IOException ioe) {
			System.out.println("Error trying to close Logger files: " + ioe);
			ioe.printStackTrace(System.out);
		}
	}

	/**
	 * Gets the current time, wrapped in square brackets.
	 *
	 * @return the current time as a string
	 */
	private static String time() {
		return "[" + C2OBridge.getCurrentTime() + "] ";
	}
}
