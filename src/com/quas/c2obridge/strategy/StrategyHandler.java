package com.quas.c2obridge.strategy;

import com.quas.c2obridge.C2OBridge;
import com.quas.c2obridge.Connector;
import com.quas.c2obridge.CurrencyPairs;
import com.quas.c2obridge.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.quas.c2obridge.C2OBridge.*;

/**
 * Abstract base for strategy handlers.
 *
 * Created by Quasar on 2/12/2015.
 */
public abstract class StrategyHandler implements IStrategyHandler {

	/** Oanda account ID for this strategy */
	private final int accountId;

	/**
	 * Constructor for a strategy. Locks in the account id for this strategy upon creation.
	 *
	 * @param accountId the account id for this strategy
	 */
	public StrategyHandler(int accountId) {
		this.accountId = accountId;
	}

	/**
	 * Rounds value to 4 decimal places.
	 */
	public static double round4(double number) {
		String[] split = Double.toString(number).split("\\.");
		String decimalsOriginal = split[1];
		String decimals = decimalsOriginal;
		boolean extraPip = false; // true = round up
		if (decimalsOriginal.length() > 4) {
			decimals = decimalsOriginal.substring(0, 4);
			int fifthDecimalPlace = Integer.parseInt(decimalsOriginal.substring(4, 5));
			extraPip = (fifthDecimalPlace >= 5);
		}
		double ret = Double.parseDouble(split[0] + "." + decimals);
		if (extraPip) {
			ret += 0.0001d;
		}
		Logger.info("round4(" + number + ") returned " + ret);
		return ret;
	}

	/**
	 * Rounds value to 2 decimal places.
	 */
	public static double round2(double number) {
		String[] split = Double.toString(number).split("\\.");
		String decimalsOriginal = split[1];
		String decimals = decimalsOriginal;
		boolean extraPip = false; // true = round up
		if (decimalsOriginal.length() > 2) {
			decimals = decimalsOriginal.substring(0, 2);
			int thirdDecimalPlace = Integer.parseInt(decimalsOriginal.substring(2, 3));
			extraPip = (thirdDecimalPlace >= 5);
		}
		double ret = Double.parseDouble(split[0] + "." + decimals);
		if (extraPip) {
			ret += 0.01d;
		}
		Logger.info("round2(" + number + ") returned " + ret);
		return ret;
	}

	/**
	 * Validates the given pair by throwing an exception if it's invalid.
	 *
	 * @param s the pair string
	 */
	public final void validateCurrencyPair(String s) {
		if (s.length() != 7 || !s.contains("_") || s.split("_").length != 2 || s.split("_")[0].length() != 3) {
			throw new RuntimeException("invalid currency pair: " + s);
		}
	}

	/**
	 * Extracts the relevant information from the newly-received message, and calls the implementing strategy
	 * handler.
	 *
	 * @param message the newly-received message
	 */
	@Override
	public final void handleMessage(Message message) throws MessagingException, IOException {
		String subject = message.getSubject();
		if (subject.equals(SUBJECT_FIND)) { // signal message
			BufferedReader reader = new BufferedReader(new InputStreamReader(message.getInputStream()));
			String body = "";
			String line;
			while ((line = reader.readLine()) != null) {
				body += line;
			}
			body = Jsoup.parse(body).text();

			int start = body.indexOf(SEARCH_START_STRING) + SEARCH_START_STRING.length();
			int stop = body.indexOf(SEARCH_STOP_STRING);
			String relevant = body.substring(start, stop);
			String[] parts = relevant.trim().split("\\s+");
			boolean multi = parts.length > 28; // whether or not this is a multi email

			String action;
			String side;
			int psize;
			String pair;
			double oprice;

			try {
				boolean reachedEnd = false;
				int i = 0;
				while (!reachedEnd) {
					Integer.parseInt(parts[i]);
					i += 3;
					if (!parts[i].equals("ET"))
						throw new RuntimeException("'ET' not in expected position, got " + parts[i] + " instead");
					i++;

					String actionLine = "";

					while (!parts[i].contains(",")) {
						actionLine += parts[i] + " ";
						i++;
					}
					String[] actionsSplit = actionLine.split(" ");
					if (actionsSplit.length == 4 || actionsSplit.length == 5) {
						side = actionsSplit[0].toLowerCase();
						action = (actionsSplit[2].equals("go") || actionsSplit[2].equals("open")) ? OPEN : CLOSE;
					} else {
						throw new RuntimeException("actionsSplit.length not 4 or 5. length = " + actionsSplit.length + ", actionLine = " + actionLine);
					}

					psize = Integer.parseInt(parts[i].replace(",", ""));
					i++;
					pair = parts[i].replace("/", "_");
					i++;

					String marketOrder = "";
					marketOrder += parts[i] + " ";
					i++;
					marketOrder += parts[i];
					i++;
					if (!marketOrder.equals("market order"))
						throw new RuntimeException("marketOrder invalid: " + marketOrder);

					String goodTil = "";
					while (!parts[i].equals("traded")) {
						if (!goodTil.equals("")) goodTil += " ";
						goodTil += parts[i];
						i++;
					}
					if (!goodTil.equals("Good Til Cancel") && !goodTil.equals("day order")) {
						throw new RuntimeException("Invalid value for goodTil: " + goodTil);
					}

					if (!parts[i].equals("traded")) throw new RuntimeException("expected 'traded', got: " + parts[i]);
					i++;
					if (!parts[i].equals("at")) throw new RuntimeException("expected 'at', got: " + parts[i]);
					i++;

					oprice = Double.parseDouble(parts[i]);

					// handle the info directly if the message is a standard, one-trade message
					// otherwise, only do the closes (do not touch opens in a multi)
					if (!multi || action.equals(CLOSE)) {
						try {
							handleInfo(action, side, psize, pair, oprice);
						} catch(Exception e) {
							// catch ALL exceptions: handleInfo can throw IOExceptions AND RuntimeExceptions
							Logger.error("Error in StrategyHandler.handleInfo: " + e);
							e.printStackTrace(Logger.err);
						}
					}

					i += 4; // will be pointing to the id of the next trade if multitrade

					reachedEnd = (i >= parts.length);
				}


			} catch (RuntimeException re) {
				Logger.error("Extraction failed, entire line = " + relevant + ", index 0 = " + parts[0]);
				re.printStackTrace(Logger.err);
			}
		} else {
			Logger.info("Not a trade message, ignoring.");
		}
	}

