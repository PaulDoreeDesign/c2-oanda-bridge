/**
 * Script to help calculate the optimal stop-loss for C2 strategy.
 */
var STOP_LOSSES = [ 0.1, 0.2, 0.3, 0.5, 0.75, 1.0, 1.25, 1.5, 2, 2.25, 2.5, 2.75, 3, 3.25, 3.5, 3.75, 4, 5, 6, 8, 10, 50, 99 ];

function calc(stopLoss) {
    var len = DATA.length;
    var losses = 0; // number of trades that are lost with the given stop loss
    var wins = 0; // number of trades that are won with the given stop loss
    var totalProfitPercent = 0;
    for (var i = 0; i < len; i++) {
        var drawdownPercent = -DATA[i][10];
        var drawdownDollars = -DATA[i][11];
        var profitDollars = DATA[i][15];
        if (isNaN(drawdownPercent) || isNaN(drawdownDollars)) {
            drawdownPercent = 0;
            drawdownDollars = 0;
        }
        if (drawdownPercent <= stopLoss) {
            // increment wins
            wins++;
            // calculate percentage profit
            if (drawdownPercent != 0) {
                var percentPerDollar = drawdownPercent / drawdownDollars;
                var profitPercent = percentPerDollar * profitDollars;
                totalProfitPercent += profitPercent;
            } else {
                // guesstimate the profit percent
                totalProfitPercent += 0.25;
            }
        } else {
            // increment losses
            losses++;
        }
    }

    var averageProfitPercent = round4(totalProfitPercent / wins);
    var ratio = round4(wins / losses);
    var percentRatio = round4(totalProfitPercent / (stopLoss * losses));
    var winPercent = round4(wins / (wins + losses) * 100);

    console.log('# wins: ' + wins);
    console.log('# losses: ' + losses);
    console.log('Win:loss count ratio: ' + ratio + ':1 (' + winPercent + ' %)');
    console.log('Average % per win: ' + averageProfitPercent);
    console.log('Exact % per loss: ' + stopLoss);
    console.log('Win:loss percentage ratio: ' + percentRatio + ':1');
}

for (var i = 0; i < STOP_LOSSES.length; i++) {
    console.log('----- RUNNING STOP-LOSS = ' + STOP_LOSSES[i] + '% -----');
    calc(STOP_LOSSES[i]);
    console.log('------------------------------------');
}

