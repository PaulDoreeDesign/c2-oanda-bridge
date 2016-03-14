package com.quas.c2obridge.strategy;

import com.quas.c2obridge.C2OBridge;
import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * A strategy which relies on manual entries for trades. Trades can only be entered if the corresponding position has
 * been opened on C2.
 *
 * Once a trade has been manually entered, this strategy will then automatically match the additions to that position
 * upon notification from C2, proportional to account size. The strategy will also close the position when the same is
 * done on C2.
 *
 * Created by Quasar on 1/03/16.
 */
public class ManualEntryStrategyHandler extends StrategyHandler {

	private static final int C2_ONE_TO_UNITS = 10000;

    /** Properties file */
	private static final String MANUAL_ENTRY_FILE = "manualentry.properties";
    private static final String LONG_ON_C2 = "LONG_ON_C2";
    private static final String SHORT_ON_C2 = "SHORT_ON_C2";
    private static final String CURRENTLY_OPEN = "CURRENTLY_OPEN";

    /** Collections of positions that are short and long on C2 currently */
    // key: pair, value: position size
    private Map<String, Integer> longOnC2;
    private Map<String, Integer> shortOnC2;
    /** Collection of positions that we have currently entered */
    private Set<String> currentlyOpen;

    public ManualEntryStrategyHandler(int accountId) {
        super(accountId);

		// initialise collections
		this.longOnC2 = new HashMap<String, Integer>();
		this.shortOnC2 = new HashMap<String, Integer>();
		this.currentlyOpen = new HashSet<String>();

        // load from file
		Properties props = new Properties();
		try {
			props.load(new FileInputStream(new File(MANUAL_ENTRY_FILE)));
			// load long on C2s
			String[] longOnC2Line = props.getProperty(LONG_ON_C2).split(",");
			for (String entry : longOnC2Line) {
				if (entry.trim().equals("")) continue;
				String[] parts = entry.split("/");
				String pair = parts[0];
				int size = Integer.parseInt(parts[1]);
				pair = pair.trim().toUpperCase();
				validateCurrencyPair(pair);
				// looks good, add to longOnC2
				longOnC2.put(pair, size);
			}
			// load shortOnC2s
			String[] shortOnC2Line = props.getProperty(SHORT_ON_C2).split(",");
			for (String entry : shortOnC2Line) {
				if (entry.trim().equals("")) continue;
				String[] parts = entry.split("/");
				String pair = parts[0];
				int size = Integer.parseInt(parts[1]);
				pair = pair.trim().toUpperCase();
				validateCurrencyPair(pair);
				// looks good, add to longOnC2
				shortOnC2.put(pair, size);
			}
			// load currentlyOpens
			String[] currentlyOpenLine = props.getProperty(CURRENTLY_OPEN).split(",");
			for (String pair : currentlyOpenLine) {
				if (pair.trim().equals("")) continue;
				pair = pair.trim().toUpperCase();
				validateCurrencyPair(pair);
				// add to reversed collection
				currentlyOpen.add(pair);
			}
		} catch (IOException ioe) {
			Logger.error("Error loading manualentry.properties save data: " + ioe);
			ioe.printStackTrace(Logger.err);
			C2OBridge.crash();
		} catch (RuntimeException re) {
			Logger.error("Error loading manualentry.properties save data, corruption: " + re);
			re.printStackTrace(Logger.err);
			C2OBridge.crash();
		}
    }

	@Override
	public final void shutdown() {
		Properties props = new Properties();

		// save longOnC2
		String longOnC2Line = "";
		for (String pair : longOnC2.keySet()) {
			if (!longOnC2Line.equals("")) longOnC2Line += ",";
			longOnC2Line += pair + "/" + longOnC2.get(pair);
		}
		props.put(LONG_ON_C2, longOnC2Line);

		// save shortOnC2
		String shortOnC2Line = "";
		for (String pair : shortOnC2.keySet()) {
			if (!shortOnC2Line.equals("")) shortOnC2Line += ",";
			shortOnC2Line += pair + "/" + shortOnC2.get(pair);
		}
		props.put(SHORT_ON_C2, shortOnC2Line);

		// save currentlyOpen
		String currentlyOpenLine = "";
		for (String pair : currentlyOpen) {
			if (!currentlyOpenLine.equals("")) currentlyOpenLine += ",";
			currentlyOpenLine += pair;
		}
		props.put(CURRENTLY_OPEN, currentlyOpenLine);

		// save to file
		try {
			FileOutputStream fos = new FileOutputStream(MANUAL_ENTRY_FILE);
			props.store(fos, null);
			fos.close();
		} catch (IOException ioe) {
			Logger.error("Unable to save ManualEntryStrategy data: " + ioe);
			ioe.printStackTrace(Logger.err);
		}
	}

