package com.quas.c2obridge.strategy;

import com.quas.c2obridge.C2OBridge;
import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Strategy #3:
 * - does the exact opposite of what the C2 strategy provider does
 * - initial stop-loss flat 25 pips
 * - no take-profit: use trailing stop-loss of 50 pips
 * - has 'unreversed' trades, that turn and go with the strategy provider once it has gone very negative
 *
 * Created by Quasar on 3/12/2015.
 */
public class ReverseStrategyHandler extends StrategyHandler {

	/** Percentage of account to risk with every trade */
	private static final int RISK_PERCENTAGE_PER_TRADE = 2;

	/** Custom pip diff for reverse strategy only */
	private static final int REVERSE_MAX_PIP_DIFF = 10; // 10 pips

	/** Maximum number of units for an unreverse trade */
	private static final int MAX_UNREVERSE_TRADE_UNITS = 50000;

	/** Initial stop-loss in pips */
	private static final int STOP_LOSS = 25;

	/** Trailing stop-loss in pips */
	private static final int TRAILING_STOP_LOSS = 50;

	/** Filename for this strategy */
	private static final String REVERSE_FILE = "reverse.properties";

	/** Property name for currently open on C2 */
	private static final String OPEN_ON_C2 = "OPEN_ON_C2";
	/** Property name for unreversed trades */
	private static final String UNREVERSED = "UNREVERSED";

	/** How often to run the scheduled reverse check */
	private static final int SCHEDULED_CHECK_MINUTES = 5; // every 5 min

	/** Scheduled checker for reversed pairs */
	private final ScheduledExecutorService scheduler;
	private final ReverseScheduledCheck checker;

	/** Set that contains all pairs that are currently open on C2 */
	private Set<String> openOnC2;
	/** Set that contains all pairs that are currently un-reversed */
	private Set<String> unreversed;

	/** Set that contains all pairs that we currently have reversed */
	private Set<String> reversed;

	/**
	 * Constructor for the reverse strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public ReverseStrategyHandler(int accountId) {
		super(accountId);

		this.openOnC2 = new HashSet<>();
		this.unreversed = new HashSet<>();
		this.reversed = new HashSet<>();

		// load saved data from properties file
		Properties saveData = new Properties();
		try {
			saveData.load(new FileInputStream(new File(REVERSE_FILE)));

			String[] openOnC2Unvalidated = saveData.getProperty(OPEN_ON_C2).split(",");
			for (String s : openOnC2Unvalidated) {
				if (s.equals("")) continue;
				s = s.trim().toUpperCase(); // trim whitespace and uppercase
				validateCurrencyPair(s); // throws RuntimeException if invalid

				// add to openOnC2
				openOnC2.add(s);
			}

			String[] unreversedUnvalidated = saveData.getProperty(UNREVERSED).split(",");
			for (String s : unreversedUnvalidated) {
				if (s.equals("")) continue;
				s = s.trim().toUpperCase();
				validateCurrencyPair(s);

				// if already on op
				// add to unreversed
				unreversed.add(s);
			}
		} catch (IOException ioe) {
			Logger.error("Error loading reverse.properties save data: " + ioe);
			ioe.printStackTrace(Logger.err);
			C2OBridge.crash();
		} catch (RuntimeException re) {
			Logger.error("Error loading reverse.properties save data, corruption: " + re);
			re.printStackTrace(Logger.err);
			C2OBridge.crash();
		}

		// setup scheduled checks
		this.scheduler = Executors.newScheduledThreadPool(1);
		this.checker = new ReverseScheduledCheck();
		scheduler.scheduleAtFixedRate(checker, SCHEDULED_CHECK_MINUTES, SCHEDULED_CHECK_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * Scheduled task that runs every SCHEDULED_CHECK_MINUTES minutes. Acts like a more blocky trailing stop loss, with
	 * fixed intervals to shift stop-losses. Possibly the most important shift is from the initial stop-loss to
	 * break-even.
	 */
	private class ReverseScheduledCheck implements Runnable {

		@Override
		public void run() {
			Logger.info("Scheduled task placeholder");
		}
	}

	/**
	 * Checks whether or not the given pair can be unreversed.
	 *
	 * A pair can be unreversed if:
	 * - it is currently open on C2
	 * - we are not currently reverse-trading it
	 * - it has not already been unreversed
	 *
	 * @param pair the pair to check for unreversing
	 * @param units the amount of units in the unreverse trade
	 * @param additional whether or not this is an additional unreversal
	 * @return null if the pair can be unreversed. otherwise, returns a string detailing the reason why this pair cannot
	 * 		   be unreversed.
	 */
	public String canUnreverse(String pair, int units, boolean additional) {
		String reason = "";

		ArrayList<JSONObject> list = getTrades(pair);
		for (JSONObject trade : list) {
			if (trade.getString(INSTRUMENT).equals(pair)) {
				reason += "\n- pair is currently being reverse traded, ";
				break;
			}
		}

		if (!openOnC2.contains(pair)) {
			if (!reason.equals("")) reason += "\n";
			reason += "- pair is NOT currently open on C2, ";
		}
		if (unreversed.contains(pair) && !additional) {
			if (!reason.equals("")) reason += "\n";
			reason += "- pair has ALREADY been unreversed. To force an additional trade on top, use 'reverse buy/sell currency_A currency_B numUnits additional";
		}
		if  (units > MAX_UNREVERSE_TRADE_UNITS) {
			if (!reason.equals("")) reason += "\n";
			reason += "- unreverse trade size cannot exceed the maximum of " + MAX_UNREVERSE_TRADE_UNITS + " units";
		}
		return reason;
	}

