package com.quas.c2obridge.strategy;

import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

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

	/** Initial stop-loss in pips */
	private static final int STOP_LOSS = 30;

	/** Trailing stop-loss in pips */
	private static final int TRAILING_STOP_LOSS = 30;

	/**
	 * Constructor for the reverse strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public ReverseStrategyHandler(int accountId) {
		super(accountId);
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
	public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double curPrice = getOandaPrice(side, pair);
		double diff = roundPips(pair, Math.abs(curPrice - oprice));

		// check if any trades for this pair are already opened. if so, ignore
		List<JSONObject> alreadyOpen = getTrades(pair);
		if (alreadyOpen.size() > 0) {
			// return early without doing anything
			return;
		}

		if (action.equals(OPEN)) {
			// flip side
			side = side.equals(BUY) ? SELL : BUY;

			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && curPrice < oprice) || (side.equals(SELL) && curPrice > oprice)) {
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

				modifyTrade(id, stopLoss, TRAILING_STOP_LOSS);
			} else {
				// missed opportunity
				Logger.error("[ReverseStrategy] missed opportunity to place order to " + side + " " + pair + " (pip diff = " + diff +
						" - actiontype = " + side + ", curPrice = " + curPrice + ", oprice = " + oprice + ")");
			}
		}
		// reverse strategy does not close trades manually - always wait to hit stop-loss (trailing or initial)
	}
}
