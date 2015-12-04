package com.quas.c2obridge;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Strategy #2: copy C2 but smartly!
 * - max pip differential is 1 pip (negative in terms of our favour)
 * - if pip differential exceeded (ie. chance missed), set limit order instead, for 5 pips in our favour
 * - set stop loss at 0.6% of C2's account (and if multiplier is 6x, equal to 3.6% of our account)
 * - re-entry: completely manual. re-entry rules:
 *   ~ only consider re-entry when C2 loss is 5%+
 *   ~ re-entry position size - 50% of position size multiplier (ie. if multiplier is 6, for re-entry, -> 3 instead)
 *   ~ only re-enter when there is ample evidence of reversal in our favour (eg. support or resistance, other patterns)
 *   ~ once re-entered, increasing position size is only allowed once per -2.5% of C2 account maximum
 *   ~ manually re-entered trades will remain on the blacklist so will not be added to by the auto-trader
 * - keep a list of all currently-opened positions. when C2 sends open notice, check to see if the currency pair is on
 *   the local currently-open list, and the blacklist.
 *   ~ if on blacklist, show notification, do not open trade.
 *   ~ if not on either list, this is a completely fresh trade - proceed to open without any other issues
 *   ~ if on the local currently-open list, check with Oanda to see if the trade is still active
 *   ~ if still active, re-calculate stop-loss for combined position size, to keep stop-loss at 0.6% of C2 account. if
 *     the new stop-loss is less than 15 (?) pips away, don't open the new additional position. otherwise, open.
 *   ~ if no longer active, it means we have been stopped out, so remove the pair from the local currently-open list and
 *     add to blacklist. do not open trade.
 *
 * Additional notes: Stop loss of 0.6% of C2's account was picked because 75% of trades have drawdowns equal to or lower
 * than it.
 *
 * Created by Quasar on 4/12/2015.
 */
public class SmartCopyStrategyHandler extends StrategyHandler {

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 6;

	/** Overrides the StrategyHandler's MAX_PIP_DIFF */
	private static final int MAX_PIP_DIFF = 1;

	/** Smart copy file */
	private static final String SMART_COPY_FILE = "smartcopy.properties";
	/** For save properties */
	private static final String CURRENTLY_OPEN = "CURRENTLY_OPEN";
	private static final String BLACKLIST = "BLACKLIST";

	/**
	 * List of currency pairs that are on the local-currently-open list. (Does not mean it is necessarily currently
	 * open on Oanda, as it could have been stopped out)
	 */
	private Set<String> currentlyOpen;

	/** Blacklisted currency pairs: pairs that have been stopped out beyond the 0.6% of C2 account */
	private Set<String> blacklist;

	/**
	 * Constructor for the smart copy strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public SmartCopyStrategyHandler(int accountId) {
		super(accountId);

		this.currentlyOpen = new HashSet<String>();
		this.blacklist = new HashSet<String>();

		// load saved data from properties file
		Properties saveData = new Properties();
		try {
			saveData.load(new FileInputStream(new File(SMART_COPY_FILE)));

			String[] currentlyOpenUnvalidated = saveData.getProperty(CURRENTLY_OPEN).split(",");
			String[] blacklistUnvalidated = saveData.getProperty(BLACKLIST).split(",");
			for (String s : currentlyOpenUnvalidated) {
				s = s.trim().toUpperCase(); // trim whitespace and uppercase
				validateCurrencyPair(s); // throws RuntimeException if invalid

				// add to currentlyOpen
				currentlyOpen.add(s);
			}
			for (String s : blacklistUnvalidated) {
				s = s.trim().toUpperCase();
				validateCurrencyPair(s);

				if (currentlyOpen.contains(s)) {
					throw new RuntimeException("currency pair " + s + " is in both currentlyOpen and blacklist");
				}

				// add to blacklist
				blacklist.add(s);
			}
		} catch (IOException ioe) {
			System.err.println("Error loading smartcopy.properties save data: " + ioe);
			ioe.printStackTrace(System.err);
			System.exit(0);
		} catch (RuntimeException re) {
			System.err.println("Error loading smartcopy.properties save data, corruption: " + re);
			re.printStackTrace(System.err);
			System.exit(0);
		}
	}

	/**
	 * Shuts down the strategy. Saves currentlyOpen and blacklist data.
	 */
	@Override
	public final void shutdown() {
		Properties props = new Properties();
		String c = "";
		String b = "";
		for (String s : currentlyOpen) {
			if (c.length() > 0) {
				c += ",";
			}
			c += s;
		}
		for (String s : blacklist) {
			if (b.length() > 0) {
				b += ",";
			}
			b += s;
		}
		props.put(CURRENTLY_OPEN, c);
		props.put(BLACKLIST, b);

		// save to file
		try {
			FileOutputStream fos = new FileOutputStream(SMART_COPY_FILE);
			props.store(fos, null);
			fos.close();
		} catch (IOException ioe) {
			System.err.println("Unable to save SmartCopy strategy data: " + ioe);
			ioe.printStackTrace(System.err);
		}
	}

