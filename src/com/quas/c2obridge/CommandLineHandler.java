package com.quas.c2obridge;

import com.quas.c2obridge.strategy.ReverseStrategyHandler;
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
						ReverseStrategyHandler reverseStrat = (ReverseStrategyHandler) strategies.get(C2OBridge.REVERSE);
						if (args.length == 1) {
							Logger.console("Reverse needs more arguments...");
						} else if (args[1].equals("test")) {
							Logger.console("Attempting to make a test transaction...");
							// try and buy 1 unit of gold from reverse account
							long tradeId = reverseStrat.openTrade(StrategyHandler.BUY, 1, "XAU_USD");
							Logger.console("Successfully bought [1 unit] of [XAU/USD] with current config. Sleeping for 5 sec then closing the test trade...");
							C2OBridge.sleep(5000); // sleep for 5 sec then close the trade
							reverseStrat.closeTrade(tradeId);
							Logger.console("Closed trade. Test complete.");
						} else if (args[1].equalsIgnoreCase(StrategyHandler.BUY) || args[1].equalsIgnoreCase(StrategyHandler.SELL)) {
							if (args.length < 5 || args.length > 6) {
								Logger.console("Invalid syntax: use 'reverse buy/sell currency_A currency_B numUnits [additional]'");
								continue;
							}
							// actually try and make the trade
							String side = args[1].toLowerCase();
							String cur1 = args[2].toUpperCase();
							String cur2 = args[3].toUpperCase();
							if (!CurrencyPairs.isPair(cur1, cur2)) {
								Logger.console("[" + cur1 + " / " + cur2 + "] is not a valid currency pair. Try again.");
								continue;
							}
							String pair = CurrencyPairs.getPair(cur1, cur2);
							int units;
							try {
								units = Integer.parseInt(args[4]);
							} catch (RuntimeException re) { // includes NFEs
								Logger.console("Error parsing units: " + re + ", " + re.getMessage());
								continue;
							}
							boolean additional = false;
							if (args.length == 6) {
								if (args[5].equals("additional")) {
									additional = true;
								} else {
									Logger.console("Invalid syntax: use 'reverse buy/sell currency_A currency_B numUnits [additional]'");
									continue;
								}
							}
							// open the unreverse trade if possible
							String error = reverseStrat.canUnreverse(pair, side, units, additional);
							if (error.equals("")) { // no error, can unreverse
								reverseStrat.openUnreverseTrade(side, units, pair, additional);
								Logger.console("Successfully opened an unreverse trade. Side: " + side + ", units: " + units + ", pair: " + pair);
							} else {
								Logger.console("Can't open unreverse trade for the following reasons:" + error); // print the error to console
							}
						} else {
							Logger.console("Invalid reverse command. Check your syntax and try again.");
						}
					} else {
						Logger.console("[C2OBridge] Unrecognised command.");
					}
				}
			}
		} catch (IOException ioe) {
			Logger.console("IOException caught while trying to read commandline. Shouldn't be a problem if app is shutting down.");
		} catch (RuntimeException re) {
			Logger.console("Something went wrong when processing command... " + re);
			re.printStackTrace(Logger.err);
		}
	}
}
