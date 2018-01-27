package put.ai.games.myplayer;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.System.currentTimeMillis;
import static java.util.stream.StreamSupport.stream;

/**
 * class made for, and used by tree searching algorithms ( min-max, AB )
 *
 * @author Witold Kupś 127088 Mikołaj Śledź 127310
 */
public class MagicPlayer extends Player {

    private static final Random random = new Random();
    private static final int MIN_DEPTH = 2;
    private final ExecutorService executor;

    private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

    public MagicPlayer() {
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public String getName() {
        return "WitoldAB Kupś 127088 Mikołaj Śledź 127310";
    }

    /**
     * implementation of function nextMove by out team
     * <p>
     * comment/uncomment blocks depending what algorithm you want to use, available:
     * - minmax
     * - alphabeta -off
     */
    @Override
    public Move nextMove(Board b) {
        final TimeMeasurer timeMeasurer = new TimeMeasurer();
        final Color playerColor = getColor();
        final PlayerType bestMoveFilter = PlayerType.getPlayer(playerColor);
        resetStopper();
        executor.submit(() -> {
                    for (int d = MIN_DEPTH; timeMeasurer.hasEnoughTime() && d < 10; d += 2) {
                        bestMoveFilter.validateResult(minMax(null, d, new AlphaBeta(), playerColor, b));
                    }
                    forceToStop();
                }
        );
        timeMeasurer.blockTillTimeEnd();
        forceToStop();
        System.out.println(bestMoveFilter.bestNode);
        if (bestMoveFilter.bestNode != null && bestMoveFilter.bestNode.move != null) {
            return bestMoveFilter.bestNode.move;
        } else {
            final List<Move> moves = b.getMovesFor(playerColor);
            return moves.get(random.nextInt(moves.size()));
        }
    }

    private void forceToStop() {
        isForcedToStop.set(true);
    }

    private void resetStopper() {
        isForcedToStop.set(false);
    }

    private class TimeMeasurer {
        private static final int TIME_SPACE = 100;
        private static final int SLEEP_TIME = TIME_SPACE - 20;
        private final long endTime;

        private TimeMeasurer() {
            endTime = currentTimeMillis() + getTime();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 1);
        }

        void blockTillTimeEnd() {
            while (hasEnoughTime()) {
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    throw new RuntimeException("STOPPED");
                }
            }
        }

        private boolean hasEnoughTime() {
            return currentTimeMillis() < endTime - TIME_SPACE && !isForcedToStop.get();
        }
    }

    /**
     * heuristic function to evaluate board state's "value"
     *
     * @param board : Board
     * @return integer
     */
    private int evaluateBoard(Board board) {
        int value = 0;
        final int size = board.getSize();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                value += getFieldValue(board, x, y);
            }
        }
        return value;
    }


    private int getFieldValue(Board board, int x, int y) {
        switch (board.getState(x, y)) {
            case PLAYER1:
                return 1;
            case PLAYER2:
                return -1;
            case EMPTY:
            default:
                return 0;
        }
    }

    /**
     * min-max algorithm for tree searching
     *
     * @param node   is actual processed node
     * @param depth  setting max depth of searching
     * @param player actual player, just use getColor()
     * @param board  actual board state
     * @return private MoveValueTree
     */
    private MoveValueTree minMax(Move node, int depth, AlphaBeta ab, Color player, Board board) {

        List<Move> moves = board.getMovesFor(player);

        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            double v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic);
        }

        // not leaf - do below
        return makeAMove(depth, ab.copy(), player, board, moves);
    }

    private MoveValueTree makeAMove(int depth, AlphaBeta ab, Color player, Board board, Iterable<Move> moves) {
        final PlayerType bestMoveFilter = PlayerType.getPlayer(player);
        final AtomicBoolean isCutOff = new AtomicBoolean(false);
        stream(moves.spliterator(), true)
                .forEach(child -> {
                    if (shouldStopSearching(isCutOff)) return;
                    Board b = board.clone();
                    b.doMove(child);
                    MoveValueTree child_node = minMax(child, depth - 1, ab, getOpponent(player), b);
                    if (shouldStopSearching(isCutOff)) return;
                    child_node.iteration = depth;
                    child_node.move = child;
                    if (bestMoveFilter.validateResult(child_node, ab)) {
                        isCutOff.set(true);
                    }
                });
        return bestMoveFilter.bestNode;
    }

    private boolean shouldStopSearching(AtomicBoolean isCutOff) {
        return isForcedToStop.get() || isCutOff.get();
    }

    private abstract static class PlayerType {
        abstract boolean isBetter(double currentVal, double bestVal);

        abstract void manageCutOff(MoveValueTree childMove, AlphaBeta ab);

        abstract void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab);

        volatile MoveValueTree bestNode;
        volatile int bestVal;

        private PlayerType(int startValue) {
            bestVal = startValue;
        }

        static PlayerType getPlayer(Color player) {
            return player.equals(Color.PLAYER1) ? new Maximizer() : new Minimizer();
        }

        synchronized boolean validateResult(MoveValueTree childMove, AlphaBeta ab) {
            validateResult(childMove);
            manageAlphaBeta(childMove, ab);
            if (ab.isBettaBellowAlpha()) {
                manageCutOff(childMove, ab);
                bestNode.move = null;
                return true;
            }
            return false;
        }

        synchronized void validateResult(MoveValueTree childMove) {
            if (childMove != null && childMove.move != null &&
                    (bestNode == null ||
                            isBetter(childMove.value, bestNode.value) ||
                            childMove.iteration > bestNode.iteration)) {
                bestNode = childMove;
            }
        }
    }

    private static class Maximizer extends PlayerType {

        private Maximizer() {
            super(MIN_VALUE);
        }

        @Override
        boolean isBetter(double currentVal, double bestVal) {
            return currentVal > bestVal;
        }

        @Override
        void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab) {
            if (childMove.value > ab.alpha) {
                ab.alpha = childMove.value;
                bestNode = childMove;
            }
        }

        @Override
        void manageCutOff(MoveValueTree childMove, AlphaBeta ab) {
            bestNode.value = ab.beta;
        }
    }

    private static class Minimizer extends PlayerType {

        private Minimizer() {
            super(MAX_VALUE);
        }

        @Override
        boolean isBetter(double currentVal, double oldVal) {
            return currentVal < bestVal;
        }

        @Override
        void manageCutOff(MoveValueTree childMove, AlphaBeta ab) {
            childMove.value = ab.alpha;
        }

        @Override
        void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab) {
            if (childMove.value < ab.beta) {
                ab.beta = childMove.value;
                bestNode = childMove;
            }
        }
    }

    private class AlphaBeta {
        double alpha;
        double beta;

        AlphaBeta() {
            this.alpha = MIN_VALUE;
            this.beta = MAX_VALUE;
        }

        AlphaBeta(double alpha, double beta) {
            this.alpha = alpha;
            this.beta = beta;
        }

        AlphaBeta copy() {
            return new AlphaBeta(alpha, beta);
        }

        boolean isBettaBellowAlpha() {
            return beta <= alpha;
        }
    }

    private static class MoveValueTree {
        double value;
        Move move;
        int iteration;

        MoveValueTree(Move move, double value) {
            this.move = move;
            this.value = value;
        }

        @Override
        public String toString() {
            return "MoveValueTree{" + "value=" + value +
                    ", move=" + move +
                    ", iteration=" + iteration +
                    '}';
        }

    }

    public static void main(String[] args) {

    }
}