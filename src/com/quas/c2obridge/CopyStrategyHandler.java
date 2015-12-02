package com.quas.c2obridge;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Strategy #1:
 * - exact clone of C2 strategy with position sizing multiplied by constant
 *
 * Created by Quasar on 2/12/2015.
 */
public class CopyStrategyHandler extends AbstractStrategyHandler {

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 4;

	/**
	 * Called by handleMessage with the info extracted from the email.
	 *
	 * @param action the action being undertaken: OPEN or CLOSE
	 * @param side the side being undertaken: BUY or SELL
	 * @param psize the position size opened by C2
	 * @param pair the currency pair being traded
	 * @param oprice the price at which C2 opened the position
	 */
	public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double compare = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(compare - oprice));

		if (action == OPEN) {
			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && compare < oprice) || (side.equals(SELL) && compare > oprice)) {
				// pip difference is at most negative 5 pips (in direction of our favour)
				// try to place an order

				// get our position sizing
				int oandaPsize = convert(psize) * POS_SIZE_MULTIPLIER;
				// actually place the trade
				openTrade(side, oandaPsize, pair);
			} else {
				// missed opportunity
				System.err.println("missed opportunity to place order to " + side + " " + pair + " (pip diff = " + diff +
						" - actiontype = " + side + ", compare = " + compare + ", oprice = " + oprice + ")");
				// @TODO maybe place limit order?
			}
		} else {
			// try to close position instantly
			// @TODO confirm position sizing is equivalent to entire position
			// get all trades for this pair
			ArrayList<JSONObject> list = getTrades(pair); // json of all currently open trades for this pair
			for (JSONObject trade : list) {
				// close every trade returned for this pair
				long tradeId = trade.getLong(ID);
				closeTrade(tradeId);
			}
		}
	}
}
