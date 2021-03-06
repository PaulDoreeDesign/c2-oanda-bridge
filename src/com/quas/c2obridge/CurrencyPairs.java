package com.quas.c2obridge;

/**
 * Contains a list of all the currency pairs.
 *
 * Created by Quasar on 5/12/2015.
 */
public class CurrencyPairs {

	private static final String[] ALL_PAIRS = {
			"AUD_CAD",
			"AUD_CHF",
			"AUD_JPY",
			"AUD_NZD",
			"AUD_USD",
			"CAD_CHF",
			"CAD_JPY",
			"CHF_JPY",
			"EUR_AUD",
			"EUR_CAD",
			"EUR_CHF",
			"EUR_GBP",
			"EUR_JPY",
			"EUR_NZD",
			"EUR_USD",
			"GBP_AUD",
			"GBP_CAD",
			"GBP_CHF",
			"GBP_JPY",
			"GBP_NZD",
			"GBP_USD",
			"NZD_CAD",
			"NZD_CHF",
			"NZD_JPY",
			"NZD_USD",
			"USD_CAD",
			"USD_CHF",
			"USD_JPY"
	};

	private CurrencyPairs() {}

	/**
	 * Checks that a pair with the two given currencies exists.
	 *
	 * @return true if the pair exists, false if not
	 */
	public static boolean isPair(String c1, String c2) {
		if (c1.equals(c2)) return false;
		for (String s : ALL_PAIRS) {
			if (s.contains(c1) && s.contains(c2)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gets the pair with the two given currencies.
	 *
	 * @return the pair, in the correct order
	 */
	public static String getPair(String c1, String c2) {
		if (c1.equals(c2)) throw new RuntimeException("Invalid pair, the two sides of the 'pair' are both " + c1 + "...");
		for (String s : ALL_PAIRS) {
			if (s.contains(c1) && s.contains(c2)) {
				return s;
			}
		}
		throw new RuntimeException("Invalid pairs...: " + c1 + " / " + c2);
	}
}
