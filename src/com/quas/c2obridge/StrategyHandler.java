package com.quas.c2obridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

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
	 * Checks that the extracted important piece of the email is valid.
	 *
	 * If not valid, throws RuntimeException and the program will terminate.
	 *
	 * @param parts the extracted string
	 */
	public void verify(String[] parts) {
		if (parts.length != 21) throw new RuntimeException("parts.length is invalid: " + parts.length);
		Integer.parseInt(parts[0]); // NumberFormatException if index 0 isn't a valid id
		// if (!parts[1].matches()) throw new RuntimeException("Invalid date: " + parts[1]); @TODO: regex match
		// 1,2 @TODO: check date and time are 16 hours before now (if sydney)
		if (!parts[3].equals("ET")) throw new RuntimeException("parts[3] isn't 'ET': " + parts[3]);
		// 4, 5, 6, 7 are the actions (buy/sell) to (go/close) (short/long)
		int size = Integer.parseInt(parts[8].replace(",", "")); // NumberFormatException if can't parse after removing comma
		if (size % 10000 != 0) throw new RuntimeException("Invalid raw position size: " + size);
		if (!parts[9].contains("/") || parts[9].length() != 7) throw new RuntimeException("invalid currency pair: " + parts[9]);
		if (!parts[10].equals("market") || !parts[11].equals("order")) throw new RuntimeException("not 'market order': " + parts[10] + " " + parts[11]);

		String combined = parts[12] + " " + parts[13] + " " + parts[14] + " " + parts[15] + " " + parts[16];
		if (!combined.equals("Good Til Cancel traded at")) throw new RuntimeException ("not 'Good Til Cancel traded at': " + combined);

		// price opened at by c2
		Double.parseDouble(parts[17]); // NumberFormatException if this number isn't parsable as a double

		if (!parts[18].equals(parts[1]) || !parts[19].equals(parts[2])) {
			throw new RuntimeException("dates aren't equal: " + parts[18] + " != " + parts[1] + ", " + parts[19] + " != " + parts[2]);
		}

		if (!parts[20].equals("ET")) throw new RuntimeException("parts[20] isn't ET: " + parts[20]);
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
			// System.out.println(relevant);
			String[] parts = relevant.trim().split("\\s+");

			if (parts.length == 22) { // get rid of "position" at position 8
				String[] partsTemp = new String[parts.length - 1];
				for (int i = 0; i < parts.length; i++) {
					if (i == 8) continue;
					if (i > 8) {
						partsTemp[i - 1] = parts[i];
					} else {
						partsTemp[i] = parts[i];
					}
				}
				parts = partsTemp;
			}

			verify(parts);

			// String date = parts[1];
			// String time = parts[2];
			String action = (parts[6].equals("go") || parts[6].equals("open")) ? OPEN : CLOSE;
			String side = parts[4].toLowerCase();
			int psize = Integer.parseInt(parts[8].replace(",", "")); // units
			String pair = parts[9].replace("/", "_");
			double oprice = Double.parseDouble(parts[17]); // opening price by C2
			//System.out.println("date = " + date + ", time = " + time + ", action = " + action + ", type = " + type +
			//	", psize = " + psize + ", pair = " + pair + ", oprice = " + oprice + "\n----");

			try {
				handleInfo(action, side, psize, pair, oprice);
			} catch (IOException ioe) {
				System.err.println("Error in StrategyHandler.handleInfo: " + ioe);
				ioe.printStackTrace(System.err);
			}
		}
	}

	/**
	 * Manages conversion from C2's units to our Oanda account's position sizing.
	 *
	 * Takes into account the difference in account sizes, and currencies.
	 *
	 * @param units the number of units placed by the C2 trader
	 * @return number of units to place via Oanda API at a 1:1 ratio
	 */
	public int convert(int units) {
		double audUsdPrice = getOandaPrice(BUY, AUD_USD);
		double accountBalance = getAccountBalance() * audUsdPrice; // multiply by AUDUSD price to get our account balance in USD
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
		double ret = (side == BUY) ? ask : bid;

		return ret;
	}

	/**
	 * Rounds the given pips to the nearest 0.1 pips.
	 *
	 * @param diff the pips to round
	 * @return the pips, rounded correctly
	 */
	public double roundPips(String pair, double diff) {
		if (pair.contains(JPY)) diff *= 100;
		else diff *= 10000;
		diff = Math.round(diff * 10) / 10.0; // round to 1 decimal place
		return diff;
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
	 * Gets the balance of this account.
	 *
	 * @return the balance
	 */
	public double getAccountBalance() {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId, "GET", OANDA_API_KEY);
		JSONObject json = new JSONObject(con.getResponse());
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
		String expire = ""; // @TODO

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
		return json.getLong("id");
	}

	/**
	 * Gets all open trades for the given currency pair. (Max is 500 but this should never be reached unless some
	 * crazy stupid shit is being done...)
	 *
	 * @param pair the currency pair to search for
	 * @return ArrayList<JSONObject> arraylist of JSONObjects that were in the response
	 */
	public ArrayList<JSONObject> getTrades(String pair) {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades?instrument=" + pair + "&count=500", Connector.GET, OANDA_API_KEY);
		JSONArray arr = new JSONObject(con.getResponse()).getJSONArray("trades");
		ArrayList<JSONObject> list = new ArrayList<JSONObject>();
		for (int i = 0; i < arr.length(); i++) {
			list.add(arr.getJSONObject(i));
		}
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
		// System.out.println(response);
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
		params.put(TRAILING_STOP, Double.toString(trailingStop));
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + accountId + "/trades/" + tradeId, Connector.PATCH, OANDA_API_KEY, params);
		String response = con.getResponse();
		// @TODO check response to make sure close was successful
		// @TODO verify that this method works
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
