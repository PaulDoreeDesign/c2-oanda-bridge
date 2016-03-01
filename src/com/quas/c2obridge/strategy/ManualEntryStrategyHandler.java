package com.quas.c2obridge.strategy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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

    /** Properties file keys */
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

        // load long and short on C2 pairs from file
        // @TODO
    }

    @Override
    public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
        if (currentlyOpen.contains(pair)) {
            // we've entered this pair, let's add to our position

        } else if (longOnC2.containsKey(pair) || shortOnC2.containsKey(pair)) {
            // we haven't entered this pair, but it is being increased on C2. Update our records to reflect that increase

        } else {
            // this is a fresh trade on C2. Update our records and log the trade
            

        }
    }
}
