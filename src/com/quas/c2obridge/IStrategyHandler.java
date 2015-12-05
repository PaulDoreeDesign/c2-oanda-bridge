package com.quas.c2obridge;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.IOException;

/**
 * Created by Quasar on 2/12/2015.
 */
public interface IStrategyHandler {

	/** Our Oanda accounts' currency */
	public static final String ACC_CURRENCY = "AUD";

	/** Acceptable difference between C2's open price and current Oanda price, in pips */
	public static final int MAX_PIP_DIFF = 5; // 5 pips

	/** Current net account worth of the C2 strategy provider */
	public static final int C2_PROVIDER_NET_WORTH = 1200000; // 1.2mil

	/** The string to search for to select emails by title */
	public static final String SUBJECT_FIND = "[Collective2] Latest Trade Signals: Zip4x";
	/** The string to search for which marks the beginning of the relevant section */
	public static final String SEARCH_START_STRING = "Issued Action Quant Symbol Good Til Model Account Outcome";
	/** The String to search for which marks the end of the relevant section */
	public static final String SEARCH_STOP_STRING = "Copyright (C) 2015. All rights reserved.";

	/**
	 * Assorted useful currencies
	 */
	public static final String AUD_USD = "AUD_USD";
	public static final String JPY = "JPY";
	public static final String AUD = "AUD";

	/**
	 * Constants which represent values extracted from the emails.
	 */
	public static final String OPEN = "open";
	public static final String CLOSE = "close";
	public static final String BUY = "buy";
	public static final String SELL = "sell";

	/**
	 * Constants which represent values to be sent as property names
	 */
	public static final String INSTRUMENT = "instrument";
	public static final String UNITS = "units";
	public static final String SIDE = "side";
	public static final String TYPE = "type";
	public static final String ID = "id";
	public static final String BALANCE = "balance";
	public static final String STOP_LOSS = "stopLoss";
	public static final String TRAILING_STOP = "trailingStop";
	public static final String EXPIRY = "expiry";
	public static final String PRICE = "price";

	/**
	 * Constants which represent values to be sent as property values
	 */
	public static final String MARKET = "market";
	public static final String LIMIT = "limit";

	/**
	 * Flag passed to modifyTrade method to signify no trailing stop should be placed.
	 */
	public static final int NO_TRAILING_STOP = -1;

	/**
	 * Handles the given newly-received message.
	 *
	 * @param message the newly-received message
	 */
	public abstract void handleMessage(Message message) throws MessagingException, IOException;
}
