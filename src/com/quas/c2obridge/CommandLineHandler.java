package com.quas.c2obridge;

import com.quas.c2obridge.C2OBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runnable for handling inputs from the command line.
 *
 * Created by Quasar on 20/12/2015.
 */
public class CommandLineHandler implements Runnable {

	private final C2OBridge app;

	public CommandLineHandler(C2OBridge app) {
		this.app = app;
	}

	@Override
	public void run() {
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;
		try {
			while ((line = reader.readLine()) != null) {
				if (line.equals("shutdown")) {
					Logger.console("[C2OBridge] Starting shutdown procedure:");
					app.shutdown();
					Logger.shutdown();
					C2OBridge.sleep(3000); // sleep for 3 seconds anyway, just in case
					Logger.console("[C2OBridge] Shutdown successfully.");
					System.exit(0);
				} else {
					Logger.console("[C2OBridge] Unrecognised command.");
				}
			}
		} catch (IOException ioe) {
			Logger.console("IOException caught while trying to read commandline. Shouldn't be a problem if app is shutting down.");
		}
	}
}