	/**
	 * Manages conversion from C2's units to our Oanda account's position sizing.
	 *
	 * Takes into account the difference in account sizes, and currencies.
	 *
	 * @param units the number of units placed by the C2 trader
	 * @param accountBalanceAUD the current balance of our account on Oanda
	 * @return number of units to place via Oanda API at a 1:1 ratio
	 *
	 * @TODO make this method independent of account currency type
	 */
	public int convert(int units, double accountBalanceAUD) {
		double audUsdPrice = getOandaPrice(BUY, AUD_USD);
		double accountBalance = accountBalanceAUD * audUsdPrice; // multiply by AUDUSD price to get our account balance in USD
		double times = C2_PROVIDER_NET_WORTH / accountBalance; // the no. of times that the C2 account is larger than ours

		return (int) (units / times);
	}

	/**
	 * Given a currency pair and side (buy or sell), checks with Oanda's REST API and returns the relevant price. (If
	 * side is BUY, returns ask price, if side is SELL, returns bid price.
	 *
	 * @param side whether we want to buy or sell
	 * @param pair the currency pair to check
	 * @return the ask or bid price (depending on supplied side)
	 */
	public double getOandaPrice(String side, String pair) {
		Connector con = new Connector(OANDA_API_URL + "/v1/prices?instruments=" + pair, Connector.GET, OANDA_API_KEY);
		String response = con.getResponse();
		JSONObject json = new JSONObject(response).getJSONArray("prices").getJSONObject(0);
		double ask = json.getDouble("ask");
		double bid = json.getDouble("bid");
		double ret = (side.equals(BUY)) ? ask : bid;
		C2OBridge.sleep(500); // sleep for half a second after every request
		return ret;
	}

	/**
	 * Given a currency pair, checks with Oanda's REST API and returns the median price between the bid and ask prices.
	 *
	 * @param pair the currency pair to check
	 * @return the median price between the bid and ask prices
	 */
	public double getMedianOandaPrice(String pair) {
		Connector con = new Connector(OANDA_API_URL + "/v1/prices?instruments=" + pair, Connector.GET, OANDA_API_KEY);
		String response = con.getResponse();
		JSONObject json = new JSONObject(response).getJSONArray("prices").getJSONObject(0);
		double ask = json.getDouble("ask");
		double bid = json.getDouble("bid");
		C2OBridge.sleep(500); // sleep for half a second after every request
		return (ask + bid) / 2;
	}

	/**
	 * Converts the given pips into price. (1 pip = 0.0001 for most currencies, 1 pip = 0.01 for JPY currencies)
	 *
	 * @param pair the currency pair
	 * @param pips the number of pips
	 * @return the price
	 */
	public double pipsToPrice(String pair, double pips) {
		if (pair.contains(JPY)) return (pips / 100);
		else return (pips / 10000);
	}

	/**
	 * Converts the given price to pips. (0.0001 currency = 1 pip for most currencies, 0.01 currency = 1 pip for JPY currencies)
	 *
	 * @param pair the currency pair
	 * @param price the price
	 * @return the number of pips
	 */
	public double priceToPips(String pair, double price) {
		if (pair.contains(JPY)) return price * 100;
		else return price * 10000;
	}

