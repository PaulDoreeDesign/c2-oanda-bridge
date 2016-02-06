package com.quas.c2obridge.strategy;

import com.quas.c2obridge.C2OBridge;
import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Strategy #3:
 * - does the exact opposite of what the C2 strategy provider does
 * - initial stop-loss flat 15 pips for now, equivalent to ~$1000 when position size of C2 = 1,000,000 (100 x 10,000)
 * - no take-profit: use trailing stop-loss of 30 pips
 *
 * Created by Quasar on 3/12/2015.
 */
public class ReverseStrategyHandler extends StrategyHandler {

	/** Percentage of account to risk with every trade */
	private static final int RISK_PERCENTAGE_PER_TRADE = 2;

	/** Custom pip diff for reverse strategy only */
	private static final int REVERSE_MAX_PIP_DIFF = 10; // 10 pips

	/** Initial stop-loss in pips */
	private static final int STOP_LOSS = 25;

	/** Trailing stop-loss in pips */
	private static final int TRAILING_STOP_LOSS = 50;

	/** Filename for this strategy */
	private static final String REVERSE_FILE = "reverse.properties";

	/** Property name for currently open on C2 */
	private static final String OPEN_ON_C2 = "OPEN_ON_C2";

	/** Set that contains all pairs that are currently open on C2 */
	private Set<String> openOnC2;

	/**
	 * Constructor for the reverse strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public ReverseStrategyHandler(int accountId) {
		super(accountId);

		this.openOnC2 = new HashSet<String>();
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
		} catch (IOException ioe) {
			Logger.error("Error loading reverse.properties save data: " + ioe);
			ioe.printStackTrace(Logger.err);
			C2OBridge.crash();
		} catch (RuntimeException re) {
			Logger.error("Error loading reverse.properties save data, corruption: " + re);
			re.printStackTrace(Logger.err);
			C2OBridge.crash();
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
	public final void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		// check if any trades for this pair are already opened. if so, ignore
		List<JSONObject> alreadyOpen = getTrades(pair);
		if (alreadyOpen.size() > 0) {
			// return early without doing anything
			return;
		}

		if (action.equals(OPEN)) {
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

					// commented lines below are for splitting reverse strategy into take-profit and trailing-stop
					/*
					// work out the take profit
					double takeProfit = curPrice;
					netPips = pipsToPrice(pair, STOP_LOSS * 2);
					if (side.equals(BUY)) takeProfit += netPips;
					else takeProfit -= netPips;
					if (isTrailingReverseStrategy()) {
						// give it initial stop loss and the trailing stop loss. no take profit
						modifyTrade(id, stopLoss, TRAILING_STOP_LOSS);
					} else if (isTakeProfitReverseStrategy()) {
						// give it initial stop loss but no trailing stop loss. give it take profit
						modifyTrade(id, stopLoss, NO_TRAILING_STOP);
						setTakeProfit(id, takeProfit);
					} else {
						throw new RuntimeException("Both isTrailingReverseStrategy() and isTakeProfitReverseStrategy() return false");
					}
					*/
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
			if (c.length() > 0) {
				c += ",";
			}
			c += s;
		}
		props.put(OPEN_ON_C2, c);

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