	private void validateCurrencyPair(String s) {
		if (s.length() != 7 || !s.contains("_") || s.split("_").length != 2 || s.split("_")[0].length() != 3) {
			throw new RuntimeException("invalid currency pair: " + s);
		}
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
	public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		System.out.println("From SmartCopyStrategyHandler.handleInfo(): MAX_PIP_DIFF should be overridden to be 1: " +
			MAX_PIP_DIFF);

		if (action.equals(OPEN)) {
			// trying to open trade, determine if fresh trade, or it is on blacklist or local-currently-open list
			if (blacklist.contains(pair)) {
				// pair is currently blacklisted, show notification message and do nothing
				// even if trade has been re-entered as per re-entry rules, the currency pair will still stay on blacklist here
				System.out.println("[SmartCopyStrategy] C2 added to position for pair " + pair + ", which is blacklisted. No action taken.");
				return;
			}
			if (currentlyOpen.contains(pair)) {
				// determine if the pair is still open on Oanda, or if it has been stopped out
				List<JSONObject> trades = getTrades(pair);
				if (trades.size() > 0) { // not yet stopped out
					// calculate new stop-loss and determine whether opening this additional position + adjusting all
					// stop losses if worth it
					// @TODO
				} else { // the pair has been stopped out: remove from currentlyOpen and add to blacklist
					System.out.println("[SmartCopyStrategy] C2 added to position for pair " + pair +
						", but our position was already stopped out. Adding [" + pair + "] to blacklist.");
					currentlyOpen.remove(pair);
					blacklist.add(pair);
				}
				// return early
				return;
			}

			// not in blacklist AND not in currentlyOpen - this is a completely fresh trade, open normally
			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && curPrice < oprice) || (side.equals(SELL) && curPrice > oprice)) {
				// pip difference is at most negative 1 pip (for us)
				// try to place an order

				// get our position sizing
				int oandaPsize = convert(psize) * POS_SIZE_MULTIPLIER;
				// actually place the trade
				openTrade(side, oandaPsize, pair);
				// add pair to currentlyOpen
				currentlyOpen.add(pair);
			} else { // pip differential too large, place limit order instead
				// @TODO place limit order for 5 pips the other way (in our favour)

			}
		} else {
			// try to close all positions for the pair instantly
			// no checks required - even manually re-entered trades should be closed according to C2 strategy

			// get all trades for this pair
			ArrayList<JSONObject> list = getTrades(pair); // json of all currently open trades for this pair
			for (JSONObject trade : list) {
				// close every trade returned for this pair
				long tradeId = trade.getLong(ID);
				closeTrade(tradeId);
			}
			// get all orders for this pair
			ArrayList<JSONObject> olist = getOrders(pair);
			for (JSONObject order : olist) {
				// close every order returned for this pair
				long orderId = order.getLong(ID);
				deleteOrder(orderId);
			}

			// remove the pair from the blacklist and currentlyOpen, if present
			if (blacklist.contains(pair)) {
				blacklist.remove(pair);
				if (currentlyOpen.contains(pair)) {
					throw new RuntimeException(pair + " was in both blacklist and currentlyOpen - shouldn't happen");
				}
			} else if (currentlyOpen.contains(pair)) {
				currentlyOpen.remove(pair);
			}
		}
	}
}
