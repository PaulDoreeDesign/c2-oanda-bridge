package com.quas.c2obridge;

import com.quas.c2obridge.strategy.StrategyHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * Runnable for handling inputs from the command line.
 *
 * Created by Quasar on 20/12/2015.
 */
public class CommandLineHandler implements Runnable {

	private final C2OBridge app;

	private final Map<Integer, StrategyHandler> strategies;

	public CommandLineHandler(C2OBridge app, Map<Integer, StrategyHandler> strategies) {
		this.app = app;

		this.strategies = strategies;
	}

	@Override
	public void run() {
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;
		try {
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.equals("shutdown")) {
					Logger.console("[C2OBridge] Starting shutdown procedure:");
					app.shutdown();
					Logger.shutdown();
					C2OBridge.sleep(3000); // sleep for 3 seconds anyway, just in case
					Logger.console("[C2OBridge] Shutdown successfully.");
					System.exit(0);
				} else {
					String[] args = line.split(" ");
					String strategy = args[0];
					if (strategy.equals("reverse")) {
						StrategyHandler reverseStrat = strategies.get(C2OBridge.REVERSE);
						if (args[1].equals("test")) {
							Logger.info("Attempting to make a test transaction...");
							// try and buy 1 unit of AUD_USD from reverse account
							long tradeId = reverseStrat.openTrade(StrategyHandler.BUY, 1, CurrencyPairs.getPair("AUD", "USD"));
							Logger.info("Successfully bought [1 unit] of [AUD/USD] with current config.");
							C2OBridge.sleep(1000); // sleep for a second then close the trade
							reverseStrat.closeTrade(tradeId);
							Logger.info("Closed trade. Test complete.");
						} else if (args[1].equalsIgnoreCase(StrategyHandler.BUY) || args[1].equalsIgnoreCase(StrategyHandler.SELL)) {
							String side = args[1].toLowerCase();
						} else {

						}
					} else {
						Logger.console("[C2OBridge] Unrecognised command.");
					}
				}
			}
		} catch (IOException ioe) {
			Logger.console("IOException caught while trying to read commandline. Shouldn't be a problem if app is shutting down.");
		} catch (RuntimeException re) {
			Logger
		}
	}
}
