package com.quas.c2obridge;

import java.io.IOException;

/**
 * Strategy #3:
 * - does the exact opposite of what the C2 strategy provider does
 * - initial stop-loss flat 15 pips for now, equivalent to ~$1000 when position size of C2 = 1,000,000 (100 x 10,000)
 * - no take-profit: use trailing stop-loss of 30 pips
 *
 * Created by Quasar on 3/12/2015.
 */
public class ReverseStrategyHandler extends AbstractStrategyHandler {

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 8;

	/** Stop-loss in pips */
	private static final int STOP_LOSS = 15;

	/** Trailing stop-loss in pips */
	private static final int TRAILING_STOP_LOSS = 30;

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
		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		if (action.equals(OPEN)) {
			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && curPrice > oprice) || (side.equals(SELL) && curPrice < oprice)) {
				// pip difference is at most positive 5 pips (in direction of C2's favour), so try to place an order
				// flip side first
				side = side.equals(BUY) ? SELL : BUY;

				// get our position sizing
				int oandaPsize = convert(psize) * POS_SIZE_MULTIPLIER;
				// actually place the trade
				long id = openTrade(side, oandaPsize, pair); // id = id of the trade that is returned once it is placed

				// modify the trade to give it a stop-loss
				double stopLoss = side.equals(BUY) ? -STOP_LOSS : STOP_LOSS;
				stopLoss += curPrice;
				modifyTrade(id, stopLoss, TRAILING_STOP_LOSS);
			} else {
				// missed opportunity
				System.err.println("[ReverseStrategy] missed opportunity to place order to " + side + " " + pair + " (pip diff = " + diff +
						" - actiontype = " + side + ", curPrice = " + curPrice + ", oprice = " + oprice + ")");
			}
		}
		// reverse strategy does not close trades manually - always wait to hit stop-loss (trailing or initial)
	}
}
