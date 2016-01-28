/**
 * Script to help calculate the optimal stop-loss for C2 strategy.
 */
var PIPS = [ 1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 75, 80, 90, 100, 999999 ];

var BUY = 'BUY';
var SELL = 'SELL';
var JPY = 'JPY';

function calc(pipsProfit) {
    var profitableTrades = 0;
    var withinPipsProfitLimit = 0;
    var withinLimitAndLessThan100 = 0;
    var drawdownGreaterThanPips = 0;
    for (var i = 0; i < DATA.length; i++) {
        var row = DATA[i];
        var pair = row[3];
        var action = row[1]; // 'BUY' or 'SELL'
        if (action !== BUY && action !== SELL) throw new Error('Invalid action: ' + action);
        var openPrice = Number(row[5]);
        var closePrice = Number(row[8]);
        var units = Number(row[6]);
        var drawdownPercentage = Number(row[10]);
        var profit = Number(row[15]);
        var worstPrice = Number(row[14]);

        // only counting profitable trades
        if (profit > 0) {
            profitableTrades++;

            // difference between openPrice and closePrice
            var priceDiff = (action === BUY) ? (closePrice - openPrice) : (openPrice - closePrice);
            // worst price
            var worstDiff = (action === BUY) ? (openPrice - worstPrice) : (worstPrice - openPrice);

            // convert the difference and worstDiff to pips
            var isJPYPair = pair.indexOf(JPY) > -1;
            var pipsGained = isJPYPair ? (priceDiff * 100) : (priceDiff * 10000);
            var pipsWorst = isJPYPair ? (worstDiff * 100) : (worstDiff * 10000);

            if (pipsGained <= pipsProfit) {
                withinPipsProfitLimit++;
                if (units <= 100) {
                    withinLimitAndLessThan100++;
                }
            }
            if (pipsWorst >= pipsProfit) {
                drawdownGreaterThanPips++;
            }
        }
    }
    console.log('Profitable trades less than ' + pipsProfit + ' pips profit: ' + withinPipsProfitLimit + ' (' + Math.round(withinPipsProfitLimit / profitableTrades * 100) + '%)');
    console.log('Profitable trades within limit AND less than 100 units: ' + withinLimitAndLessThan100 + ' (' + Math.round(withinLimitAndLessThan100 / profitableTrades * 100) + '%)');
    console.log('Profitable trades with drawdown greater than ' + pipsProfit + ' pips: ' + drawdownGreaterThanPips + ' (' + Math.round(drawdownGreaterThanPips / profitableTrades * 100) + '%)');
    console.log('----------');
}

console.log('Total number of trades: 365');

for (var i = 0; i < PIPS.length; i++) {
    calc(PIPS[i]);
}
