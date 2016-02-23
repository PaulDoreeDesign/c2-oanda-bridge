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
	private static final int INITIAL_STOP_LOSS = 25;

	/** Trailing stop-loss in pips */
	private static final int TRAILING_STOP_LOSS = 50;

	/** Filename for this strategy */
	private static final String REVERSE_FILE = "reverse.properties";

	/** Property name for currently open on C2 */
	private static final String OPEN_ON_C2 = "OPEN_ON_C2";
	/** Property name for unreversed trades */
	private static final String UNREVERSED = "UNREVERSED";
	/** Property name for reversed trades */
	private static final String REVERSED = "REVERSED";

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
	private final Set<String> reversed;

	/**
	 * Constructor for the reverse strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public ReverseStrategyHandler(int accountId) {
		super(accountId);

		this.openOnC2 = new HashSet<String>();
		this.unreversed = new HashSet<String>();
		this.reversed = new HashSet<String>();

		// load saved data from properties file
		Properties saveData = new Properties();
		try {
			saveData.load(new FileInputStream(new File(REVERSE_FILE)));

			String[] openOnC2Unvalidated = saveData.getProperty(OPEN_ON_C2).split(",");
			for (String s : openOnC2Unvalidated) {
				if (s.trim().equals("")) continue;
				s = s.trim().toUpperCase(); // trim whitespace and uppercase
				validateCurrencyPair(s); // throws RuntimeException if invalid

				// add to openOnC2
				openOnC2.add(s);
			}

			String[] unreversedUnvalidated = saveData.getProperty(UNREVERSED).split(",");
			for (String s : unreversedUnvalidated) {
				if (s.trim().equals("")) continue;
				s = s.trim().toUpperCase();
				validateCurrencyPair(s);

				// if already on op
				// add to unreversed
				unreversed.add(s);
			}

			String[] reversedUnvalidated = saveData.getProperty(REVERSED).split(",");
			for (String s : reversedUnvalidated) {
				if (s.trim().equals("")) continue;
				s = s.trim().toUpperCase();
				validateCurrencyPair(s);
				// add to reversed collection
				reversed.add(s); // no synchronization block needed here because this will be executed completely before emails start being read
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
			// fetch all the currently open trades on oanda
			List<JSONObject> allTrades = getTrades(null);
			Set<String> stillOpenReverse = new HashSet<String>();
			Set<String> stillOpenUnreverse = new HashSet<String>();
			for (JSONObject trade : allTrades) {
				double stopLoss = trade.getDouble(TRAILING_STOP);
				String pair = trade.getString(INSTRUMENT);

				boolean isReversedTrade;
				synchronized (reversed) {
					// whether or not this trade is a reverse trade (the other possibility is unreversed)
					isReversedTrade = reversed.contains(pair);
				}
				if (isReversedTrade) {
					stillOpenReverse.add(pair);
				} else {
					stillOpenUnreverse.add(pair);
				}

				// check if the trade has gone in our favour by INITIAL_STOP_LOSS pips
				long tradeId = trade.getLong(ID);
				double openPrice = trade.getDouble(PRICE);
				String openSide = trade.getString(SIDE);
				String closeAction = openSide.equals(BUY) ? SELL : BUY; // action to take if we want to close the trade
				double currentPrice = getOandaPrice(closeAction, pair);
				double profit = (openSide.equals(BUY) ? 1 : -1) * (currentPrice - openPrice); // profit in pair price
				profit = priceToPips(pair, profit); // calculate profit in pips

				// reversed trades: consider moving their stop-loss to break even, and also possibly set trailing stop
				if (isReversedTrade) {
					if (stopLoss == 0) { // trades that we haven't set the trailing stop for yet
						if (profit >= INITIAL_STOP_LOSS) {
							// when we reach INITIAL_STOP_LOSS pips in profit, we move stop-loss to break even
							// and also set the trailing stop loss
							modifyTrade(tradeId, openPrice, TRAILING_STOP_LOSS);
							Logger.info("[ReverseStrategy -> ReverseScheduleCheck] Set trade for reversed [" + pair + "] to break-even (stop-loss = " + openPrice +
								"). Also set trailing stop to " + TRAILING_STOP_LOSS + " pips.");
						}
					} else {
						if (profit >= (INITIAL_STOP_LOSS * 8)) { // 200 pips profit -> 100 pips stop-loss
							// double the trailing stop loss
							modifyTrade(tradeId, openPrice, TRAILING_STOP_LOSS * 2);
							Logger.info("[ReverseStrategy -> ReverseScheduleCheck] Trade for reversed [" + pair + "] reached " + profit + " pips profit, so moved trailing stop to " +
									(TRAILING_STOP_LOSS * 2) + " pips.");
						}
						// maybe additional settings here in the future...
					}
				// unreversed trades: consider moving their stop-loss to break even, but that's it.
				} else if (stopLoss == 0 && profit >= INITIAL_STOP_LOSS) {
					modifyTrade(tradeId, openPrice, NO_TRAILING_STOP);
					Logger.info("[ReverseStrategy -> ReverseScheduleCheck] Set trade for un-reversed [" + pair + "] to break-even (stop-loss = " + openPrice + "). No trailing stop.");
				}
			}

			// check for reversed trades that have been stopped out
			synchronized (reversed) {
				Set<String> reversedCopy = new HashSet<String>(reversed);
				reversedCopy.removeAll(stillOpenReverse); // subtract stillOpen from reversedCopy
				// pairs still remaining in reversedCopy have been stopped out
				for (String pair : reversedCopy) {
					// remove from reversed
					reversed.remove(pair);
					// print message informing of stop-out
					Logger.info("[ReverseStrategy -> ReverseScheduleCheck] Trade for reverse [" + pair + "] appears to have been stopped out.");
				}
			}

			// check for unreversed trades that have been stopped out
			Set<String> unreversedCopy = new HashSet<String>(unreversed);
			unreversedCopy.removeAll(stillOpenUnreverse);
			for (String pair : unreversedCopy) {
				unreversed.remove(pair);
				Logger.info("[ReverseStrategy -> ReverseScheduleCheck] Trade for un-reverse [" + pair + "] appears to have been stopped out.");
			}
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
	 * @param side the side that we are trying to unreverse with
	 * @param units the amount of units in the unreverse trade
	 * @param additional whether or not this is an additional unreversal
	 * @return null if the pair can be unreversed. otherwise, returns a string detailing the reason why this pair cannot
	 * 		   be unreversed.
	 */
	public String canUnreverse(String pair, String side, int units, boolean additional) {
		String reason = "";

		List<JSONObject> list = getTrades(pair);
		for (JSONObject trade : list) {
			if (trade.getString(INSTRUMENT).equals(pair)) {
				if (!trade.getString(SIDE).equals(side)) {
					reason += "\n- pair is currently being traded in the OPPOSITE direction, ";
				}
				break;
			}
		}

		if (!openOnC2.contains(pair)) {
			if (!reason.equals("")) reason += "\n";
			reason += "- pair is NOT currently open on C2, ";
		}
		if (unreversed.contains(pair) && !additional) {
			if (!reason.equals("")) reason += "\n";
			reason += "- pair has ALREADY been unreversed. To force an additional trade on top, use 'reverse buy/sell currency_A currency_B numUnits [additional]'";
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

					// figure out position sizing, with relation to RISK_PERCENTAGE_PER_TRADE and INITIAL_STOP_LOSS
					double accCurrencyPerPip = getAccCurrencyPerPip(pair); // each pip of this pair is worth how much $AUD assuming position size 1
					double accCurrencyRisk = balance * (RISK_PERCENTAGE_PER_TRADE / 100D); // how many $AUD for RISK_PERCENTAGE_PER_TRADE % risk
					int oandaPsize = (int) (accCurrencyRisk / (accCurrencyPerPip * INITIAL_STOP_LOSS));

					// actually place the trade
					long id = openTrade(side, oandaPsize, pair); // id = id of the trade that is returned once it is placed

					// modify the trade to give it an initial stop-loss
					double stopLoss = curPrice;
					double netPips = pipsToPrice(pair, INITIAL_STOP_LOSS);
					if (side.equals(BUY)) stopLoss -= netPips;
					else stopLoss += netPips;

					// round stop loss to 4 decimal places (non-JPY pairs) or 2 decimal places (JPY pairs)
					if (pair.contains(JPY)) {
						stopLoss = round2(stopLoss);
					} else {
						stopLoss = round4(stopLoss);
					}

					// give it initial stop loss, but not trailing stop loss.
					modifyTrade(id, stopLoss, NO_TRAILING_STOP);

					// add pair to the 'reversed' collection
					synchronized (reversed) {
						reversed.add(pair);
					}

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
				List<JSONObject> list = getTrades(pair);
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
		String r = "";
		for (String s : reversed) {
			if (r.length() > 0) r += ",";
			r += s;
		}
		props.put(REVERSED, r);

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
