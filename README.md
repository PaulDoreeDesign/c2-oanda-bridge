Bridge between Collective2 and Oanda for forex trading.

Scrapes site/emails from Collective2 and translates them into actions to take on Oanda.

Strategies list:
- CopyStrategy: Replicate scraped actions exactly. Can increase/decrease position sizes relative to account size by altering CopyStrategyHandler.POS_SIZE_MULTIPLIER
- SmartCopy: Replicate scraped actions exactly, but place stop-loss at SmartCopyStrategyHandler.C2_STOPLOSS_PERCENT %. When a pair that already has an existing trade is added to, stop-losses are adjusted to account for the position addition.
- ReverseStrategy: Reverses every single trade scraped, with a flat stop-loss and trailing stop.
- ManualEntryStrategy: Entry into a trade scraped off C2 must be manual. After the trade has manually been entered, all additions to the position made by C2 are automated and cloned.

New strategies may be created by extending the StrategyHandler abstract class and overriding the handleInfo() method.