	/**
	 * Opens an unreverse trade and puts the pair into the unreversed collection. Does not check all requirements, so
	 * make sure canUnreverse() is called before calling this method.
	 *
	 * @param side buy or sell
	 * @param units the position size
	 * @param pair the currency pair to trade
	 * @param additional whether or not this trade is a forced additional on top of an already reversed trade
	 */
	public void openUnreverseTrade(String side, int units, String pair, boolean additional) {
		if (!additional && unreversed.contains(pair)) { // not additional, but already in unreversed collection
			throw new RuntimeException("Unreversed already contains pair [" + pair + "]");
		} else if (!additional) { // first trade, add to collection
			unreversed.add(pair);
		}

		// open trade
		openTrade(side, units, pair);
	}

	/**
	 * Called by handleMessage with the info extracted from the email.
	 *
	 * @param action the action being undertaken: OPEN or CLOSE
	 * @param side the side being undertaken: BUY or SELL
	 * @param psize the position size opened by C2
	 * @param pair the currency pair being traded
	 * @param oprice the price at which C2 opened the position
	 */
	@Override
	public final void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		if (action.equals(OPEN)) {

			// check if any trades for this pair are already opened. if so, ignore
			List<JSONObject> alreadyOpen = getTrades(pair);
			if (alreadyOpen.size() > 0) {
				// return early without doing anything
				return;
			}

			// only process if this pair isn't already in openOnC2 (otherwise it means this is an additional trade)
			if (!openOnC2.contains(pair)) {
				// add to set
				openOnC2.add(pair);

				// flip side
				side = side.equals(BUY) ? SELL : BUY;

				// diff = difference between C2's opening price and oanda's current price, in pips
				if (diff <= REVERSE_MAX_PIP_DIFF || (side.equals(BUY) && curPrice < oprice) || (side.equals(SELL) && curPrice > oprice)) {
					// pip difference is at most positive 5 pips (in direction of C2's favour), so try to place an order

					// fetch balance
					double balance = getAccountBalance();

					// figure out position sizing, with relation to RISK_PERCENTAGE_PER_TRADE and STOP_LOSS
					double accCurrencyPerPip = getAccCurrencyPerPip(pair); // each pip of this pair is worth how much $AUD assuming position size 1
					double accCurrencyRisk = balance * (RISK_PERCENTAGE_PER_TRADE / 100D); // how many $AUD for RISK_PERCENTAGE_PER_TRADE % risk
					int oandaPsize = (int) (accCurrencyRisk / (accCurrencyPerPip * STOP_LOSS));

					// actually place the trade
					long id = openTrade(side, oandaPsize, pair); // id = id of the trade that is returned once it is placed

					// modify the trade to give it a stop-loss
					double stopLoss = curPrice;
					double netPips = pipsToPrice(pair, STOP_LOSS);
					if (side.equals(BUY)) stopLoss -= netPips;
					else stopLoss += netPips;

					// round stop loss to 4 decimal places (non-JPY pairs) or 2 decimal places (JPY pairs)
					if (pair.contains(JPY)) {
						stopLoss = round2(stopLoss);
					} else {
						stopLoss = round4(stopLoss);
					}

					// give it initial stop loss and the trailing stop loss. no take profit
					modifyTrade(id, stopLoss, TRAILING_STOP_LOSS);

				} else {
					// missed opportunity
					Logger.error("[ReverseStrategy] missed opportunity to place order to " + side + " " + pair + " (pip diff = " + diff +
							" - actiontype = " + side + ", curPrice = " + curPrice + ", oprice = " + oprice + ")");
					// @TODO set limit orders for missed opportunities
				}
			}
		} else {
			// mark as closed, remove from openOnC2
			openOnC2.remove(pair);
			// check if we had this pair on unreverse, if so, close that trade and remove from unreverse collection
			if (unreversed.contains(pair)) {
				unreversed.remove(pair);
				// fetch all of the open trades and close them
				ArrayList<JSONObject> list = getTrades(pair);
				if (list.size() == 0) {
					Logger.error("[ReverseStrategy] C2 closed pair " + pair + " which was on the unreverse list, but didn't find any on Oanda.");
				}
				for (JSONObject trade : list) {
					// close every trade returned for this pair
					long tradeId = trade.getLong(ID);
					closeTrade(tradeId);
				}
			}
		}
	}

	/**
	 * Shuts down the strategy. Saves openOnC2 data.
	 */
	@Override
	public final void shutdown() {
		Properties props = new Properties();
		String c = "";
		for (String s : openOnC2) {
			if (c.length() > 0) c += ",";
			c += s;
		}
		props.put(OPEN_ON_C2, c);
		String u = "";
		for (String s : unreversed) {
			if (u.length() > 0) u += ",";
			u += s;
		}
		props.put(UNREVERSED, u);

		// save to file
		try {
			FileOutputStream fos = new FileOutputStream(REVERSE_FILE);
			props.store(fos, null);
			fos.close();
		} catch (IOException ioe) {
			Logger.error("Unable to save ReverseStrategy data: " + ioe);
			ioe.printStackTrace(Logger.err);
		}
	}
}