	/**
	 * Gets the balance of this account.
	 *
	 * @return the balance
	 */
	public double getAccountBalance() {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId, "GET", OANDA_API_KEY);
		JSONObject json = new JSONObject(con.getResponse());
		C2OBridge.sleep(500); // sleep for half a second after every request
		return json.getDouble(BALANCE);
	}

	/**
	 * Places a spot trade given the side (buy or sell), position size in units, and currency pair.
	 *
	 * Throws an exception if the trade did not go through successfully. @TODO
	 *
	 * @param side buy or sell
	 * @param psize position size (in units)
	 * @param pair currency pair
	 * @return the id of the trade that was just opened
	 */
	public long openTrade(String side, int psize, String pair) {
		HashMap<String, String> props = new LinkedHashMap<String, String>();
		props.put(INSTRUMENT, pair);
		props.put(UNITS, Integer.toString(psize));
		props.put(SIDE, side);
		props.put(TYPE, MARKET);
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/orders", Connector.POST, OANDA_API_KEY, props);

		String response = con.getResponse();
		JSONObject json = new JSONObject(response).getJSONObject("tradeOpened");
		// if (json == null) throw new RuntimeException("Tried to open trade but it failed: pair = " + pair);
		C2OBridge.sleep(500); // sleep for half a second after every request
		return json.getLong("id");
	}

	/**
	 * Creates a limit order. Expiry is set at 24 hours (although they are deleted upon C2 close order, if before that).
	 *
	 * @param side the side (buy or sell)
	 * @param psize position size in units
	 * @param pair currency pair
	 * @param bound the bound at which to execute the trade
	 * @return the id of the order that was just opened
	 */
	public long createOrder(String side, int psize, String pair, double bound) {
		// UTC date time string representing 24 hours
		DateFormat yearFormat = new SimpleDateFormat("yyyy");
		DateFormat monthFormat = new SimpleDateFormat("MM");
		Date curDate = new Date();
		int year = Integer.parseInt(yearFormat.format(curDate));
		int month = Integer.parseInt(monthFormat.format(curDate));
		// increment month by 1
		month++;
		if (month > 12) {
			year++;
			month -= 12;
		}
		String monthString = Integer.toString(month);
		if (monthString.length() == 1) monthString = "0" + monthString;
		String expire = year + "-" + monthString + "-06T12:00:00Z"; // just always do a month in advance

		HashMap<String, String> props = new LinkedHashMap<String, String>();
		props.put(INSTRUMENT, pair);
		props.put(UNITS, Integer.toString(psize));
		props.put(SIDE, side);
		props.put(TYPE, LIMIT);
		props.put(EXPIRY, expire);
		props.put(PRICE, Double.toString(bound));
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/orders", Connector.POST, OANDA_API_KEY, props);

		String response = con.getResponse();
		JSONObject json = new JSONObject(response).getJSONObject("orderOpened");
		C2OBridge.sleep(500); // sleep for half a second after every request
		return json.getLong("id");
	}

	/**
	 * Gets all open trades for the given currency pair. (Max is 50 but this should never be reached unless something
	 * has gone SERIOUSLY out of whack...
	 *
	 * @param pair the currency pair to search for, or null to search every trade, regardless of currency pair
	 * @return arraylist of JSONObjects that were in the response
	 */
	public List<JSONObject> getTrades(String pair) {
		String pairParam = (pair == null) ? "" : "instrument=" + pair + "&";
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades?" + pairParam + "count=50", Connector.GET, OANDA_API_KEY);
		JSONArray arr = new JSONObject(con.getResponse()).getJSONArray("trades");
		ArrayList<JSONObject> list = new ArrayList<JSONObject>();
		for (int i = 0; i < arr.length(); i++) {
			list.add(arr.getJSONObject(i));
		}
		C2OBridge.sleep(500); // sleep for half a second after every request
		return list;
	}

	/**
	 * Gets all pending orders on Oanda for the given currency pair.
	 *
	 * @param pair the currency pair to search for
	 * @return arraylist of JSONObjects that were in the response
	 */
	public ArrayList<JSONObject> getOrders(String pair) {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/orders?instrument=" + pair + "&count=500", Connector.GET, OANDA_API_KEY);
		JSONArray arr = new JSONObject(con.getResponse()).getJSONArray("orders");
		ArrayList<JSONObject> list = new ArrayList<JSONObject>();
		for (int i = 0; i < arr.length(); i++) {
			list.add(arr.getJSONObject(i));
		}
		C2OBridge.sleep(500); // sleep for half a second after every request
		return list;
	}

	/**
	 * Submits a request to Oanda's REST API to close the trade with the given id.
	 *
	 * @param tradeId
	 */
	public void closeTrade(long tradeId) {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades/" + tradeId, Connector.DELETE, OANDA_API_KEY);
		String response = con.getResponse();
		// @TODO check response to make sure close was successful
		C2OBridge.sleep(500); // sleep for half a second after every request
	}

	/**
	 * Submits a request to Oanda's REST API to delete the order with the given id.
	 *
	 * @param orderId
	 */
	public void deleteOrder(long orderId) {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/orders/" + orderId, Connector.DELETE, OANDA_API_KEY);
		String response = con.getResponse();
		// @TODO check response to make sure delete was successful
		C2OBridge.sleep(500); // sleep for half a second after every request
	}

	/**
	 * Modifies an existing trade, giving it a stop loss and a trailing stop.
	 *
	 * @param tradeId the id of the trade to modify
	 * @param stopLoss initial stop loss in pips
	 * @param trailingStop trailing stop loss in pips
	 */
	public void modifyTrade(long tradeId, double stopLoss, double trailingStop) {
		HashMap<String, String> params = new LinkedHashMap<String, String>();
		params.put(STOP_LOSS, Double.toString(stopLoss));
		if (trailingStop != NO_TRAILING_STOP) {
			params.put(TRAILING_STOP, Double.toString(trailingStop));
		}
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades/" + tradeId, Connector.PATCH, OANDA_API_KEY, params);
		String response = con.getResponse();
		C2OBridge.sleep(500); // sleep for half a second after every request
	}

	/**
	 * Modifies an existing trade, giving it a take profit price.
	 *
	 * @param tradeId the id of the trade to modify
	 * @param takeProfit the price at which to take profit and close out the trade automatically
	 */
	public void setTakeProfit(long tradeId, double takeProfit) {
		HashMap<String, String> params = new LinkedHashMap<String, String>();
		params.put(TAKE_PROFIT, Double.toString(takeProfit));
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades/" + tradeId, Connector.PATCH, OANDA_API_KEY, params);
		String response = con.getResponse();
		C2OBridge.sleep(500); // sleep for half a second after every request
	}

	/**
	 * Modifies an existing order, giving it a stop loss, and a trailing stop (if the param isn't NO_TRAILING_STOP).
	 *
	 * @param orderId the id of the order to modify
	 * @param stopLoss initial stop loss in pips
	 * @param trailingStop trailing stop loss in pips, or NO_TRAILING_STOP
	 */
	public void modifyOrder(long orderId, double stopLoss, double trailingStop) {
		HashMap<String, String> params = new LinkedHashMap<String, String>();
		params.put(STOP_LOSS, Double.toString(stopLoss));
		if (trailingStop != NO_TRAILING_STOP) {
			params.put(TRAILING_STOP, Double.toString(trailingStop));
		}
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/orders/" + orderId, Connector.PATCH, OANDA_API_KEY, params);
		String response = con.getResponse();
		// @TODO check response to make sure close was successful
		// @TODO verify that this method works
		C2OBridge.sleep(500); // sleep for half a second after every request
	}

	/**
	 * Calculates how much each pip of the given pair is worth, in terms of our account's currency ACC_CURRENCY.
	 *
	 * @param pair the currency pair of which we are trying to calculate the per-pip value in terms of our account's currency
	 * @return the amount of our account's currency each pip of the given pair is worth
	 */
	public double getAccCurrencyPerPip(String pair) {
		String[] sides = pair.split("_");
		if (sides.length != 2) throw new RuntimeException(pair + " split over '_' length is " + sides.length);

		String base = sides[0];
		// get currency exchange rate for the pair
		double pairRate = getMedianOandaPrice(pair);
		double pip = 0.0001; // value of one pip
		if (pair.contains(JPY)) pip = 0.01; // JPY pairs = pip is 0.01

		double pipValueBase = pip / pairRate; // value per pip in terms of the base currency

		// find value of currency pair accountCurrency/baseCurrency
		String accBase;
		String[] accBaseSplit;
		double accBasePrice;
		boolean accCurIsBase;
		if (!ACC_CURRENCY.equals(base)) {
			accBase = CurrencyPairs.getPair(ACC_CURRENCY, base);
			accBaseSplit = accBase.split("_");
			accBasePrice = getMedianOandaPrice(accBase);
			accCurIsBase = ACC_CURRENCY.equals(accBaseSplit[0]);
		} else {
			accBasePrice = 1; // base is equal to acc currency
			accCurIsBase = true;
		}
		double ret;
		if (accCurIsBase) { // acc currency is base of accBase pair
			ret = pipValueBase / accBasePrice;
		} else { // acc currency is quote of accBase pair
			ret = pipValueBase * accBasePrice;
		}
		return ret;
	}

	/**
	 * Alerts this strategy that the application is shutting down.
	 */
	public void shutdown() {
		// do nothing
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
	public abstract void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException;
}
