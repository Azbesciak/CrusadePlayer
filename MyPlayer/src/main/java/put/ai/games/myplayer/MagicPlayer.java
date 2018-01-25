package put.ai.games.myplayer;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.Integer.MIN_VALUE;
import static java.lang.System.currentTimeMillis;
import static java.util.stream.StreamSupport.stream;

/**
 * class made for, and used by tree searching algorithms ( min-max, AB )
 *
 * @author Witold Kupś 127088 Mikołaj Śledź 127310
 */
public class MagicPlayer extends Player {

    private static final BiPredicate<Integer, Integer> IS_CURRENT_BIGGER = (current, best) -> current > best;
    private static final BiPredicate<Integer, Integer> IS_CURRENT_LOWER = (current, best) -> current < best;
    private static final Random random = new Random();
    private static final int MAX_DEPTH = 4;
    private static final double VALUE_MULTIPLIER = 1.25;
    private final ExecutorService executor;

    private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

    public MagicPlayer() {
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public String getName() {
        return "Witold SIMPLE 127088 Mikołaj Śledź 127310";
    }

    /**
     * implementation of function nextMove by out team
     * <p>
     * comment/uncomment blocks depending what algorithm you want to use, available:
     * - naive -off
     * - minmax
     * - alphabeta -off
     */
    @Override
    public Move nextMove(Board b) {
        final TimeMeasurer timeMeasurer = new TimeMeasurer();
        final Color playerColor = getColor();
        final BestMoveFilter bestMoveFilter = new BestMoveFilter(playerColor);
        resetStopper();
        executor.submit(() -> {
                    IntStream.range(1, MAX_DEPTH).parallel()
                            .mapToObj(depth -> minMax(null, depth, playerColor, b))
                            .forEach(bestMoveFilter::testAndAssignIfBetter);
                    forceToStop();
                }
        );
        timeMeasurer.blockTillTimeEnd();
        forceToStop();
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
        }

        void blockTillTimeEnd() {
            while (currentTimeMillis() < endTime - TIME_SPACE && !isForcedToStop.get()) {
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    throw new RuntimeException("STOPPED");
                }
            }
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

    private int evaluateFieldWithNeighbours(Board board, int x, int y) {
        final int size = board.getSize();
        int value = getFieldValue(board, x, y);
        if (value == 0) return 0;
        if (x >= 1) {
            value += countValueVertically(board, x-1, y, size);
        }
        if (x < size - 1) {
            value += countValueVertically(board, x+1, y, size);
        }

        return value + countOnVerticalEdges(board, x, y, size);
    }

    private int countValueVertically(Board board, int x, int y, int size) {
        return getFieldValue(board, x, y) + countOnVerticalEdges(board, x, y, size);
    }

    private int countOnVerticalEdges(Board board, int x, int y, int size) {
        int value = 0;
        if (y >= 1) {
            value += getFieldValue(board, x, y-1);
        }
        if (y < size-1) {
            value += getFieldValue(board, x, y+1);
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
    private MoveValueTree minMax(Move node, int depth, Color player, Board board) {

        List<Move> moves = board.getMovesFor(player);

        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            int v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic);
        }

        // not leaf - do below
        return makeAMove(depth, player, board, moves);
    }

    private MoveValueTree makeAMove(int depth, Color player, Board board, Iterable<Move> moves) {
        final BestMoveFilter bestMoveFilter = new BestMoveFilter(player);
        stream(moves.spliterator(), true)
                .forEach(child -> {
                    Board b = board.clone();
                    b.doMove(child);
                    MoveValueTree child_node = minMax(child, depth - 1, getOpponent(player), b);
                    if (isForcedToStop.get()) {
                        return;
                    }
                    child_node.iteration = depth;
                    child_node.move = child;
                    child_node.value *= VALUE_MULTIPLIER;
                    bestMoveFilter.testAndAssignIfBetter(child_node);
                });
        return bestMoveFilter.bestNode;
    }

    private static class BestMoveFilter {
        final PlayerType playerType;
        MoveValueTree bestNode;
        final AtomicInteger bestVal;

        private BestMoveFilter(Color player) {
            playerType = getPlayerType(player);
            bestVal = new AtomicInteger(playerType.startValue);
        }

        synchronized void testAndAssignIfBetter(MoveValueTree childMove) {
            if (childMove != null &&
                    (bestNode == null || playerType.isBetter.test(childMove.value, bestVal.get()))) {
                bestVal.set(childMove.value);
                bestNode = childMove;
            }
        }

        private PlayerType getPlayerType(Color player) {
            return player.equals(Color.PLAYER1) ? PlayerType.MAXIMIZER : PlayerType.MINIMIZER;
        }
    }

    private enum PlayerType {
        MAXIMIZER(MIN_VALUE, IS_CURRENT_BIGGER),
        MINIMIZER(MIN_VALUE, IS_CURRENT_LOWER);

        final int startValue;
        final BiPredicate<Integer, Integer> isBetter;

        PlayerType(int minValue, BiPredicate<Integer, Integer> isBetter) {
            this.startValue = minValue;
            this.isBetter = isBetter;
        }
    }

    private static class MoveValueTree {
        int value;
        Move move;
        int iteration;

        MoveValueTree(Move move, int value) {
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