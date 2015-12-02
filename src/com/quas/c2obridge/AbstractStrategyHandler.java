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
import java.util.Properties;

/**
 * Created by Quasar on 2/12/2015.
 */
public abstract class AbstractStrategyHandler implements StrategyHandler {

	/** Oanda API details */
	public static final String OANDA_API_KEY;
	public static final String OANDA_API_URL;
	public static final int OANDA_ACCOUNT_ID;

	// load from props file
	static {
		Properties oandaProps = new Properties();
		try {
			oandaProps.load(new FileInputStream(new File("oanda.properties")));
		} catch (IOException ioe) {
			System.err.println("Error loading config.properties file: " + ioe);
			ioe.printStackTrace(System.err);
			System.exit(0);
		}

		OANDA_API_KEY = oandaProps.getProperty("API_KEY");
		OANDA_API_URL = oandaProps.getProperty("API_URL");
		if (OANDA_API_KEY == null || OANDA_API_URL == null) {
			throw new RuntimeException("should not be null: API_KEY = " + OANDA_API_KEY + ", API_URL = " + OANDA_API_URL);
		}
		OANDA_ACCOUNT_ID = Integer.parseInt(oandaProps.getProperty("ACC_ID"));
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
			System.out.println(relevant);
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
	 * Gets the balance of this account.
	 *
	 * @return the balance
	 */
	public double getAccountBalance() {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + OANDA_ACCOUNT_ID, "GET", OANDA_API_KEY);
		JSONObject json = new JSONObject(con.getResponse());
		return json.getDouble(BALANCE);
	}

	/**
	 * Places a spot trade given the side (buy or sell), position size in units, and currency pair.
	 *
	 * @param side buy or sell
	 * @param psize position size (in units)
	 * @param pair currency pair
	 */
	public void openTrade(String side, int psize, String pair) {
		HashMap<String, String> props = new LinkedHashMap<String, String>();
		props.put(INSTRUMENT, pair);
		props.put(UNITS, Integer.toString(psize));
		props.put(SIDE, side);
		props.put(TYPE, MARKET);
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + OANDA_ACCOUNT_ID + "/orders", Connector.POST, OANDA_API_KEY, props);

		String response = con.getResponse();
		// @TODO check response to make sure trade was successfully placed
		// System.err.println(response);
	}

	/**
	 * Gets all open trades for the given currency pair. (Max is 500 but this should never be reached unless some
	 * crazy stupid shit is being done...)
	 *
	 * @param pair the currency pair to search for
	 * @return ArrayList<JSONObject> arraylist of JSONObjects that were in the response
	 */
	public ArrayList<JSONObject> getTrades(String pair) {
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + OANDA_ACCOUNT_ID + "/trades?instrument=" + pair + "&count=500", Connector.GET, OANDA_API_KEY);
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
		Connector con = new Connector(OANDA_API_URL + "/v1/accounts/" + OANDA_ACCOUNT_ID + "/trades/" + tradeId, Connector.DELETE, OANDA_API_KEY);
		String response = con.getResponse();
		// @TODO check response to make sure close was successful
		// System.out.println(response);
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
