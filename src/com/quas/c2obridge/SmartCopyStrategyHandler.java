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
 *   ~ if still active, only add to the position if there's only 1 trade so far for the pair, and if the distance to
 *     stop-loss is more than 10 pips. make the stop-loss the same as the first trade, so the risk is increased, up to
 *     a maximum of 40% risk increase (at this time, 3.6->5.0% total risk). scale the new position size if necessary to
 *     stay within this upper limit of risk.
 *   ~ if no longer active, it means we have been stopped out, so remove the pair from the local currently-open list and
 *     add to blacklist. do not open trade.
 *
 * Additional notes: Stop loss of 0.6% of C2's account was picked because 75% of trades have drawdowns equal to or lower
 * than it.
 *
 * Created by Quasar on 4/12/2015.
 */
public class SmartCopyStrategyHandler extends StrategyHandler {

	/** Stop-loss when C2's account hits this PERCENTAGE drawdown */
	private static final double C2_STOPLOSS_PERCENT = 0.6;

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 6;

	/** Percentage of our account risked per trade, equivalent to C2_STOPLOSS_PERCENT * POS_SIZE_MULTIPLIER */
	private static final double ACC_STOPLOSS_PERCENT = C2_STOPLOSS_PERCENT * POS_SIZE_MULTIPLIER;

	/** Overrides the StrategyHandler's MAX_PIP_DIFF */
	private static final int MAX_PIP_DIFF = 1;

	/**
	 * Number of pips from C2's original opening price (oprice variable) in OUR FAVOUR to place limit order if
	 * instant open was missed
	 */
	private static final int LIMIT_ORDER_PIPS_DIFF = 5;

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
		// get our account balance in account currency at the very start
		double accountBalance = getAccountBalance();

		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		// get our position sizing
		int oandaPsize = convert(psize, accountBalance) * POS_SIZE_MULTIPLIER;

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
				List<JSONObject> orders = getOrders(pair);
				if (trades.size() > 0) { // trade was placed and not yet stopped out
					if (trades.size() == 1) {
						// this is the first addition - we can try give this a shot
						JSONObject t = trades.get(0);
						double tOpenPrice = t.getDouble(PRICE);
						double tStopLoss = t.getDouble(STOP_LOSS);
						int tPsize = t.getInt(UNITS);

						// never add to winning positions
						if ((side.equals(BUY) && curPrice > tOpenPrice) || (side.equals(SELL) && curPrice < tOpenPrice)) {
							return;
						}
						// don't bother adding to position if stop loss is less than 10 pips away
						double priceToStopLoss = Math.abs(curPrice - tStopLoss);
						double pipsToStopPrice = (pair.contains(JPY)) ? priceToStopLoss * 100 : priceToStopLoss * 10000 ;
						if (pipsToStopPrice < 10) {
							return;
						}

						double portionLost = Math.abs(tOpenPrice - curPrice) / Math.abs(tOpenPrice - tStopLoss);
						double riskRemaining = 1 - portionLost; // % of the original 3.6% risk that remains from here
						double psizeIncreasePercent = ((double) (tPsize + psize)) / tPsize - 1; // % of original position size increase
						double riskPercentIncrease = riskRemaining * psizeIncreasePercent * ACC_STOPLOSS_PERCENT; // % of risk increase as per account

						if (riskPercentIncrease > (ACC_STOPLOSS_PERCENT * 0.4D)) { // at most, 3.6% -> 5.0%, or 40% increase in risk
							// bring position size down to this maximal risk increase
							double decrease = (ACC_STOPLOSS_PERCENT / 0.4D) / riskPercentIncrease;
							oandaPsize *= decrease;
						}

						// place the additional trade
						long tradeId = openTrade(side, oandaPsize, pair);
						// modify the trade and give it the same stop loss as first trade
						modifyTrade(tradeId, tStopLoss, NO_TRAILING_STOP);

					} else {
						// already added to this position, don't do it again (given our small stop-loss range, any further
						// additions to this currency position will most likely be stopped out very soon anyway)
						System.out.println("[SmartCopyStrategy] C2 added to position for " + pair +
								", but we already have 2 open for this pair and are not adding any more.");
					}

				} else if (orders.size() > 0) { // order was placed and is still there, this should rarely ever happen...
					System.out.println("[SmartCopyStrategy] C2 added to position but our limit order hasn't even popped. Weird! pair = " + pair);
					// don't do anything else, this is a very rare and strange situation: wait for manual intervention
				} else { // the pair has definitely been stopped out: remove from currentlyOpen and add to blacklist
					System.out.println("[SmartCopyStrategy] C2 added to position for pair " + pair +
						", but our position was already stopped out. Adding [" + pair + "] to blacklist.");
					currentlyOpen.remove(pair);
					blacklist.add(pair);
				}
				// return early
				return;
			}

			// check if the pair has any orders pending - this shouldn't ever happen
			ArrayList<JSONObject> olist = getOrders(pair);
			if (olist.size() > 0) {
				throw new RuntimeException("[SmartCopyStrategy] C2 opened position on " + pair + " but an order was pending - this should never happen");
			}

			// calculate appropriate stoploss for the trade
			double accCurrencyPerPip = getAccCurrencyPerPip(pair);
			double accCurrencyPerPipForTrade = accCurrencyPerPip * oandaPsize;
			int stopLossPips = (int) (accountBalance * ACC_STOPLOSS_PERCENT / accCurrencyPerPipForTrade);
			double stopLossPrice = pipsToPrice(pair, stopLossPips);

			// not in blacklist or currentlyOpen - this is a completely fresh trade, open normally
			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && curPrice < oprice) || (side.equals(SELL) && curPrice > oprice)) {
				// pip difference is at most negative 1 pip (for us)
				double stopLoss = curPrice;
				if (side.equals(BUY)) stopLoss -= stopLossPrice;
				else stopLoss += stopLossPrice;
				// actually place the trade
				long tradeId = openTrade(side, oandaPsize, pair);
				// modify the trade
				modifyTrade(tradeId, stopLoss, NO_TRAILING_STOP);
			} else { // pip differential too large, place limit order instead
				double d = pipsToPrice(pair, LIMIT_ORDER_PIPS_DIFF);
				if (side.equals(BUY)) d *= -1; // if buy, we want price to be LIMIT_ORDER_PIPS_DIFF pips *LOWER* for advantage
				double bound = oprice + d;
				double stopLoss = bound; // stopLoss is relative to bound, not curPrice, for limit orders
				if (side.equals(BUY)) stopLoss -= stopLossPrice;
				else stopLoss += stopLossPrice;
				// place the order
				long orderId = createOrder(side, oandaPsize, pair, bound);
				// modify the order and give it appropriate stop loss
				modifyOrder(orderId, stopLoss, NO_TRAILING_STOP);
			}
			// add pair to currentlyOpen, doesn't matter if market order or limit order
			currentlyOpen.add(pair);
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