	public void tryManualEntry(String side, String pair, int size, double multiplier) {
		if (currentlyOpen.contains(pair)) {
			Logger.console("[ManualEntryStrategy] Pair [" + pair + "] has already been manually entered. No action taken.");
			return;
		}

		boolean tradeLong = side.equals(BUY);
		boolean longedOnC2 = longOnC2.containsKey(pair);
		boolean shortedOnC2 = shortOnC2.containsKey(pair);

		if ((tradeLong && longedOnC2) || (!tradeLong && shortedOnC2)) {
			// check that the size is correct
			size *= C2_ONE_TO_UNITS; // 1 'unit' (# column) on C2 website is equivalent to 10,000 units of currency pair
			int loggedSize;
			if (tradeLong) loggedSize = longOnC2.get(pair);
			else loggedSize = shortOnC2.get(pair);
			if (loggedSize != size) {
				Logger.console("[ManualEntryStrategy] For [" + pair + "], logged size is " + loggedSize + " but entered size is " + size + ". No action taken.");
				return;
			}

			// add to currentlyOpen
			currentlyOpen.add(pair);
			// send request to oanda
			double accountBalance = getAccountBalance();
			int ourSize = convert(size, accountBalance);
			// adjust according to multiplier
			ourSize = (int) Math.ceil(ourSize * multiplier);

			Logger.console("[ManualEntryStrategy] Converted C2 size of [" + size + "] into our proportional Oanda size of [" + ourSize + "] units (Multiplier: " + multiplier + ")");
			openTrade(side, ourSize, pair);
			Logger.console("[ManualEntryStrategy] Successfully entered trade for [" + pair + "] of size [" + ourSize + "] units.");
		} else {
			Logger.console("[ManualEntryStrategy] Pair [" + pair + "] isn't on the correct side (or possibly any side at all) on C2. Check and try again.");
		}
	}

    @Override
    public void handleInfo(String action, String side, int psize, String pair, double c2OpenedPrice) throws IOException {
		boolean opening = action.equals(OPEN);
		boolean tradeLong = side.equals(BUY); // flag for trade long or short
		boolean longedOnC2 = longOnC2.containsKey(pair);
		boolean shortedOnC2 = shortOnC2.containsKey(pair);

		if (opening) {
			boolean weHaveOpen = currentlyOpen.contains(pair); // whether or not we have this pair currently opened (ie. manually entered)
			boolean addedToByC2 = longedOnC2 || shortedOnC2; // whether or not it has already been logged as open on C2
			if (weHaveOpen || addedToByC2) {
				if (weHaveOpen) {
					// we've entered this pair, let's add to our position
					// first, check that the pip difference isn't too huge against us
					double livePrice = getOandaPrice(side, pair);
					double diff = Math.abs(livePrice - c2OpenedPrice);
					if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && livePrice < c2OpenedPrice) || (side.equals(SELL) && livePrice > c2OpenedPrice)) {
						// difference is small enough, actually open the trade
						double accountBalance = getAccountBalance();
						int ourPsize = convert(psize, accountBalance);
						openTrade(side, ourPsize, pair);
						Logger.info("[ManualEntryStrategy] Pair [" + pair + "] was added to by C2, and copied by this strategy.");
					} else {
						// show alert, didn't make trade because diff was too large
						Logger.info("[ManualEntryStrategy] Pair [" + pair + "] was added to by C2, but missed out because pip diff was [" + diff + "] pips.");
					}
				}
				// update our logs of C2's position size, regardless of whether or not we have manually entered this yet
				Map<String, Integer> onC2;
				if (longedOnC2) onC2 = longOnC2;
				else onC2 = shortOnC2;
				int currentSize = onC2.get(pair);
				int newSize = currentSize + psize;
				onC2.put(pair, newSize);
			} else {
				// this is a fresh trade on C2. Update our records and log the trade
				if (tradeLong) longOnC2.put(pair, psize);
				else shortOnC2.put(pair, psize);
			}
		} else {
			// if currently open on Oanda, close them
			if (currentlyOpen.contains(pair)) {
				List<JSONObject> list = getTrades(pair); // json of all currently open trades for this pair
				for (JSONObject trade : list) {
					// close every trade returned for this pair
					long tradeId = trade.getLong(ID);
					closeTrade(tradeId);
				}
				currentlyOpen.remove(pair);
			}
			// remove from long/short on C2
			if (longOnC2.containsKey(pair)) longOnC2.remove(pair);
			if (shortOnC2.containsKey(pair)) shortOnC2.remove(pair);
		}
    }
}
